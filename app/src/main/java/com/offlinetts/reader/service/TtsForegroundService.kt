package com.offlinetts.reader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.offlinetts.reader.R
import com.offlinetts.reader.pipeline.PipelineState
import com.offlinetts.reader.pipeline.TextChunk
import com.offlinetts.reader.pipeline.TtsPipeline
import com.offlinetts.reader.playback.TtsPlayer
import com.offlinetts.reader.ui.MainActivity
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the synthesis + playback pipeline alive independent of the OS's background
 * process lifecycle. Without a foreground service (and its mandatory, un-dismissible
 * notification), Android will suspend or kill this process a few seconds after the user
 * locks the screen or backgrounds the app, silently cutting off playback mid-sentence.
 */
class TtsForegroundService : LifecycleService() {

    private lateinit var pipeline: TtsPipeline
    private lateinit var player: TtsPlayer

    override fun onCreate() {
        super.onCreate()
        pipeline = TtsPipeline(this, lifecycleScope)
        player = TtsPlayer(this, lifecycleScope)
        createNotificationChannel()

        pipeline.state.onEach { state ->
            updateNotification(state)
            if (state is PipelineState.Synthesizing && state.completed == 0) {
                player.start(pipeline.playbackBuffer)
            }
        }.launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification(PipelineState.Idle))

        when (intent?.action) {
            ACTION_START -> {
                val chunks = intent.getStringArrayListExtra(EXTRA_CHUNKS)
                    ?.mapIndexed { i, text -> TextChunk(i, text) } ?: emptyList()
                val voiceId = intent.getStringExtra(EXTRA_VOICE) ?: "af_heart"
                pipeline.start(chunks, voiceId)
            }
            ACTION_PAUSE -> player.pause()
            ACTION_RESUME -> player.resume()
            ACTION_STOP -> {
                pipeline.stop()
                player.stop()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pipeline.release()
        player.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null // Started, not bound: the UI talks to us via Intents + a broadcast/StateFlow-backed repository, not a live binder connection.
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(state: PipelineState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: PipelineState): Notification {
        val contentText = when (state) {
            is PipelineState.Idle -> getString(R.string.status_idle)
            is PipelineState.Synthesizing -> getString(R.string.status_synthesizing, state.completed, state.total)
            is PipelineState.Draining -> getString(R.string.status_playing)
            is PipelineState.Done -> getString(R.string.status_playing)
            is PipelineState.Error -> getString(R.string.status_error, state.message)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TtsForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.offlinetts.reader.action.START"
        const val ACTION_PAUSE = "com.offlinetts.reader.action.PAUSE"
        const val ACTION_RESUME = "com.offlinetts.reader.action.RESUME"
        const val ACTION_STOP = "com.offlinetts.reader.action.STOP"
        const val EXTRA_CHUNKS = "chunks"
        const val EXTRA_VOICE = "voice"

        fun start(context: Context, chunks: List<String>, voiceId: String) {
            val intent = Intent(context, TtsForegroundService::class.java)
                .setAction(ACTION_START)
                .putStringArrayListExtra(EXTRA_CHUNKS, ArrayList(chunks))
                .putExtra(EXTRA_VOICE, voiceId)
            context.startForegroundService(intent)
        }

        fun pause(context: Context) =
            context.startService(Intent(context, TtsForegroundService::class.java).setAction(ACTION_PAUSE))

        fun resume(context: Context) =
            context.startService(Intent(context, TtsForegroundService::class.java).setAction(ACTION_RESUME))

        fun stop(context: Context) =
            context.startService(Intent(context, TtsForegroundService::class.java).setAction(ACTION_STOP))
    }
}
