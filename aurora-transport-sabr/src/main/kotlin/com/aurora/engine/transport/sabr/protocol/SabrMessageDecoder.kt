package com.aurora.engine.transport.sabr.protocol

import com.aurora.engine.transport.sabr.model.SabrEvent

/**
 * Converts raw [UmpPart] records into typed [SabrEvent] instances.
 *
 * This sits between the [UmpFrameDecoder] (binary framing) and the session controller
 * (business logic). It is intentionally stateless — all state lives in the session controller.
 *
 * ## Mapping Rules
 *
 * | UMP Type ID | SabrEvent |
 * |---|---|
 * | [UmpMessageType.MEDIA_DATA] | [SabrEvent.MediaData] |
 * | [UmpMessageType.FORMAT_INIT] | [SabrEvent.FormatInit] |
 * | [UmpMessageType.MEDIA_END] | [SabrEvent.MediaEnd] |
 * | [UmpMessageType.NEXT_REQUEST_POLICY] | [SabrEvent.NextRequestPolicy] |
 * | [UmpMessageType.RELOAD] | [SabrEvent.ReloadRequired] |
 * | [UmpMessageType.REDIRECT] | [SabrEvent.ServerRedirect] |
 * | [UmpMessageType.STREAM_ERROR] | [SabrEvent.StreamError] |
 * | (other) | [SabrEvent.UnknownPart] |
 *
 * Payloads that would normally be protobuf-encoded are parsed with minimal inline
 * extraction (no generated protobuf classes, to keep the dependency footprint minimal).
 */
class SabrMessageDecoder {

    /**
     * Decode a single [UmpPart] into a [SabrEvent].
     *
     * Never throws for valid UMP framing — unknown types produce [SabrEvent.UnknownPart].
     * Malformed payloads for known types will produce fallback events with safe defaults.
     */
    fun decode(part: UmpPart): SabrEvent {
        return when (part.typeId) {
            UmpMessageType.MEDIA_DATA -> decodeMediaData(part.payload)
            UmpMessageType.FORMAT_INIT -> decodeFormatInit(part.payload)
            UmpMessageType.MEDIA_END -> decodeMediaEnd(part.payload)
            UmpMessageType.NEXT_REQUEST_POLICY -> decodeNextRequestPolicy(part.payload)
            UmpMessageType.RELOAD -> decodeReload(part.payload)
            UmpMessageType.REDIRECT -> decodeRedirect(part.payload)
            UmpMessageType.STREAM_ERROR -> decodeStreamError(part.payload)
            UmpMessageType.ONESIE_HEADER,
            UmpMessageType.MEDIA_HEADER -> {
                // Headers are informational; map to unknown so they are logged and skipped
                SabrEvent.UnknownPart(part.typeId, part.payload.size)
            }
            else -> SabrEvent.UnknownPart(part.typeId, part.payload.size)
        }
    }

    // --- Private decoders ---

    /**
     * Decode MEDIA_DATA payload.
     *
     * Minimal protobuf field extraction:
     * - field 1 (varint): formatId
     * - field 2 (varint): offsetBytes
     * - field 3 (length-delimited): media bytes
     *
     * If parsing fails, returns a zero-offset MediaData with the raw payload.
     */
    private fun decodeMediaData(payload: ByteArray): SabrEvent.MediaData {
        return try {
            val fields = extractProtobufFields(payload)

            val formatId = fields.varint(1)?.toInt() ?: 0
            val offset = fields.varint(2) ?: 0L
            val data = fields.bytes(3) ?: payload

            SabrEvent.MediaData(
                formatId = formatId,
                data = data.copyOf(),
                offsetBytes = offset
            )
        } catch (_: Exception) {
            // Fallback: treat entire payload as media data for format 0
            SabrEvent.MediaData(formatId = 0, data = payload.copyOf(), offsetBytes = 0)
        }
    }

    private fun decodeFormatInit(payload: ByteArray): SabrEvent.FormatInit {
        return try {
            val fields = extractProtobufFields(payload)
            val formatId = fields.varint(1)?.toInt() ?: 0
            val initSegment = fields.bytes(2) ?: payload

            SabrEvent.FormatInit(
                formatId = formatId,
                initSegment = initSegment.copyOf()
            )
        } catch (_: Exception) {
            SabrEvent.FormatInit(formatId = 0, initSegment = payload.copyOf())
        }
    }

    private fun decodeMediaEnd(payload: ByteArray): SabrEvent.MediaEnd {
        return try {
            val fields = extractProtobufFields(payload)
            val formatId = fields.varint(1)?.toInt() ?: 0
            SabrEvent.MediaEnd(formatId = formatId)
        } catch (_: Exception) {
            SabrEvent.MediaEnd(formatId = 0)
        }
    }

