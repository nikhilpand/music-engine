package com.aurora.engine.transport.sabr.protocol

/**
 * Known UMP (Universal Media Protocol) message type IDs.
 *
 * These constants represent the type identifiers found in UMP frame headers.
 * Values are based on observed protocol behavior from open-source research.
 *
 * Unknown type IDs are handled gracefully (logged and skipped).
 */
object UmpMessageType {
    /** Onesie header: initial metadata about the response. */
    const val ONESIE_HEADER = 10

    /** Media header: metadata for a media segment (format, offset, etc.). */
    const val MEDIA_HEADER = 20

    /** Media data: raw audio/video bytes at a given offset. */
    const val MEDIA_DATA = 21

    /** Media end: signals end of data for a format (segment or final). */
    const val MEDIA_END = 22

    /** Format initialization: codec configuration data for a format/itag. */
    const val FORMAT_INIT = 42

    /** Next request policy: server's instructions for continuation. */
    const val NEXT_REQUEST_POLICY = 43

    /** Reload required: server demands session re-initialization. */
    const val RELOAD = 44

    /** Server redirect: CDN endpoint change directive. */
    const val REDIRECT = 45

    /** Stream error: server-initiated error message. */
    const val STREAM_ERROR = 46

    /**
     * Returns a human-readable name for the type ID, for diagnostic logging only.
     * Never includes sensitive data.
     */
    fun nameOf(typeId: Int): String = when (typeId) {
        ONESIE_HEADER -> "ONESIE_HEADER"
        MEDIA_HEADER -> "MEDIA_HEADER"
        MEDIA_DATA -> "MEDIA_DATA"
        MEDIA_END -> "MEDIA_END"
        FORMAT_INIT -> "FORMAT_INIT"
        NEXT_REQUEST_POLICY -> "NEXT_REQUEST_POLICY"
        RELOAD -> "RELOAD"
        REDIRECT -> "REDIRECT"
        STREAM_ERROR -> "STREAM_ERROR"
        else -> "UNKNOWN($typeId)"
    }
}
