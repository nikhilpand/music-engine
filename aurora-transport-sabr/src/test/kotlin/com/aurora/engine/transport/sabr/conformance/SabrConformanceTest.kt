package com.aurora.engine.transport.sabr.conformance

import com.aurora.engine.transport.sabr.model.SabrEvent
import com.aurora.engine.transport.sabr.protocol.SabrMessageDecoder
import com.aurora.engine.transport.sabr.protocol.UmpFrameDecoder
import com.aurora.engine.transport.sabr.protocol.UmpMessageType
import com.aurora.engine.transport.sabr.protocol.UmpPart
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * SABR Conformance Tests — Phase 5, Component 0
 *
 * These tests validate the UMP frame decoder and message decoder against
 * structurally correct protocol frames, NOT just mocked internals.
 *
 * Each test constructs binary UMP frames exactly as a real SABR server would
 * produce them, feeds them through the full parsing pipeline, and asserts
 * that the resulting [SabrEvent] objects carry correct, usable data.
 *
 * ## Why this matters
 *
 * Phase 4 SABR tests used synthetic data. These conformance tests ensure:
 * - Varint encoding/decoding matches the UMP spec (prefix-coded, not protobuf base-128)
 * - Multi-part streams parse correctly in a single feed() call
 * - Partial/chunked delivery works across multiple feed() calls
 * - Protobuf field extraction inside payloads handles real field layouts
 * - Edge cases (zero-length payloads, unknown types, oversized parts) are handled
 */
@DisplayName("SABR Conformance Tests")
class SabrConformanceTest {

    private lateinit var frameDecoder: UmpFrameDecoder
    private lateinit var messageDecoder: SabrMessageDecoder

    @BeforeEach
    fun setup() {
        frameDecoder = UmpFrameDecoder()
        messageDecoder = SabrMessageDecoder()
    }

    // ── Helpers: Build structurally-correct UMP binary frames ────────────────

