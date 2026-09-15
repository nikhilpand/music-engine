package com.aurora.engine.transport.sabr.buffer

import java.io.IOException
import java.util.TreeMap

/**
 * Coverage-map based media buffer for SABR transport.
 *
 * Unlike a simple ring buffer, this buffer tracks which byte ranges have been filled
 * and supports non-contiguous writes (e.g., after seek, retry, or out-of-order delivery).
 *
 * ## Design
 *
 * - Media bytes are stored in a flat byte array of fixed capacity.
 * - A [TreeMap] tracks filled byte ranges (coverage map).
 * - Reads block until the requested byte range is covered, or timeout occurs.
 * - Writes can arrive in any order; overlapping/duplicate writes are idempotent.
 *
 * ## Thread Safety
 *
 * All public methods are synchronized. The [read] method uses `wait()` to block
 * until data is available, and [write] calls `notifyAll()` to wake blocked readers.
 *
 * @param capacity Maximum buffer capacity in bytes.
 */
class SabrMediaBuffer(private val capacity: Long) {

    /** Backing storage. Allocated lazily on first write to avoid wasted memory. */
    private var storage: ByteArray? = null

    /**
     * Coverage map: maps [startOffset, endOffset) ranges that have been written.
     * Key = start byte offset, Value = end byte offset (exclusive).
     * Ranges are always merged to maintain the invariant: no two adjacent/overlapping entries.
     */
    private val coverageMap = TreeMap<Long, Long>()

    /** Total bytes written (after deduplication). */
    private var totalBytesWritten = 0L

    /** Set to true when the complete media stream has been received. */
    @Volatile
    private var isComplete = false

    /** Set to true when the buffer is released / closed. */
    @Volatile
    private var isClosed = false

    /** The base offset: the lowest byte offset in the current buffer window. */
    private var baseOffset = 0L

