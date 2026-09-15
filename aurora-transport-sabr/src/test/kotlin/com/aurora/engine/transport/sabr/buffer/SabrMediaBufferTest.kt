package com.aurora.engine.transport.sabr.buffer

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SabrMediaBufferTest {

    private lateinit var buffer: SabrMediaBuffer

    @BeforeEach
    fun setUp() {
        buffer = SabrMediaBuffer(capacity = 1024)
    }

    @AfterEach
    fun tearDown() {
        buffer.close()
    }

    // --- Basic write and read ---

    @Test
    fun `write and read contiguous data`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        buffer.write(0, data)

        val dest = ByteArray(4)
        val read = buffer.read(0, dest, 0, 4, timeoutMs = 100)
        assertThat(read).isEqualTo(4)
        assertThat(dest).isEqualTo(data)
    }

    @Test
    fun `read partial coverage returns available bytes`() {
        buffer.write(0, byteArrayOf(0x01, 0x02, 0x03))

        val dest = ByteArray(5)
        val read = buffer.read(0, dest, 0, 5, timeoutMs = 100)
        assertThat(read).isEqualTo(3)
        assertThat(dest.take(3).toByteArray()).isEqualTo(byteArrayOf(0x01, 0x02, 0x03))
    }

    // --- Coverage map ---

    @Test
    fun `contiguousBytesFrom returns correct count`() {
        buffer.write(0, byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertThat(buffer.contiguousBytesFrom(0)).isEqualTo(4)
        assertThat(buffer.contiguousBytesFrom(2)).isEqualTo(2)
        assertThat(buffer.contiguousBytesFrom(4)).isEqualTo(0)
    }

    @Test
    fun `non-contiguous writes are tracked separately`() {
        buffer.write(0, byteArrayOf(0x01, 0x02))
        buffer.write(10, byteArrayOf(0x0A, 0x0B))

        assertThat(buffer.contiguousBytesFrom(0)).isEqualTo(2)
        assertThat(buffer.contiguousBytesFrom(2)).isEqualTo(0)
        assertThat(buffer.contiguousBytesFrom(10)).isEqualTo(2)
        assertThat(buffer.totalBytesCovered()).isEqualTo(4)
    }

    @Test
    fun `adjacent writes are merged`() {
        buffer.write(0, byteArrayOf(0x01, 0x02))
        buffer.write(2, byteArrayOf(0x03, 0x04))

        assertThat(buffer.contiguousBytesFrom(0)).isEqualTo(4)
        assertThat(buffer.getCoverageSnapshot()).hasSize(1)
    }

    @Test
    fun `overlapping writes are merged and idempotent`() {
        buffer.write(0, byteArrayOf(0x01, 0x02, 0x03))
        buffer.write(2, byteArrayOf(0x03, 0x04, 0x05))

        assertThat(buffer.contiguousBytesFrom(0)).isEqualTo(5)
        assertThat(buffer.getCoverageSnapshot()).hasSize(1)

        // Read should return the latest write for overlapping region
        val dest = ByteArray(5)
        buffer.read(0, dest, 0, 5, timeoutMs = 100)
        assertThat(dest).isEqualTo(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
    }

    @Test
    fun `isRangeCovered returns correct boolean`() {
        buffer.write(0, byteArrayOf(0x01, 0x02, 0x03, 0x04))

        assertThat(buffer.isRangeCovered(0, 4)).isTrue()
        assertThat(buffer.isRangeCovered(0, 5)).isFalse()
        assertThat(buffer.isRangeCovered(2, 2)).isTrue()
        assertThat(buffer.isRangeCovered(3, 2)).isFalse()
    }

    // --- End of stream ---

    @Test
    fun `markComplete causes read to return -1 when no data`() {
        buffer.write(0, byteArrayOf(0x01))
        buffer.markComplete()

        val dest = ByteArray(4)
        // Read available data first
        val read1 = buffer.read(0, dest, 0, 4, timeoutMs = 100)
        assertThat(read1).isEqualTo(1)

        // Read beyond available data returns -1
        val read2 = buffer.read(1, dest, 0, 4, timeoutMs = 100)
        assertThat(read2).isEqualTo(-1)
    }

    // --- Seek / reset ---

    @Test
    fun `resetForSeek clears coverage and updates base offset`() {
        buffer.write(0, byteArrayOf(0x01, 0x02, 0x03))
        assertThat(buffer.totalBytesCovered()).isEqualTo(3)

        buffer.resetForSeek(1000)

        assertThat(buffer.totalBytesCovered()).isEqualTo(0)
        assertThat(buffer.getBaseOffset()).isEqualTo(1000)
        assertThat(buffer.isStreamComplete()).isFalse()

        // Write new data at new offset
        buffer.write(1000, byteArrayOf(0x0A, 0x0B))
        assertThat(buffer.contiguousBytesFrom(1000)).isEqualTo(2)
    }

    // --- Close ---

    @Test
    fun `write after close throws IOException`() {
        buffer.close()
        assertThrows<IOException> { buffer.write(0, byteArrayOf(0x01)) }
    }

    @Test
    fun `read after close throws IOException`() {
        buffer.close()
        assertThrows<IOException> { buffer.read(0, ByteArray(4), 0, 4, timeoutMs = 100) }
    }

    // --- Timeout ---

    @Test
    fun `read with no data times out`() {
        assertThrows<IOException> {
            buffer.read(0, ByteArray(4), 0, 4, timeoutMs = 50)
        }
    }

    // --- Blocking read woken by write ---

    @Test
    fun `read blocks until write provides data`() {
        val readResult = AtomicInteger(-1)
        val dest = ByteArray(4)
        val latch = CountDownLatch(1)

        val reader = Thread {
            readResult.set(buffer.read(0, dest, 0, 4, timeoutMs = 5000))
            latch.countDown()
        }
        reader.start()

        // Give reader time to block
        Thread.sleep(50)

        // Write data to wake it up
        buffer.write(0, byteArrayOf(0x01, 0x02, 0x03, 0x04))

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(readResult.get()).isEqualTo(4)
        assertThat(dest).isEqualTo(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    }

    @Test
    fun `read blocks until markComplete for EOF`() {
        // Write some data first
        buffer.write(0, byteArrayOf(0x01))

        val readResult = AtomicInteger(99)
        val latch = CountDownLatch(1)

        val reader = Thread {
            // Try to read at offset 1 — no data there
            readResult.set(buffer.read(1, ByteArray(4), 0, 4, timeoutMs = 5000))
            latch.countDown()
        }
        reader.start()

        Thread.sleep(50)
        buffer.markComplete()

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(readResult.get()).isEqualTo(-1) // EOF
    }

    // --- Capacity ---

    @Test
    fun `write exceeding capacity throws IOException`() {
        val largeData = ByteArray(1025) { 0x01 }
        assertThrows<IOException> { buffer.write(0, largeData) }
    }

    // --- Empty writes ---

    @Test
    fun `empty write is no-op`() {
        buffer.write(0, ByteArray(0))
        assertThat(buffer.totalBytesCovered()).isEqualTo(0)
    }
}
