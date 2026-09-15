package com.aurora.engine.transport.sabr.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A single reassembled UMP (Universal Media Protocol) frame/part.
 *
 * @property typeId UMP message type identifier (see [UmpMessageType]).
 * @property payload The complete payload bytes for this part.
 */
data class UmpPart(val typeId: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UmpPart) return false
        return typeId == other.typeId && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * typeId + payload.contentHashCode()
}

/**
 * Stateful UMP frame decoder.
 *
 * Parses raw bytes from HTTP response bodies into [UmpPart] records. This component
 * is **stateful** because UMP parts can span multiple HTTP response chunks.
 *
 * ## UMP Binary Format
 *
 * Each UMP part consists of:
 * 1. **Type varint**: Identifies the message type.
 * 2. **Size varint**: Byte length of the payload.
 * 3. **Payload**: Raw bytes (may be protobuf, media data, etc.).
 *
 * ## UMP Varint Encoding
 *
 * UMP uses a prefix-coded variable-length integer:
 * - `0xxxxxxx` (1 byte): 7-bit value (0–127)
 * - `10xxxxxx yyyyyyyy` (2 bytes): 14-bit value
 * - `110xxxxx ...` (3 bytes): 21-bit value
 * - `1110xxxx ...` (4 bytes): 28-bit value
 * - `11110xxx + 4 raw LE bytes` (5 bytes): raw little-endian u32
 *
 * @param maxPartSize Maximum allowed payload size to prevent malformed stream DoS.
 * @param maxReassemblySize Maximum reassembly buffer size for partial parts.
 */
