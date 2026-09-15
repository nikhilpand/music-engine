package com.aurora.engine.player.android.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

interface AudioFocusListener {
    fun onPauseRequested(transient: Boolean)
    fun onResumeRequested()
    fun onVolumeMultiplierChanged(multiplier: Float)
}

/**
 * Manages audio focus requests, ducking, transient interruptions, and
 * the ACTION_AUDIO_BECOMING_NOISY broadcast (e.g., unplugged headphones).
 */
class AudioFocusManager(
    private val context: Context,
    private val listener: AudioFocusListener,
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
) : AudioManager.OnAudioFocusChangeListener {

    private var audioFocusRequest: AudioFocusRequest? = null
    private var pausedForTransientFocusLoss = false
    private var isNoisyReceiverRegistered = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                // User unplugged headphones or Bluetooth disconnected - pause immediately
                listener.onPauseRequested(transient = false)
            }
        }
    }

    /**
     * Requests audio focus before playback begins.
     * Returns true if focus was granted.
     */
    fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        registerNoisyReceiver()

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()

            audioFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Abandons audio focus when playback stops or is released.
     */
    fun abandonAudioFocus() {
        val am = audioManager ?: return
        unregisterNoisyReceiver()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(this)
        }
        pausedForTransientFocusLoss = false
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                listener.onVolumeMultiplierChanged(1.0f)
                if (pausedForTransientFocusLoss) {
                    pausedForTransientFocusLoss = false
                    listener.onResumeRequested()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck volume down to 20%
                listener.onVolumeMultiplierChanged(0.2f)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss (e.g. phone call or notification alert)
                pausedForTransientFocusLoss = true
                listener.onPauseRequested(transient = true)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent focus loss
                pausedForTransientFocusLoss = false
                listener.onPauseRequested(transient = false)
                abandonAudioFocus()
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            try {
                val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                context.registerReceiver(becomingNoisyReceiver, filter)
                isNoisyReceiverRegistered = true
            } catch (_: Exception) {
            }
        }
    }

    private fun unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                context.unregisterReceiver(becomingNoisyReceiver)
            } catch (_: Exception) {
            } finally {
                isNoisyReceiverRegistered = false
            }
        }
    }
}
