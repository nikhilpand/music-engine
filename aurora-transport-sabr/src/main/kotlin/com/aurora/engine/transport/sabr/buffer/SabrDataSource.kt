package com.aurora.engine.transport.sabr.buffer

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * ExoPlayer [DataSource] backed by a [SabrMediaBuffer].
 *
 * This bridges the SABR buffer into ExoPlayer's data loading pipeline.
 * ExoPlayer calls [open] → [read] → [close] to consume media data, and this
 * class translates those calls into [SabrMediaBuffer.read].
 *
 * ## Important
 *
 * - This DataSource does NOT perform any network I/O. Network feeding is done
 *   by the session controller writing into the [SabrMediaBuffer].
 * - The [readTimeoutMs] controls how long [read] blocks waiting for data before
 *   throwing an IOException (which ExoPlayer interprets as a recoverable error).
 *
 * @param buffer The SABR media buffer to read from.
 * @param readTimeoutMs Maximum time to block in [read] waiting for data.
 */
class SabrDataSource(
    private val buffer: SabrMediaBuffer,
    private val readTimeoutMs: Long = 5000L
) : DataSource {

    private var uri: Uri? = null
    private var currentOffset: Long = 0
    private var bytesRemaining: Long = -1

    /**
     * Opens the DataSource for reading.
     *
     * @param dataSpec The data specification describing what range to read.
     * @return The number of bytes that can be read, or [C.LENGTH_UNSET] if unknown.
     */
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        currentOffset = dataSpec.position
        bytesRemaining = if (dataSpec.length != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            -1L // Unknown length — read until EOF
        }
        return bytesRemaining
    }

    /**
     * Read data from the SABR buffer.
     *
     * @param buffer Destination buffer.
     * @param offset Offset in the destination buffer.
     * @param length Maximum number of bytes to read.
     * @return Number of bytes read, or [C.RESULT_END_OF_INPUT] if end of stream.
     * @throws IOException if the buffer is closed or timeout expires.
     */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val toRead = if (bytesRemaining > 0) {
            minOf(length.toLong(), bytesRemaining).toInt()
        } else {
            length
        }

        val read = this.buffer.read(currentOffset, buffer, offset, toRead, readTimeoutMs)

        if (read == -1) {
            return androidx.media3.common.C.RESULT_END_OF_INPUT
        }

        currentOffset += read
        if (bytesRemaining > 0) {
            bytesRemaining -= read
        }

        return read
    }

    /**
     * Returns the URI of the current data source, if opened.
     */
    override fun getUri(): Uri? = uri

    /**
     * Closes the DataSource. No-op since the buffer lifecycle is managed separately.
     */
    override fun close() {
        uri = null
    }

    /**
     * Transfer listeners are not used since this DataSource does no network I/O.
     * The buffer is fed by the session controller, not by this DataSource.
     */
    override fun addTransferListener(transferListener: TransferListener) {
        // No-op: SABR data source is buffer-backed, not network-backed
    }
}