class UmpFrameDecoder(
    private val maxPartSize: Int = 512 * 1024,
    private val maxReassemblySize: Int = 256 * 1024
) {
    // Parsing state machine phases
    private enum class Phase {
        /** Awaiting type varint bytes. */
        READING_TYPE,
        /** Awaiting size varint bytes. */
        READING_SIZE,
        /** Awaiting payload bytes. */
        READING_PAYLOAD
    }

    private var phase: Phase = Phase.READING_TYPE

    // Varint accumulation (up to 5 bytes)
    private var varintBuffer = ByteArray(5)
    private var varintBufferLen = 0

    // Decoded type and size for the current part
    private var currentType: Int = 0
    private var currentSize: Int = 0

    // Payload reassembly
    private var payloadBuffer: ByteArrayOutputStream? = null
    private var payloadBytesRemaining: Int = 0

    /**
     * Feed raw bytes from an HTTP response body.
     *
     * @param data Raw bytes to process.
     * @return List of fully reassembled [UmpPart] records. May return 0, 1, or many parts.
     * @throws IOException if the stream contains structurally invalid data.
     */
    fun feed(data: ByteArray): List<UmpPart> {
        val results = mutableListOf<UmpPart>()
        var offset = 0

        while (offset < data.size) {
            when (phase) {
                Phase.READING_TYPE -> {
                    offset = consumeVarintByte(data, offset)
                    val decoded = tryDecodeVarint()
                    if (decoded != null) {
                        currentType = decoded
                        varintBufferLen = 0
                        phase = Phase.READING_SIZE
                    }
                }

                Phase.READING_SIZE -> {
                    offset = consumeVarintByte(data, offset)
                    val decoded = tryDecodeVarint()
                    if (decoded != null) {
                        currentSize = decoded
                        varintBufferLen = 0

                        if (currentSize < 0) {
                            throw IOException("UMP: negative payload size $currentSize for type $currentType")
                        }
                        if (currentSize > maxPartSize) {
                            throw IOException(
                                "UMP: payload size $currentSize exceeds max $maxPartSize " +
                                    "for type ${UmpMessageType.nameOf(currentType)}"
                            )
                        }

                        if (currentSize == 0) {
                            // Zero-length payload — emit immediately
                            results.add(UmpPart(currentType, ByteArray(0)))
                            phase = Phase.READING_TYPE
                        } else {
                            payloadBuffer = ByteArrayOutputStream(minOf(currentSize, 8192))
                            payloadBytesRemaining = currentSize
                            phase = Phase.READING_PAYLOAD
                        }
                    }
                }

                Phase.READING_PAYLOAD -> {
                    val available = data.size - offset
                    val toRead = minOf(available, payloadBytesRemaining)
                    val buf = payloadBuffer
                        ?: throw IllegalStateException("UMP: payload buffer is null in READING_PAYLOAD phase")

                    if (buf.size() + toRead > maxReassemblySize) {
                        throw IOException(
                            "UMP: reassembly buffer would exceed max $maxReassemblySize " +
                                "for type ${UmpMessageType.nameOf(currentType)}"
                        )
                    }

                    buf.write(data, offset, toRead)
                    offset += toRead
                    payloadBytesRemaining -= toRead

                    if (payloadBytesRemaining == 0) {
                        results.add(UmpPart(currentType, buf.toByteArray()))
                        payloadBuffer = null
                        phase = Phase.READING_TYPE
                    }
                }
            }
        }

        return results
    }

    /**
     * Reset the decoder state. Call on session invalidation, seek, or error.
     */
    fun reset() {
        phase = Phase.READING_TYPE
        varintBufferLen = 0
        currentType = 0
        currentSize = 0
        payloadBuffer = null
        payloadBytesRemaining = 0
    }

    /**
     * Returns true if the decoder has partial (incomplete) state.
     * Useful for diagnostics.
     */
    fun hasPartialState(): Boolean =
        phase != Phase.READING_TYPE || varintBufferLen > 0

    // --- Private helpers ---

    /**
     * Consume one byte from data into the varint accumulation buffer.
     * Returns the new offset (offset + 1).
     */
    private fun consumeVarintByte(data: ByteArray, offset: Int): Int {
        if (varintBufferLen >= varintBuffer.size) {
            throw IOException("UMP: varint exceeds maximum 5 bytes")
        }
        varintBuffer[varintBufferLen++] = data[offset]
        return offset + 1
    }

    /**
     * Attempt to decode a complete varint from the accumulated bytes.
     * Returns the decoded integer value, or null if more bytes are needed.
     */
    private fun tryDecodeVarint(): Int? {
        if (varintBufferLen == 0) return null
        return decodeVarint(varintBuffer, 0, varintBufferLen)?.first
    }

    companion object {
        /**
         * Decode a UMP varint from the given byte array starting at [offset].
         *
         * @param data The byte array containing varint bytes.
         * @param offset Starting position in the array.
         * @param available Number of bytes available from offset.
         * @return `Pair(value, bytesConsumed)` or `null` if insufficient bytes.
         *
         * UMP varint encoding:
         * - `0xxxxxxx` (1 byte): 7-bit value
         * - `10xxxxxx yyyyyyyy` (2 bytes): 14-bit value
         * - `110xxxxx yyyyyyyy zzzzzzzz` (3 bytes): 21-bit value
         * - `1110xxxx ...` (4 bytes): 28-bit value
         * - `11110xxx + 4 raw LE bytes` (5 bytes): raw little-endian u32
         */
        fun decodeVarint(data: ByteArray, offset: Int, available: Int = data.size - offset): Pair<Int, Int>? {
            if (available <= 0) return null

            val first = data[offset].toInt() and 0xFF

            return when {
                // 1 byte: 0xxxxxxx
                first and 0x80 == 0 -> {
                    Pair(first, 1)
                }
                // 2 bytes: 10xxxxxx
                first and 0xC0 == 0x80 -> {
                    if (available < 2) return null
                    val value = ((first and 0x3F) shl 8) or
                        (data[offset + 1].toInt() and 0xFF)
                    Pair(value, 2)
                }
                // 3 bytes: 110xxxxx
                first and 0xE0 == 0xC0 -> {
                    if (available < 3) return null
                    val value = ((first and 0x1F) shl 16) or
                        ((data[offset + 1].toInt() and 0xFF) shl 8) or
                        (data[offset + 2].toInt() and 0xFF)
                    Pair(value, 3)
                }
                // 4 bytes: 1110xxxx
                first and 0xF0 == 0xE0 -> {
                    if (available < 4) return null
                    val value = ((first and 0x0F) shl 24) or
                        ((data[offset + 1].toInt() and 0xFF) shl 16) or
                        ((data[offset + 2].toInt() and 0xFF) shl 8) or
                        (data[offset + 3].toInt() and 0xFF)
                    Pair(value, 4)
                }
                // 5 bytes: 11110xxx + 4 raw LE bytes
                first and 0xF8 == 0xF0 -> {
                    if (available < 5) return null
                    // Lower 3 bits of prefix are ignored; next 4 bytes are raw little-endian u32
                    val value = (data[offset + 1].toInt() and 0xFF) or
                        ((data[offset + 2].toInt() and 0xFF) shl 8) or
                        ((data[offset + 3].toInt() and 0xFF) shl 16) or
                        ((data[offset + 4].toInt() and 0xFF) shl 24)
                    Pair(value, 5)
                }
                else -> {
                    throw IOException("UMP: invalid varint prefix byte 0x${first.toString(16)}")
                }
            }
        }
    }
}
