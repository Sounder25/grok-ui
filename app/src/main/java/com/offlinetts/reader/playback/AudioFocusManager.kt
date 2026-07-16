package com.offlinetts.reader.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Requests audio focus for speech playback and reacts to transient loss (a phone call, a
 * nav prompt, another app's short alert) by ducking, and to full loss (another app starts
 * its own playback) by pausing outright — without this, the OS would let the two audio
 * streams talk over each other.
 */
class AudioFocusManager(
    context: Context,
    private val onDuck: (duck: Boolean) -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pausedByFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocusLoss = true
                onPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedByFocusLoss = true
                onPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                onDuck(false)
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    onResume()
                }
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener(focusListener)
        .setWillPauseWhenDucked(false)
        .build()

    fun request(): Boolean {
        val result = audioManager.requestAudioFocus(focusRequest)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