    /**
     * Encode a UMP varint (prefix-coded, NOT protobuf base-128).
     *
     * This matches the encoding described in UmpFrameDecoder:
     * - 0xxxxxxx (1 byte): 0–127
     * - 10xxxxxx yyyyyyyy (2 bytes): 128–16383
     * - 110xxxxx ... (3 bytes): 16384–2097151
     * - 1110xxxx ... (4 bytes): 2097152–268435455
     * - 11110xxx + 4 LE bytes (5 bytes): larger values
     */
    private fun encodeUmpVarint(value: Int): ByteArray {
        require(value >= 0) { "UMP varint must be non-negative" }
        return when {
            value < 0x80 -> byteArrayOf(value.toByte())
            value < 0x4000 -> byteArrayOf(
                (0x80 or (value shr 8)).toByte(),
                (value and 0xFF).toByte()
            )
            value < 0x200000 -> byteArrayOf(
                (0xC0 or (value shr 16)).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
            value < 0x10000000 -> byteArrayOf(
                (0xE0 or (value shr 24)).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
            else -> byteArrayOf(
                0xF0.toByte(),
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte()
            )
        }
    }

    /**
     * Build a complete UMP frame: typeVarint + sizeVarint + payload.
     */
    private fun buildUmpFrame(typeId: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeUmpVarint(typeId))
        out.write(encodeUmpVarint(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    /**
     * Encode a protobuf varint field (standard base-128 with MSB continuation).
     * fieldNumber << 3 | wireType=0, then value bytes.
     */
    private fun protoVarintField(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        // Tag: (fieldNumber << 3) | 0
        writeProtobufVarint(out, (fieldNumber.toLong() shl 3) or 0)
        writeProtobufVarint(out, value)
        return out.toByteArray()
    }

    /**
     * Encode a protobuf length-delimited field.
     * fieldNumber << 3 | wireType=2, then length varint, then bytes.
     */
    private fun protoBytesField(fieldNumber: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeProtobufVarint(out, (fieldNumber.toLong() shl 3) or 2)
        writeProtobufVarint(out, data.size.toLong())
        out.write(data)
        return out.toByteArray()
    }

    private fun writeProtobufVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    // ── Single-Part Conformance ─────────────────────────────────────────────

    @Nested
    @DisplayName("Single UMP Part Parsing")
    inner class SinglePartParsing {

        @Test
        @DisplayName("MEDIA_DATA (type 21) with protobuf payload produces correct MediaData event")
        fun mediaDataParsesCorrectly() {
            val mediaBytes = ByteArray(16) { (it + 0xAA).toByte() }
            val payload = ByteArrayOutputStream().apply {
                write(protoVarintField(1, 251))   // formatId = 251
                write(protoVarintField(2, 0))     // offsetBytes = 0
                write(protoBytesField(3, mediaBytes)) // data = 16 bytes
            }.toByteArray()

            val frame = buildUmpFrame(UmpMessageType.MEDIA_DATA, payload)
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            assertThat(parts[0].typeId).isEqualTo(UmpMessageType.MEDIA_DATA)
            assertThat(parts[0].payload).isEqualTo(payload)

            val event = messageDecoder.decode(parts[0])
            assertThat(event).isInstanceOf(SabrEvent.MediaData::class.java)
            val md = event as SabrEvent.MediaData
            assertThat(md.formatId).isEqualTo(251)
            assertThat(md.offsetBytes).isEqualTo(0)
            assertThat(md.data).isEqualTo(mediaBytes)
        }

        @Test
        @DisplayName("MEDIA_DATA with non-zero offset preserves byte position")
        fun mediaDataWithOffset() {
            val mediaBytes = ByteArray(32) { it.toByte() }
            val payload = ByteArrayOutputStream().apply {
                write(protoVarintField(1, 251))
                write(protoVarintField(2, 524288))  // offset = 512KB
                write(protoBytesField(3, mediaBytes))
            }.toByteArray()

            val frame = buildUmpFrame(UmpMessageType.MEDIA_DATA, payload)
            val parts = frameDecoder.feed(frame)
            val event = messageDecoder.decode(parts[0]) as SabrEvent.MediaData

            assertThat(event.offsetBytes).isEqualTo(524288)
            assertThat(event.data.size).isEqualTo(32)
        }

        @Test
        @DisplayName("FORMAT_INIT (type 42) produces FormatInit with init segment")
        fun formatInitParsesCorrectly() {
            val initSegment = ByteArray(32) { (0x1A + it).toByte() } // fake WebM header bytes
            val payload = ByteArrayOutputStream().apply {
                write(protoVarintField(1, 251))        // formatId = 251
                write(protoBytesField(2, initSegment)) // initSegment
            }.toByteArray()

            val frame = buildUmpFrame(UmpMessageType.FORMAT_INIT, payload)
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            val event = messageDecoder.decode(parts[0]) as SabrEvent.FormatInit
            assertThat(event.formatId).isEqualTo(251)
            assertThat(event.initSegment).isEqualTo(initSegment)
        }

        @Test
        @DisplayName("MEDIA_END (type 22) produces MediaEnd with correct formatId")
        fun mediaEndParsesCorrectly() {
            val payload = protoVarintField(1, 251)
            val frame = buildUmpFrame(UmpMessageType.MEDIA_END, payload)
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            val event = messageDecoder.decode(parts[0]) as SabrEvent.MediaEnd
            assertThat(event.formatId).isEqualTo(251)
        }

        @Test
        @DisplayName("NEXT_REQUEST_POLICY (type 43) extracts cookie, backoff, and buffer target")
        fun nextRequestPolicyParsesCorrectly() {
            val cookie = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
            val payload = ByteArrayOutputStream().apply {
                write(protoBytesField(1, cookie))       // playbackCookie
                write(protoVarintField(2, 1000))        // backoffMs
                write(protoVarintField(3, 20000))       // targetBufferDurationMs
            }.toByteArray()

            val frame = buildUmpFrame(UmpMessageType.NEXT_REQUEST_POLICY, payload)
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            val event = messageDecoder.decode(parts[0]) as SabrEvent.NextRequestPolicy
            assertThat(event.playbackCookie).isNotNull()
            assertThat(event.playbackCookie!!.size).isEqualTo(8)
            assertThat(event.backoffMs).isEqualTo(1000)
            assertThat(event.targetBufferDurationMs).isEqualTo(20000)
        }

        @Test
        @DisplayName("RELOAD (type 44) extracts reason string")
        fun reloadParsesCorrectly() {
            val reason = "cdn_rotation".toByteArray(Charsets.UTF_8)
            val frame = buildUmpFrame(UmpMessageType.RELOAD, reason)
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            val event = messageDecoder.decode(parts[0]) as SabrEvent.ReloadRequired
            assertThat(event.reason).isEqualTo("cdn_rotation")
        }

        @Test
        @DisplayName("REDIRECT (type 45) extracts new endpoint URL")
        fun redirectParsesCorrectly() {
            val endpoint = "https://rr2---sn-abc123.googlevideo.com/videoplayback"
            val payload = endpoint.toByteArray(Charsets.UTF_8)
            val frame = buildUmpFrame(UmpMessageType.REDIRECT, payload)
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            val event = messageDecoder.decode(parts[0]) as SabrEvent.ServerRedirect
            assertThat(event.newEndpoint).isEqualTo(endpoint)
        }

        @Test
        @DisplayName("STREAM_ERROR (type 46) extracts code and message")
        fun streamErrorParsesCorrectly() {
            val message = "forbidden".toByteArray(Charsets.UTF_8)
            val payload = ByteArrayOutputStream().apply {
                write(protoVarintField(1, 403))        // code
                write(protoBytesField(2, message))     // message
            }.toByteArray()

            val frame = buildUmpFrame(UmpMessageType.STREAM_ERROR, payload)
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            val event = messageDecoder.decode(parts[0]) as SabrEvent.StreamError
            assertThat(event.code).isEqualTo(403)
            assertThat(event.message).isEqualTo("forbidden")
        }

        @Test
        @DisplayName("Unknown type ID produces UnknownPart event (no crash)")
        fun unknownTypeProducesUnknownPart() {
            val payload = ByteArray(10) { 0xFF.toByte() }
            val frame = buildUmpFrame(99, payload)
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            val event = messageDecoder.decode(parts[0])
            assertThat(event).isInstanceOf(SabrEvent.UnknownPart::class.java)
            val up = event as SabrEvent.UnknownPart
            assertThat(up.typeId).isEqualTo(99)
            assertThat(up.payloadSize).isEqualTo(10)
        }

        @Test
        @DisplayName("MEDIA_HEADER (type 20) maps to UnknownPart (informational, skipped)")
        fun mediaHeaderMapsToUnknownPart() {
            val frame = buildUmpFrame(UmpMessageType.MEDIA_HEADER, ByteArray(0))
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            assertThat(parts[0].typeId).isEqualTo(UmpMessageType.MEDIA_HEADER)
            val event = messageDecoder.decode(parts[0])
            assertThat(event).isInstanceOf(SabrEvent.UnknownPart::class.java)
        }

        @Test
        @DisplayName("Zero-length payload produces valid part")
        fun zeroLengthPayload() {
            val frame = buildUmpFrame(UmpMessageType.MEDIA_END, ByteArray(0))
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            assertThat(parts[0].payload.size).isEqualTo(0)
            // MediaEnd with empty payload uses fallback formatId=0
            val event = messageDecoder.decode(parts[0]) as SabrEvent.MediaEnd
            assertThat(event.formatId).isEqualTo(0)
        }
    }

    // ── Multi-Part Stream Parsing ───────────────────────────────────────────

    @Nested
    @DisplayName("Multi-Part Stream Parsing")
    inner class MultiPartStream {

        @Test
        @DisplayName("5-part stream (FormatInit→MediaData→MediaData→NextRequestPolicy→MediaEnd) in single feed()")
        fun multiPartStreamSingleFeed() {
            val initSegment = ByteArray(32) { it.toByte() }
            val mediaChunk1 = ByteArray(64) { (it + 0x10).toByte() }
            val mediaChunk2 = ByteArray(48) { (it + 0x20).toByte() }
            val cookie = byteArrayOf(0xCA.toByte(), 0xFE.toByte())

            val stream = ByteArrayOutputStream().apply {
                // Part 1: FORMAT_INIT
                write(buildUmpFrame(UmpMessageType.FORMAT_INIT, ByteArrayOutputStream().apply {
                    write(protoVarintField(1, 251))
                    write(protoBytesField(2, initSegment))
                }.toByteArray()))
                // Part 2: MEDIA_DATA (offset=0)
                write(buildUmpFrame(UmpMessageType.MEDIA_DATA, ByteArrayOutputStream().apply {
                    write(protoVarintField(1, 251))
                    write(protoVarintField(2, 0))
                    write(protoBytesField(3, mediaChunk1))
                }.toByteArray()))
                // Part 3: MEDIA_DATA (offset=64)
                write(buildUmpFrame(UmpMessageType.MEDIA_DATA, ByteArrayOutputStream().apply {
                    write(protoVarintField(1, 251))
                    write(protoVarintField(2, 64))
                    write(protoBytesField(3, mediaChunk2))
                }.toByteArray()))
                // Part 4: NEXT_REQUEST_POLICY
                write(buildUmpFrame(UmpMessageType.NEXT_REQUEST_POLICY, ByteArrayOutputStream().apply {
                    write(protoBytesField(1, cookie))
                    write(protoVarintField(2, 500))
                    write(protoVarintField(3, 30000))
                }.toByteArray()))
                // Part 5: MEDIA_END
                write(buildUmpFrame(UmpMessageType.MEDIA_END, protoVarintField(1, 251)))
            }.toByteArray()

            val parts = frameDecoder.feed(stream)
            assertThat(parts).hasSize(5)

            // Decode all events
            val events = parts.map { messageDecoder.decode(it) }

            assertThat(events[0]).isInstanceOf(SabrEvent.FormatInit::class.java)
            assertThat(events[1]).isInstanceOf(SabrEvent.MediaData::class.java)
            assertThat(events[2]).isInstanceOf(SabrEvent.MediaData::class.java)
            assertThat(events[3]).isInstanceOf(SabrEvent.NextRequestPolicy::class.java)
            assertThat(events[4]).isInstanceOf(SabrEvent.MediaEnd::class.java)

            // Verify offsets are sequential
            val md1 = events[1] as SabrEvent.MediaData
            val md2 = events[2] as SabrEvent.MediaData
            assertThat(md1.offsetBytes).isEqualTo(0)
            assertThat(md2.offsetBytes).isEqualTo(64)

            // Verify cookie is round-tripped
            val nrp = events[3] as SabrEvent.NextRequestPolicy
            assertThat(nrp.playbackCookie).isNotNull()
            assertThat(nrp.backoffMs).isEqualTo(500)
        }

        @Test
        @DisplayName("Interleaved known + unknown types are handled without data loss")
        fun interleavedKnownAndUnknownTypes() {
            val stream = ByteArrayOutputStream().apply {
                write(buildUmpFrame(UmpMessageType.FORMAT_INIT, ByteArrayOutputStream().apply {
                    write(protoVarintField(1, 140))
                    write(protoBytesField(2, ByteArray(8)))
                }.toByteArray()))
                write(buildUmpFrame(99, ByteArray(5)))   // Unknown type
                write(buildUmpFrame(UmpMessageType.MEDIA_DATA, ByteArrayOutputStream().apply {
                    write(protoVarintField(1, 140))
                    write(protoVarintField(2, 0))
                    write(protoBytesField(3, ByteArray(32)))
                }.toByteArray()))
            }.toByteArray()

            val parts = frameDecoder.feed(stream)
            assertThat(parts).hasSize(3)

            val events = parts.map { messageDecoder.decode(it) }
            assertThat(events[0]).isInstanceOf(SabrEvent.FormatInit::class.java)
            assertThat(events[1]).isInstanceOf(SabrEvent.UnknownPart::class.java)
            assertThat(events[2]).isInstanceOf(SabrEvent.MediaData::class.java)
        }
    }

    // ── Chunked/Partial Delivery ────────────────────────────────────────────

    @Nested
    @DisplayName("Chunked/Partial Delivery")
    inner class ChunkedDelivery {

        @Test
        @DisplayName("Frame split mid-varint reassembles correctly")
        fun frameSplitMidVarint() {
            val payload = ByteArray(200) { it.toByte() }
            val frame = buildUmpFrame(UmpMessageType.MEDIA_DATA, payload)

            // Split at byte 1 — right in the middle of the size varint
            val chunk1 = frame.copyOfRange(0, 1)
            val chunk2 = frame.copyOfRange(1, frame.size)

            val parts1 = frameDecoder.feed(chunk1)
            assertThat(parts1).isEmpty()
            assertThat(frameDecoder.hasPartialState()).isTrue()

            val parts2 = frameDecoder.feed(chunk2)
            assertThat(parts2).hasSize(1)
            assertThat(parts2[0].typeId).isEqualTo(UmpMessageType.MEDIA_DATA)
            assertThat(parts2[0].payload).isEqualTo(payload)
        }

        @Test
        @DisplayName("Frame split mid-payload reassembles correctly")
        fun frameSplitMidPayload() {
            val payload = ByteArray(100) { (it xor 0x55).toByte() }
            val frame = buildUmpFrame(UmpMessageType.FORMAT_INIT, payload)

            // Split at offset that's inside the payload
            val splitPoint = frame.size / 2
            val chunk1 = frame.copyOfRange(0, splitPoint)
            val chunk2 = frame.copyOfRange(splitPoint, frame.size)

            val parts1 = frameDecoder.feed(chunk1)
            assertThat(parts1).isEmpty()

            val parts2 = frameDecoder.feed(chunk2)
            assertThat(parts2).hasSize(1)
            assertThat(parts2[0].typeId).isEqualTo(UmpMessageType.FORMAT_INIT)
            assertThat(parts2[0].payload).isEqualTo(payload)
        }

        @Test
        @DisplayName("Byte-at-a-time delivery still produces correct result")
        fun byteAtATimeDelivery() {
            val payload = ByteArray(20) { (it + 1).toByte() }
            val frame = buildUmpFrame(UmpMessageType.MEDIA_END, payload)

            val allParts = mutableListOf<UmpPart>()
            for (b in frame) {
                allParts.addAll(frameDecoder.feed(byteArrayOf(b)))
            }

            assertThat(allParts).hasSize(1)
            assertThat(allParts[0].typeId).isEqualTo(UmpMessageType.MEDIA_END)
            assertThat(allParts[0].payload).isEqualTo(payload)
        }

        @Test
        @DisplayName("Multi-part stream split across chunks reassembles all parts")
        fun multiPartChunkedDelivery() {
            val part1 = buildUmpFrame(UmpMessageType.FORMAT_INIT,
                protoVarintField(1, 251))
            val part2 = buildUmpFrame(UmpMessageType.MEDIA_DATA,
                ByteArrayOutputStream().apply {
                    write(protoVarintField(1, 251))
                    write(protoBytesField(3, ByteArray(16)))
                }.toByteArray())

            val fullStream = part1 + part2
            // Split right at the boundary between parts
            val chunk1 = fullStream.copyOfRange(0, part1.size + 2)
            val chunk2 = fullStream.copyOfRange(part1.size + 2, fullStream.size)

            val allParts = mutableListOf<UmpPart>()
            allParts.addAll(frameDecoder.feed(chunk1))
            allParts.addAll(frameDecoder.feed(chunk2))

            assertThat(allParts).hasSize(2)
            assertThat(allParts[0].typeId).isEqualTo(UmpMessageType.FORMAT_INIT)
            assertThat(allParts[1].typeId).isEqualTo(UmpMessageType.MEDIA_DATA)
        }
    }

    // ── Varint Edge Cases ───────────────────────────────────────────────────

    @Nested
    @DisplayName("UMP Varint Edge Cases")
    inner class VarintEdgeCases {

        @Test
        @DisplayName("1-byte varint (0–127) decodes correctly")
        fun oneByteVarint() {
            val result = UmpFrameDecoder.decodeVarint(byteArrayOf(0x15), 0)
            assertThat(result).isNotNull()
            assertThat(result!!.first).isEqualTo(21) // MEDIA_DATA
            assertThat(result.second).isEqualTo(1)
        }

        @Test
        @DisplayName("2-byte varint (128–16383) decodes correctly")
        fun twoByteVarint() {
            // Encode 200: 10_000000 11001000 → 0x80|0=0x80, 0xC8
            val result = UmpFrameDecoder.decodeVarint(byteArrayOf(0x80.toByte(), 0xC8.toByte()), 0)
            assertThat(result).isNotNull()
            assertThat(result!!.first).isEqualTo(200)
            assertThat(result.second).isEqualTo(2)
        }

        @Test
        @DisplayName("Maximum 1-byte varint (127) decodes correctly")
        fun maxOneByteVarint() {
            val result = UmpFrameDecoder.decodeVarint(byteArrayOf(0x7F), 0)
            assertThat(result).isNotNull()
            assertThat(result!!.first).isEqualTo(127)
        }

        @Test
        @DisplayName("Zero varint decodes correctly")
        fun zeroVarint() {
            val result = UmpFrameDecoder.decodeVarint(byteArrayOf(0x00), 0)
            assertThat(result).isNotNull()
            assertThat(result!!.first).isEqualTo(0)
        }

        @Test
        @DisplayName("Varint round-trip: encode then decode preserves value")
        fun varintRoundTrip() {
            val testValues = listOf(0, 1, 21, 42, 43, 127, 128, 255, 1000, 16383, 16384, 65535, 100000)
            for (v in testValues) {
                val encoded = encodeUmpVarint(v)
                val decoded = UmpFrameDecoder.decodeVarint(encoded, 0)
                assertThat(decoded).isNotNull()
                assertThat(decoded!!.first).isEqualTo(v)
                assertThat(decoded.second).isEqualTo(encoded.size)
            }
        }

        @Test
        @DisplayName("Insufficient bytes for 2-byte varint returns null (not exception)")
        fun insufficientBytesReturnsNull() {
            val result = UmpFrameDecoder.decodeVarint(byteArrayOf(0x80.toByte()), 0, 1)
            assertThat(result).isNull()
        }
    }

    // ── Error Handling / Robustness ─────────────────────────────────────────

    @Nested
    @DisplayName("Error Handling and Robustness")
    inner class ErrorHandling {

        @Test
        @DisplayName("Oversized payload throws IOException")
        fun oversizedPayloadThrows() {
            // Build a frame claiming 1MB payload (exceeds default 512KB max)
            val typeVarint = encodeUmpVarint(UmpMessageType.MEDIA_DATA)
            val sizeVarint = encodeUmpVarint(1024 * 1024)
            val fakeData = ByteArray(10) // Not the full 1MB, but decoder checks size first

            val frame = typeVarint + sizeVarint + fakeData
            assertThrows<IOException> { frameDecoder.feed(frame) }
        }

        @Test
        @DisplayName("Decoder reset clears all partial state")
        fun resetClearsPartialState() {
            val payload = ByteArray(50)
            val frame = buildUmpFrame(UmpMessageType.MEDIA_DATA, payload)

            // Feed partial data
            frameDecoder.feed(frame.copyOfRange(0, 3))
            assertThat(frameDecoder.hasPartialState()).isTrue()

            // Reset
            frameDecoder.reset()
            assertThat(frameDecoder.hasPartialState()).isFalse()

            // Feed a complete new frame — should work cleanly
            val newPayload = ByteArray(10) { 0xBB.toByte() }
            val newFrame = buildUmpFrame(UmpMessageType.MEDIA_END, newPayload)
            val parts = frameDecoder.feed(newFrame)
            assertThat(parts).hasSize(1)
            assertThat(parts[0].typeId).isEqualTo(UmpMessageType.MEDIA_END)
        }

        @Test
        @DisplayName("Malformed protobuf in MediaData produces fallback event (not crash)")
        fun malformedProtobufFallback() {
            // Feed garbage bytes as MEDIA_DATA payload — decoder should fallback gracefully
            val garbagePayload = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            val frame = buildUmpFrame(UmpMessageType.MEDIA_DATA, garbagePayload)
            val parts = frameDecoder.feed(frame)

            assertThat(parts).hasSize(1)
            // Should not crash — produces fallback MediaData with raw payload
            val event = messageDecoder.decode(parts[0])
            assertThat(event).isInstanceOf(SabrEvent.MediaData::class.java)
            val md = event as SabrEvent.MediaData
            // Fallback: formatId=0, data=raw payload, offset=0
            assertThat(md.formatId).isEqualTo(0)
        }

        @Test
        @DisplayName("Empty feed returns empty list (no exception)")
        fun emptyFeedReturnsEmpty() {
            val parts = frameDecoder.feed(ByteArray(0))
            assertThat(parts).isEmpty()
        }
    }
}