    /**
     * Write media bytes at the given stream offset.
     *
     * Overlapping or duplicate writes are silently ignored (idempotent).
     * Out-of-order writes are supported via the coverage map.
     *
     * @param streamOffset Byte offset within the complete media stream.
     * @param data Media bytes to write.
     * @throws IOException if the buffer is closed or data would exceed capacity.
     */
    @Synchronized
    fun write(streamOffset: Long, data: ByteArray) {
        if (isClosed) throw IOException("SabrMediaBuffer: write after close")
        if (data.isEmpty()) return

        val endOffset = streamOffset + data.size

        // Lazy allocation
        if (storage == null) {
            storage = ByteArray(capacity.toInt())
            baseOffset = streamOffset
        }

        // Check capacity
        val bufferIndex = streamOffset - baseOffset
        if (bufferIndex < 0 || bufferIndex + data.size > capacity) {
            throw IOException(
                "SabrMediaBuffer: write at offset $streamOffset (bufferIndex=$bufferIndex, " +
                    "size=${data.size}) exceeds capacity $capacity (base=$baseOffset)"
            )
        }

        // Copy data into storage
        System.arraycopy(data, 0, storage!!, bufferIndex.toInt(), data.size)

        // Update coverage map with merge
        mergeCoverage(streamOffset, endOffset)

        totalBytesWritten += data.size

        // Wake up blocked readers
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as Object).notifyAll()
    }

    /**
     * Read [length] bytes starting from [streamOffset].
     *
     * Blocks until the requested range is covered or timeout expires.
     *
     * @param streamOffset Byte offset within the complete media stream.
     * @param buffer Destination buffer.
     * @param bufferOffset Offset in the destination buffer.
     * @param length Number of bytes to read.
     * @param timeoutMs Maximum time to wait for data (0 = no wait).
     * @return Number of bytes read, or -1 if end of stream.
     * @throws IOException if the buffer is closed or timeout expires with no data.
     */
    @Synchronized
    fun read(
        streamOffset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
        timeoutMs: Long = 5000L
    ): Int {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (!isClosed) {
            // Check if we have coverage for the requested range
            val available = contiguousBytesFrom(streamOffset)

            if (available > 0) {
                val toRead = minOf(available.toInt(), length)
                val bufIdx = (streamOffset - baseOffset).toInt()
                System.arraycopy(storage!!, bufIdx, buffer, bufferOffset, toRead)
                return toRead
            }

            // If stream is complete and nothing is available, it's EOF
            if (isComplete) return -1

            // Wait for data or timeout
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw IOException(
                    "SabrMediaBuffer: read timeout at offset $streamOffset (waited ${timeoutMs}ms)"
                )
            }

            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (this as Object).wait(remaining)
        }

        throw IOException("SabrMediaBuffer: read after close")
    }

    /**
     * Mark the stream as complete. Wakes any blocked readers so they receive EOF.
     */
    @Synchronized
    fun markComplete() {
        isComplete = true
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as Object).notifyAll()
    }

    /**
     * Reset the buffer for a new playback position (e.g., after seek).
     *
     * Clears all coverage and resets the base offset.
     *
     * @param newBaseOffset The new base stream offset.
     */
    @Synchronized
    fun resetForSeek(newBaseOffset: Long) {
        coverageMap.clear()
        storage?.fill(0)
        baseOffset = newBaseOffset
        isComplete = false
        totalBytesWritten = 0

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as Object).notifyAll()
    }

    /**
     * Close the buffer and release resources. Wakes any blocked readers.
     */
    @Synchronized
    fun close() {
        isClosed = true
        storage = null
        coverageMap.clear()

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as Object).notifyAll()
    }

    // --- Query methods ---

    /** Returns the number of contiguous bytes buffered from the given offset. */
    @Synchronized
    fun contiguousBytesFrom(offset: Long): Long {
        // Find the coverage entry that could contain this offset
        val entry = coverageMap.floorEntry(offset) ?: return 0
        return if (entry.value > offset) {
            entry.value - offset
        } else {
            0
        }
    }

    /** Returns the total number of bytes covered (may be non-contiguous). */
    @Synchronized
    fun totalBytesCovered(): Long {
        var total = 0L
        for ((start, end) in coverageMap) {
            total += (end - start)
        }
        return total
    }

    /** Returns true if the range [offset, offset+length) is fully covered. */
    @Synchronized
    fun isRangeCovered(offset: Long, length: Int): Boolean {
        return contiguousBytesFrom(offset) >= length
    }

    /** Returns the current base offset. */
    @Synchronized
    fun getBaseOffset(): Long = baseOffset

    /** Returns true if the stream has been marked complete. */
    fun isStreamComplete(): Boolean = isComplete

    /** Returns true if the buffer has been closed. */
    fun isBufferClosed(): Boolean = isClosed

    /** Returns a copy of the coverage map for diagnostics. */
    @Synchronized
    fun getCoverageSnapshot(): Map<Long, Long> = TreeMap(coverageMap)

    // --- Private helpers ---

    /**
     * Merge a new range [start, end) into the coverage map.
     * Adjacent and overlapping ranges are merged into a single entry.
     */
    private fun mergeCoverage(start: Long, end: Long) {
        var mergedStart = start
        var mergedEnd = end

        // Check for overlapping/adjacent ranges below
        val floorEntry = coverageMap.floorEntry(start)
        if (floorEntry != null && floorEntry.value >= start) {
            mergedStart = minOf(mergedStart, floorEntry.key)
            mergedEnd = maxOf(mergedEnd, floorEntry.value)
            coverageMap.remove(floorEntry.key)
        }

        // Check for overlapping/adjacent ranges above
        val iterator = coverageMap.tailMap(mergedStart).entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key > mergedEnd) break
            mergedEnd = maxOf(mergedEnd, entry.value)
            iterator.remove()
        }

        coverageMap[mergedStart] = mergedEnd
    }
}
