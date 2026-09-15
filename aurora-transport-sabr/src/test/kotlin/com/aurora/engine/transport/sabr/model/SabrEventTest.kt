package com.aurora.engine.transport.sabr.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SabrEventTest {

    @Test
    fun `MediaData equality considers content`() {
        val a = SabrEvent.MediaData(251, byteArrayOf(0x01, 0x02), 0)
        val b = SabrEvent.MediaData(251, byteArrayOf(0x01, 0x02), 0)
        val c = SabrEvent.MediaData(251, byteArrayOf(0x01, 0x03), 0)

        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `FormatInit equality considers content`() {
        val a = SabrEvent.FormatInit(140, byteArrayOf(0x00, 0x01))
        val b = SabrEvent.FormatInit(140, byteArrayOf(0x00, 0x01))
        val c = SabrEvent.FormatInit(140, byteArrayOf(0xFF.toByte()))

        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun `MediaEnd is a simple data class`() {
        val a = SabrEvent.MediaEnd(251)
        val b = SabrEvent.MediaEnd(251)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `NextRequestPolicy with null fields`() {
        val event = SabrEvent.NextRequestPolicy(null, null, null)
        assertThat(event.playbackCookie).isNull()
        assertThat(event.backoffMs).isNull()
        assertThat(event.targetBufferDurationMs).isNull()
    }

    @Test
    fun `NextRequestPolicy equality considers content`() {
        val cookie = byteArrayOf(0x01, 0x02)
        val a = SabrEvent.NextRequestPolicy(cookie, 500, 10000)
        val b = SabrEvent.NextRequestPolicy(cookie, 500, 10000)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `ReloadRequired stores reason`() {
        val event = SabrEvent.ReloadRequired("cdn_rotation")
        assertThat(event.reason).isEqualTo("cdn_rotation")
    }

    @Test
    fun `ServerRedirect stores endpoint`() {
        val event = SabrEvent.ServerRedirect("https://rr3.googlevideo.com/")
        assertThat(event.newEndpoint).isEqualTo("https://rr3.googlevideo.com/")
    }

    @Test
    fun `StreamError stores code and message`() {
        val event = SabrEvent.StreamError(403, "forbidden")
        assertThat(event.code).isEqualTo(403)
        assertThat(event.message).isEqualTo("forbidden")
    }

    @Test
    fun `UnknownPart stores type and size`() {
        val event = SabrEvent.UnknownPart(999, 42)
        assertThat(event.typeId).isEqualTo(999)
        assertThat(event.payloadSize).isEqualTo(42)
    }
}