    private fun decodeNextRequestPolicy(payload: ByteArray): SabrEvent.NextRequestPolicy {
        return try {
            val fields = extractProtobufFields(payload)
            val cookie = fields.bytes(1)
            val backoff = fields.varint(2)
            val targetBuffer = fields.varint(3)

            SabrEvent.NextRequestPolicy(
                playbackCookie = cookie?.copyOf(),
                backoffMs = backoff,
                targetBufferDurationMs = targetBuffer
            )
        } catch (_: Exception) {
            SabrEvent.NextRequestPolicy(
                playbackCookie = null,
                backoffMs = null,
                targetBufferDurationMs = null
            )
        }
    }

    private fun decodeReload(payload: ByteArray): SabrEvent.ReloadRequired {
        return try {
            val reason = if (payload.isNotEmpty()) {
                String(payload, Charsets.UTF_8).take(256)
            } else {
                "server_requested_reload"
            }
            SabrEvent.ReloadRequired(reason = reason)
        } catch (_: Exception) {
            SabrEvent.ReloadRequired(reason = "server_requested_reload")
        }
    }

    private fun decodeRedirect(payload: ByteArray): SabrEvent.ServerRedirect {
        return try {
            val endpoint = String(payload, Charsets.UTF_8).take(2048)
            SabrEvent.ServerRedirect(newEndpoint = endpoint)
        } catch (_: Exception) {
            SabrEvent.ServerRedirect(newEndpoint = "")
        }
    }

    private fun decodeStreamError(payload: ByteArray): SabrEvent.StreamError {
        return try {
            val fields = extractProtobufFields(payload)
            val code = fields.varint(1)?.toInt() ?: -1
            val message = fields.bytes(2)?.let { String(it, Charsets.UTF_8) } ?: "unknown_error"
            SabrEvent.StreamError(code = code, message = message.take(512))
        } catch (_: Exception) {
            SabrEvent.StreamError(code = -1, message = "unknown_error")
        }
    }

    // --- Minimal protobuf field extractor ---

    /**
     * Extract fields from a minimal protobuf payload.
     *
     * This is a simplified, non-generated protobuf parser that handles:
     * - Varint fields (wire type 0)
     * - Length-delimited fields (wire type 2)
     * - Fixed32/Fixed64 (wire types 1, 5) — skipped
     *
     * **This is NOT a full protobuf parser.** It handles the subset of field types
     * used in SABR UMP payloads. For complex payloads, consider generated protos.
     */
    private fun extractProtobufFields(data: ByteArray): ProtobufFields {
        val varints = mutableMapOf<Int, Long>()
        val bytesFields = mutableMapOf<Int, ByteArray>()
        var offset = 0

        while (offset < data.size) {
            // Read field tag
            val tagResult = readProtobufVarint(data, offset) ?: break
            val tag = tagResult.first
            offset += tagResult.second

            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            when (wireType) {
                0 -> { // Varint
                    val valueResult = readProtobufVarint(data, offset) ?: break
                    varints[fieldNumber] = valueResult.first
                    offset += valueResult.second
                }
                2 -> { // Length-delimited
                    val lenResult = readProtobufVarint(data, offset) ?: break
                    val len = lenResult.first.toInt()
                    offset += lenResult.second
                    if (offset + len > data.size) break
                    bytesFields[fieldNumber] = data.copyOfRange(offset, offset + len)
                    offset += len
                }
                1 -> { // Fixed64 - skip 8 bytes
                    offset += 8
                }
                5 -> { // Fixed32 - skip 4 bytes
                    offset += 4
                }
                else -> break // Unknown wire type, stop parsing
            }
        }

        return ProtobufFields(varints, bytesFields)
    }

    /**
     * Read a standard protobuf varint (base-128, MSB continuation bit).
     */
    private fun readProtobufVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = offset

        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7FL) shl shift)
            pos++
            if (b and 0x80 == 0) {
                return Pair(result, pos - offset)
            }
            shift += 7
            if (shift > 63) break // Overflow protection
        }
        return null
    }

    /**
     * Holder for extracted protobuf fields.
     */
    private class ProtobufFields(
        private val varints: Map<Int, Long>,
        private val bytesFields: Map<Int, ByteArray>
    ) {
        fun varint(fieldNumber: Int): Long? = varints[fieldNumber]
        fun bytes(fieldNumber: Int): ByteArray? = bytesFields[fieldNumber]
    }
}
