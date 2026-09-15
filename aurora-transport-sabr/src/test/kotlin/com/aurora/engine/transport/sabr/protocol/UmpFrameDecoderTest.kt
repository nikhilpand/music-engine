package com.aurora.engine.transport.sabr.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class UmpFrameDecoderTest {

    private lateinit var decoder: UmpFrameDecoder

    @BeforeEach
    fun setUp() {
        decoder = UmpFrameDecoder()
    }

    // --- Varint decoding tests ---

    @Test
    fun `decodeVarint - 1 byte value`() {
        // 0xxxxxxx: value = 42 = 0x2A
        val data = byteArrayOf(0x2A)
        val result = UmpFrameDecoder.decodeVarint(data, 0)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo(42)
        assertThat(result.second).isEqualTo(1)
    }

    @Test
    fun `decodeVarint - 1 byte zero`() {
        val data = byteArrayOf(0x00)
        val result = UmpFrameDecoder.decodeVarint(data, 0)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo(0)
        assertThat(result.second).isEqualTo(1)
    }

    @Test
    fun `decodeVarint - 1 byte max value 127`() {
        val data = byteArrayOf(0x7F)
        val result = UmpFrameDecoder.decodeVarint(data, 0)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo(127)
        assertThat(result.second).isEqualTo(1)
    }

    @Test
    fun `decodeVarint - 2 byte value`() {
        // 10xxxxxx yyyyyyyy: value = (0x01 shl 8) | 0x00 = 256
        val data = byteArrayOf(0x81.toByte(), 0x00)
        val result = UmpFrameDecoder.decodeVarint(data, 0)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo(256)
        assertThat(result.second).isEqualTo(2)
    }

    @Test
    fun `decodeVarint - 2 byte max value`() {
        // 10111111 11111111 = (0x3F shl 8) | 0xFF = 16383
        val data = byteArrayOf(0xBF.toByte(), 0xFF.toByte())
        val result = UmpFrameDecoder.decodeVarint(data, 0)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo(16383)
        assertThat(result.second).isEqualTo(2)
    }

    @Test
    fun `decodeVarint - 3 byte value`() {
        // 110xxxxx yyyyyyyy zzzzzzzz
        // 11000001 00000000 00000000 = (1 shl 16) = 65536
        val data = byteArrayOf(0xC1.toByte(), 0x00, 0x00)
        val result = UmpFrameDecoder.decodeVarint(data, 0)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo(65536)
        assertThat(result.second).isEqualTo(3)
    }

    @Test
    fun `decodeVarint - 4 byte value`() {
        // 1110xxxx + 3 more bytes
        // 11100001 00000000 00000000 00000000 = (1 shl 24) = 16777216
        val data = byteArrayOf(0xE1.toByte(), 0x00, 0x00, 0x00)
        val result = UmpFrameDecoder.decodeVarint(data, 0)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo(16777216)
        assertThat(result.second).isEqualTo(4)
    }

    @Test
    fun `decodeVarint - 5 byte value (raw LE u32)`() {
        // 11110xxx + 4 raw LE bytes
        // prefix = 0xF0, then LE bytes for value 0x04030201 = 67305985
        val data = byteArrayOf(0xF0.toByte(), 0x01, 0x02, 0x03, 0x04)
        val result = UmpFrameDecoder.decodeVarint(data, 0)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo(0x04030201)
        assertThat(result.second).isEqualTo(5)
    }

    @Test
    fun `decodeVarint - insufficient bytes returns null`() {
        // 2-byte prefix but only 1 byte available
        val data = byteArrayOf(0x80.toByte())
        val result = UmpFrameDecoder.decodeVarint(data, 0)
        assertThat(result).isNull()
    }

    @Test
    fun `decodeVarint - with offset`() {
        val data = byteArrayOf(0xFF.toByte(), 0x2A)
        val result = UmpFrameDecoder.decodeVarint(data, 1, 1)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo(42)
    }

    // --- Feed: complete parts ---

    @Test
    fun `feed - single complete part with 1-byte varints`() {
        // type=21 (MEDIA_DATA), size=3, payload=[0x01, 0x02, 0x03]
        val data = byteArrayOf(21, 3, 0x01, 0x02, 0x03)
        val parts = decoder.feed(data)
        assertThat(parts).hasSize(1)
        assertThat(parts[0].typeId).isEqualTo(21)
        assertThat(parts[0].payload).isEqualTo(byteArrayOf(0x01, 0x02, 0x03))
    }

    @Test
    fun `feed - zero length payload`() {
        // type=22 (MEDIA_END), size=0
        val data = byteArrayOf(22, 0)
        val parts = decoder.feed(data)
        assertThat(parts).hasSize(1)
        assertThat(parts[0].typeId).isEqualTo(22)
        assertThat(parts[0].payload).isEmpty()
    }

    @Test
    fun `feed - multiple parts in single chunk`() {
        // Part 1: type=20, size=2, payload=[0xAA, 0xBB]
        // Part 2: type=21, size=1, payload=[0xCC]
        val data = byteArrayOf(20, 2, 0xAA.toByte(), 0xBB.toByte(), 21, 1, 0xCC.toByte())
        val parts = decoder.feed(data)
        assertThat(parts).hasSize(2)
        assertThat(parts[0].typeId).isEqualTo(20)
        assertThat(parts[0].payload).isEqualTo(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        assertThat(parts[1].typeId).isEqualTo(21)
        assertThat(parts[1].payload).isEqualTo(byteArrayOf(0xCC.toByte()))
    }

    // --- Feed: partial parts spanning multiple feeds ---

    @Test
    fun `feed - payload split across two feeds`() {
        // type=21, size=4, payload split: [0x01, 0x02] then [0x03, 0x04]
        val chunk1 = byteArrayOf(21, 4, 0x01, 0x02)
        val chunk2 = byteArrayOf(0x03, 0x04)

        val parts1 = decoder.feed(chunk1)
        assertThat(parts1).isEmpty()
        assertThat(decoder.hasPartialState()).isTrue()

        val parts2 = decoder.feed(chunk2)
        assertThat(parts2).hasSize(1)
        assertThat(parts2[0].typeId).isEqualTo(21)
        assertThat(parts2[0].payload).isEqualTo(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertThat(decoder.hasPartialState()).isFalse()
    }

    @Test
    fun `feed - type varint split across feeds`() {
        // 2-byte type varint: 0x81 0x00 = type 256
        // Feed byte by byte
        val parts1 = decoder.feed(byteArrayOf(0x81.toByte()))
        assertThat(parts1).isEmpty()
        assertThat(decoder.hasPartialState()).isTrue()

        // Complete the type varint, then provide size=0 (zero-payload part)
        val parts2 = decoder.feed(byteArrayOf(0x00, 0x00))
        assertThat(parts2).hasSize(1)
        assertThat(parts2[0].typeId).isEqualTo(256)
        assertThat(parts2[0].payload).isEmpty()
    }

    @Test
    fun `feed - partial part then complete part in next feed`() {
        // Part 1: type=21, size=2, partial payload [0x01]
        val chunk1 = byteArrayOf(21, 2, 0x01)
        val parts1 = decoder.feed(chunk1)
        assertThat(parts1).isEmpty()

        // Complete part 1 payload [0x02], then part 2: type=22, size=0
        val chunk2 = byteArrayOf(0x02, 22, 0)
        val parts2 = decoder.feed(chunk2)
        assertThat(parts2).hasSize(2)
        assertThat(parts2[0].typeId).isEqualTo(21)
        assertThat(parts2[0].payload).isEqualTo(byteArrayOf(0x01, 0x02))
        assertThat(parts2[1].typeId).isEqualTo(22)
    }

    // --- Feed: error cases ---

    @Test
    fun `feed - payload exceeds max part size throws IOException`() {
        val smallDecoder = UmpFrameDecoder(maxPartSize = 10)
        // type=21, size=100 (exceeds max of 10)
        val data = byteArrayOf(21, 100)
        assertThrows<IOException> { smallDecoder.feed(data) }
    }

    @Test
    fun `feed - unknown type ID produces valid part`() {
        // type=99 (unknown), size=1, payload=[0x42]
        val data = byteArrayOf(99, 1, 0x42)
        val parts = decoder.feed(data)
        assertThat(parts).hasSize(1)
        assertThat(parts[0].typeId).isEqualTo(99)
        assertThat(parts[0].payload).isEqualTo(byteArrayOf(0x42))
    }

    // --- Reset ---

    @Test
    fun `reset - clears partial state`() {
        // Start a partial part
        decoder.feed(byteArrayOf(21, 4, 0x01))
        assertThat(decoder.hasPartialState()).isTrue()

        decoder.reset()
        assertThat(decoder.hasPartialState()).isFalse()

        // After reset, can parse fresh data
        val parts = decoder.feed(byteArrayOf(22, 0))
        assertThat(parts).hasSize(1)
        assertThat(parts[0].typeId).isEqualTo(22)
    }

    @Test
    fun `feed - empty input returns empty list`() {
        val parts = decoder.feed(byteArrayOf())
        assertThat(parts).isEmpty()
        assertThat(decoder.hasPartialState()).isFalse()
    }

    // --- Edge cases ---

    @Test
    fun `feed - single byte at a time for complete part`() {
        // type=10, size=2, payload=[0xAA, 0xBB]
        val fullData = byteArrayOf(10, 2, 0xAA.toByte(), 0xBB.toByte())
        var allParts = mutableListOf<UmpPart>()

        for (b in fullData) {
            allParts.addAll(decoder.feed(byteArrayOf(b)))
        }

        assertThat(allParts).hasSize(1)
        assertThat(allParts[0].typeId).isEqualTo(10)
        assertThat(allParts[0].payload).isEqualTo(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
    }

    @Test
    fun `feed - large payload within limits`() {
        val payloadSize = 1024
        val payload = ByteArray(payloadSize) { (it % 256).toByte() }

        // type=21, size encoded as 2-byte varint for 1024
        // 1024 = 0x400, as 2-byte UMP varint: 10_000100 00000000 = 0x84 0x00
        val header = byteArrayOf(21, 0x84.toByte(), 0x00)
        val data = header + payload

        val parts = decoder.feed(data)
        assertThat(parts).hasSize(1)
        assertThat(parts[0].typeId).isEqualTo(21)
        assertThat(parts[0].payload.size).isEqualTo(1024)
        assertThat(parts[0].payload).isEqualTo(payload)
    }
}
