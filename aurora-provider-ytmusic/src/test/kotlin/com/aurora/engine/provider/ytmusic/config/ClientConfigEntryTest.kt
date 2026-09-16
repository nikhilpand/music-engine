package com.aurora.engine.provider.ytmusic.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.google.common.truth.Truth.assertThat

class ClientConfigEntryTest {

    @Test
    fun `default flags are correct for non-cipher client`() {
        val entry = ClientConfigEntry(
            clientName = "TEST",
            clientVersion = "1.0",
            userAgent = "TestAgent/1.0"
        )
        assertThat(entry.requiresCipher).isFalse()
        assertThat(entry.requiresPoToken).isFalse()
        assertThat(entry.supportsSabr).isFalse()
        assertThat(entry.supportsAudioOnly).isTrue()
        assertThat(entry.supportsMetadata).isFalse()
        assertThat(entry.enabled).isTrue()
        assertThat(entry.priority).isEqualTo(0)
    }

    @Test
    fun `blank clientName is rejected`() {
        assertThrows<IllegalArgumentException> {
            ClientConfigEntry(clientName = "", clientVersion = "1.0", userAgent = "test")
        }
    }

    @Test
    fun `blank clientVersion is rejected`() {
        assertThrows<IllegalArgumentException> {
            ClientConfigEntry(clientName = "TEST", clientVersion = "", userAgent = "test")
        }
    }

    @Test
    fun `SABR-capable client with cipher`() {
        val entry = ClientConfigEntry(
            clientName = "ANDROID_MUSIC",
            clientVersion = "6.42.52",
            userAgent = "com.google.android.apps.youtube.music/6.42.52",
            requiresCipher = false,
            supportsSabr = true,
            supportsMetadata = true,
            priority = 90,
            metadataPriority = 80
        )
        assertThat(entry.supportsSabr).isTrue()
        assertThat(entry.supportsMetadata).isTrue()
        assertThat(entry.priority).isEqualTo(90)
    }
}
