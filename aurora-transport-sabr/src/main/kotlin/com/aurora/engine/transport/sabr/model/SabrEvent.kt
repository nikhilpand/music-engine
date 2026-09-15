package com.aurora.engine.transport.sabr.model

/**
 * Semantic events produced by the SABR protocol processing pipeline.
 *
 * The [SabrMessageDecoder] converts raw [UmpPart] records into these events,
 * providing a clean, typed contract between the protocol layer and the session
 * controller.
 *
 * All byte arrays in events are defensively copied at creation time to prevent
 * mutation after decoding.
 */
sealed interface SabrEvent {

    /**
     * Raw media bytes at a specific byte offset for a given format/itag.
     *
     * @property formatId The format identifier (itag) this data belongs to.
     * @property data The media bytes (defensively copied).
     * @property offsetBytes The byte offset within the complete media stream.
     */
    data class MediaData(
        val formatId: Int,
        val data: ByteArray,
        val offsetBytes: Long
    ) : SabrEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MediaData) return false
            return formatId == other.formatId &&
                data.contentEquals(other.data) &&
                offsetBytes == other.offsetBytes
        }

        override fun hashCode(): Int {
            var result = formatId
            result = 31 * result + data.contentHashCode()
            result = 31 * result + offsetBytes.hashCode()
            return result
        }
    }

    /**
     * Initialization segment (codec configuration) for a given format.
     * Must be fed to the decoder before any [MediaData] for that format.
     */
    data class FormatInit(
        val formatId: Int,
        val initSegment: ByteArray
    ) : SabrEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FormatInit) return false
            return formatId == other.formatId &&
                initSegment.contentEquals(other.initSegment)
        }

        override fun hashCode(): Int {
            var result = formatId
            result = 31 * result + initSegment.contentHashCode()
            return result
        }
    }

    /**
     * End-of-media marker for a format. May indicate final end of stream
     * or a segment boundary requiring continuation.
     */
    data class MediaEnd(val formatId: Int) : SabrEvent

    /**
     * Server's policy for when to send the next continuation request.
     * Includes the echoed playback cookie for session continuity.
     */
    data class NextRequestPolicy(
        val playbackCookie: ByteArray?,
        val backoffMs: Long?,
        val targetBufferDurationMs: Long?
    ) : SabrEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NextRequestPolicy) return false
            return (playbackCookie?.contentEquals(other.playbackCookie ?: byteArrayOf())
                ?: (other.playbackCookie == null)) &&
                backoffMs == other.backoffMs &&
                targetBufferDurationMs == other.targetBufferDurationMs
        }

        override fun hashCode(): Int {
            var result = playbackCookie?.contentHashCode() ?: 0
            result = 31 * result + (backoffMs?.hashCode() ?: 0)
            result = 31 * result + (targetBufferDurationMs?.hashCode() ?: 0)
            return result
        }
    }

    /** Server demands session re-initialization (e.g., CDN rotation). */
    data class ReloadRequired(val reason: String) : SabrEvent

    /** Server redirects to a different CDN endpoint. */
    data class ServerRedirect(val newEndpoint: String) : SabrEvent

    /** Server-initiated error within the UMP stream. */
    data class StreamError(val code: Int, val message: String) : SabrEvent

    /** Unknown/unrecognized UMP part. Logged at DEBUG, otherwise ignored. */
    data class UnknownPart(val typeId: Int, val payloadSize: Int) : SabrEvent
}
