package com.aurora.engine.transport.sabr.protocol

import com.aurora.engine.transport.sabr.model.SabrEvent
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SabrMessageDecoderTest {

    private lateinit var decoder: SabrMessageDecoder

    @BeforeEach
    fun setUp() {
        decoder = SabrMessageDecoder()
    }

    // --- Helper: build a simple protobuf varint field ---

    /**
     * Encode a protobuf field tag + varint value.
     * tag = (fieldNumber shl 3) | wireType
     */
    private fun protoVarintField(fieldNumber: Int, value: Long): ByteArray {
        val tag = (fieldNumber shl 3) or 0 // wire type 0 = varint
        return encodeProtobufVarint(tag.toLong()) + encodeProtobufVarint(value)
    }

    /**
     * Encode a protobuf length-delimited field.
     */
    private fun protoBytesField(fieldNumber: Int, data: ByteArray): ByteArray {
        val tag = (fieldNumber shl 3) or 2 // wire type 2 = length-delimited
        return encodeProtobufVarint(tag.toLong()) +
            encodeProtobufVarint(data.size.toLong()) +
            data
    }

    private fun encodeProtobufVarint(value: Long): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add((v and 0x7F).toByte())
        return result.toByteArray()
    }

    // --- MEDIA_DATA tests ---

    @Test
    fun `decode MEDIA_DATA with protobuf fields`() {
        val mediaBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val payload = protoVarintField(1, 251) + // formatId = 251
            protoVarintField(2, 1024) +            // offset = 1024
            protoBytesField(3, mediaBytes)          // data

        val event = decoder.decode(UmpPart(UmpMessageType.MEDIA_DATA, payload))

        assertThat(event).isInstanceOf(SabrEvent.MediaData::class.java)
        val mediaData = event as SabrEvent.MediaData
        assertThat(mediaData.formatId).isEqualTo(251)
        assertThat(mediaData.offsetBytes).isEqualTo(1024)
        assertThat(mediaData.data).isEqualTo(mediaBytes)
    }

    @Test
    fun `decode MEDIA_DATA with empty payload uses fallback`() {
        val event = decoder.decode(UmpPart(UmpMessageType.MEDIA_DATA, ByteArray(0)))

        assertThat(event).isInstanceOf(SabrEvent.MediaData::class.java)
        val mediaData = event as SabrEvent.MediaData
        assertThat(mediaData.formatId).isEqualTo(0)
        assertThat(mediaData.offsetBytes).isEqualTo(0)
    }

    // --- FORMAT_INIT tests ---

    @Test
    fun `decode FORMAT_INIT with protobuf fields`() {
        val initData = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val payload = protoVarintField(1, 140) + protoBytesField(2, initData)

        val event = decoder.decode(UmpPart(UmpMessageType.FORMAT_INIT, payload))

        assertThat(event).isInstanceOf(SabrEvent.FormatInit::class.java)
        val formatInit = event as SabrEvent.FormatInit
        assertThat(formatInit.formatId).isEqualTo(140)
        assertThat(formatInit.initSegment).isEqualTo(initData)
    }

    // --- MEDIA_END tests ---

    @Test
    fun `decode MEDIA_END extracts formatId`() {
        val payload = protoVarintField(1, 251)

        val event = decoder.decode(UmpPart(UmpMessageType.MEDIA_END, payload))

        assertThat(event).isInstanceOf(SabrEvent.MediaEnd::class.java)
        assertThat((event as SabrEvent.MediaEnd).formatId).isEqualTo(251)
    }

    // --- NEXT_REQUEST_POLICY tests ---

    @Test
    fun `decode NEXT_REQUEST_POLICY with all fields`() {
        val cookie = byteArrayOf(0x01, 0x02, 0x03)
        val payload = protoBytesField(1, cookie) +
            protoVarintField(2, 500) +
            protoVarintField(3, 10000)

        val event = decoder.decode(UmpPart(UmpMessageType.NEXT_REQUEST_POLICY, payload))

        assertThat(event).isInstanceOf(SabrEvent.NextRequestPolicy::class.java)
        val policy = event as SabrEvent.NextRequestPolicy
        assertThat(policy.playbackCookie).isEqualTo(cookie)
        assertThat(policy.backoffMs).isEqualTo(500)
        assertThat(policy.targetBufferDurationMs).isEqualTo(10000)
    }

    @Test
    fun `decode NEXT_REQUEST_POLICY with empty payload uses defaults`() {
        val event = decoder.decode(UmpPart(UmpMessageType.NEXT_REQUEST_POLICY, ByteArray(0)))

        assertThat(event).isInstanceOf(SabrEvent.NextRequestPolicy::class.java)
        val policy = event as SabrEvent.NextRequestPolicy
        assertThat(policy.playbackCookie).isNull()
        assertThat(policy.backoffMs).isNull()
        assertThat(policy.targetBufferDurationMs).isNull()
    }

    // --- RELOAD tests ---

    @Test
    fun `decode RELOAD extracts reason string`() {
        val reason = "cdn_rotation"
        val event = decoder.decode(UmpPart(UmpMessageType.RELOAD, reason.toByteArray()))

        assertThat(event).isInstanceOf(SabrEvent.ReloadRequired::class.java)
        assertThat((event as SabrEvent.ReloadRequired).reason).isEqualTo("cdn_rotation")
    }

    @Test
    fun `decode RELOAD empty payload gives default reason`() {
        val event = decoder.decode(UmpPart(UmpMessageType.RELOAD, ByteArray(0)))

        assertThat(event).isInstanceOf(SabrEvent.ReloadRequired::class.java)
        assertThat((event as SabrEvent.ReloadRequired).reason).isEqualTo("server_requested_reload")
    }

    // --- REDIRECT tests ---

    @Test
    fun `decode REDIRECT extracts endpoint`() {
        val endpoint = "https://rr3---sn-abc.googlevideo.com/videoplayback"
        val event = decoder.decode(UmpPart(UmpMessageType.REDIRECT, endpoint.toByteArray()))

        assertThat(event).isInstanceOf(SabrEvent.ServerRedirect::class.java)
        assertThat((event as SabrEvent.ServerRedirect).newEndpoint).isEqualTo(endpoint)
    }

    // --- STREAM_ERROR tests ---

    @Test
    fun `decode STREAM_ERROR with code and message`() {
        val message = "format_unavailable"
        val payload = protoVarintField(1, 403) + protoBytesField(2, message.toByteArray())

        val event = decoder.decode(UmpPart(UmpMessageType.STREAM_ERROR, payload))

        assertThat(event).isInstanceOf(SabrEvent.StreamError::class.java)
        val error = event as SabrEvent.StreamError
        assertThat(error.code).isEqualTo(403)
        assertThat(error.message).isEqualTo("format_unavailable")
    }

    // --- Unknown types ---

    @Test
    fun `decode unknown type produces UnknownPart`() {
        val event = decoder.decode(UmpPart(999, byteArrayOf(0x01, 0x02)))

        assertThat(event).isInstanceOf(SabrEvent.UnknownPart::class.java)
        val unknown = event as SabrEvent.UnknownPart
        assertThat(unknown.typeId).isEqualTo(999)
        assertThat(unknown.payloadSize).isEqualTo(2)
    }

    @Test
    fun `decode ONESIE_HEADER maps to UnknownPart`() {
        val event = decoder.decode(UmpPart(UmpMessageType.ONESIE_HEADER, byteArrayOf(0x01)))

        assertThat(event).isInstanceOf(SabrEvent.UnknownPart::class.java)
        assertThat((event as SabrEvent.UnknownPart).typeId).isEqualTo(UmpMessageType.ONESIE_HEADER)
    }

    @Test
    fun `decode MEDIA_HEADER maps to UnknownPart`() {
        val event = decoder.decode(UmpPart(UmpMessageType.MEDIA_HEADER, byteArrayOf(0x01)))

        assertThat(event).isInstanceOf(SabrEvent.UnknownPart::class.java)
        assertThat((event as SabrEvent.UnknownPart).typeId).isEqualTo(UmpMessageType.MEDIA_HEADER)
    }
}
