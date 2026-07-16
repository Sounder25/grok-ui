package com.offlinetts.reader.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.offlinetts.reader.pipeline.PlaybackBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Worker C: drains synthesized [com.offlinetts.reader.pipeline.AudioChunk]s from the
 * [PlaybackBuffer] and feeds them to [ExoPlayer] as a growing playlist of same-format WAV
 * items, one per chunk. Consecutive items with matching audio format play back-to-back
 * gaplessly, which is what makes paragraph boundaries inaudible to the listener.
 */
class TtsPlayer(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(appContext, InMemoryDataSource.Factory()))
        .build()
        .apply {
            // handleAudioFocus = false: focus is managed explicitly by AudioFocusManager
            // below so we can duck (lower volume) on transient loss instead of only
            // supporting the pause-or-nothing behavior ExoPlayer's built-in handling gives.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus= */ false,
            )
        }

    private val focusManager = AudioFocusManager(
        context = appContext,
        onDuck = { duck -> exoPlayer.volume = if (duck) 0.2f else 1.0f },
        onPause = { exoPlayer.pause() },
        onResume = { exoPlayer.play() },
    )

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlaybackEvent> = _events

    private var feederJob: Job? = null
    private var started = false

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) scope.launch { _events.emit(PlaybackEvent.Ended) }
            }
            override fun onPlayerError(error: PlaybackException) {
                scope.launch { _events.emit(PlaybackEvent.Error(error)) }
            }
        })
    }

    /** Starts draining [buffer] into the player. One [TtsPlayer] is meant to be used per reading session. */
    fun start(buffer: PlaybackBuffer) {
        stop()
        started = false
        InMemoryChunkRegistry.clear()

        feederJob = scope.launch {
            var index = 0
            while (true) {
                val chunk = withContext(Dispatchers.IO) { buffer.pop() } ?: break
                val wavBytes = WavPcmEncoder.encode(chunk.pcm, chunk.sampleRateHz)
                val uri = InMemoryChunkRegistry.register(index++, wavBytes)
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMimeType(MimeTypes.AUDIO_WAV)
                    .build()

                withContext(Dispatchers.Main) {
                    exoPlayer.addMediaItem(mediaItem)
                    if (!started) {
                        started = true
                        if (focusManager.request()) {
                            exoPlayer.prepare()
                            exoPlayer.play()
                        } else {
                            _events.tryEmit(PlaybackEvent.Error(IllegalStateException("Audio focus request denied")))
                        }
                    }
                }
            }
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun resume() {
        if (focusManager.request()) exoPlayer.play()
    }

    fun stop() {
        feederJob?.cancel()
        feederJob = null
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        focusManager.abandon()
        started = false
    }

    fun release() {
        stop()
        exoPlayer.release()
    }

    sealed interface PlaybackEvent {
        data object Ended : PlaybackEvent
        data class Error(val cause: Throwable) : PlaybackEvent
    }
}
