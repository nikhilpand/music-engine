package com.aurora.engine.player.android.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioFocusManagerTest {

    private lateinit var context: Context
    private lateinit var fakeListener: FakeAudioFocusListener
    private lateinit var focusManager: AudioFocusManager

    class FakeAudioFocusListener : AudioFocusListener {
        var paused = false
        var transientPause = false
        var resumed = false
        var volumeMultiplier = 1.0f

        override fun onPauseRequested(transient: Boolean) {
            paused = true
            transientPause = transient
        }

        override fun onResumeRequested() {
            resumed = true
        }

        override fun onVolumeMultiplierChanged(multiplier: Float) {
            volumeMultiplier = multiplier
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeListener = FakeAudioFocusListener()
        focusManager = AudioFocusManager(context, fakeListener)
    }

    @Test
    fun `requestAudioFocus succeeds on system audio manager`() {
        val granted = focusManager.requestAudioFocus()
        assertThat(granted).isTrue()
    }

    @Test
    fun `transient focus loss triggers transient pause`() {
        focusManager.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertThat(fakeListener.paused).isTrue()
        assertThat(fakeListener.transientPause).isTrue()
    }

    @Test
    fun `audio focus gain after transient loss triggers resume`() {
        focusManager.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertThat(fakeListener.paused).isTrue()

        focusManager.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertThat(fakeListener.resumed).isTrue()
        assertThat(fakeListener.volumeMultiplier).isEqualTo(1.0f)
    }

    @Test
    fun `transient can duck drops volume multiplier to 20 percent`() {
        focusManager.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertThat(fakeListener.volumeMultiplier).isEqualTo(0.2f)
    }

    @Test
    fun `permanent focus loss pauses without transient flag`() {
        focusManager.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertThat(fakeListener.paused).isTrue()
        assertThat(fakeListener.transientPause).isFalse()
    }
}
