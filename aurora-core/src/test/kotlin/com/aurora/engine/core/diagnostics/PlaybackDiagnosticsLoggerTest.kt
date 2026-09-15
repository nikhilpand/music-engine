package com.aurora.engine.core.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackDiagnosticsLoggerTest {

    @Test
    @DisplayName("Logger stores events up to fixed ring buffer capacity")
    fun testRingBufferCapacity() {
        val logger = PlaybackDiagnosticsLogger(capacity = 5)

        for (i in 1..5) {
            logger.log("EVENT_$i", DiagnosticSeverity.INFO, "Message $i")
        }
        assertThat(logger.currentCount).isEqualTo(5)

        // Add 3 more events; should overwrite the 3 oldest events
        for (i in 6..8) {
            logger.log("EVENT_$i", DiagnosticSeverity.INFO, "Message $i")
        }
        assertThat(logger.currentCount).isEqualTo(5)

        val recent = logger.getRecentEvents()
        assertThat(recent).hasSize(5)
        // Most recent first
        assertThat(recent[0].eventType).isEqualTo("EVENT_8")
        assertThat(recent[1].eventType).isEqualTo("EVENT_7")
        assertThat(recent[2].eventType).isEqualTo("EVENT_6")
        assertThat(recent[3].eventType).isEqualTo("EVENT_5")
        assertThat(recent[4].eventType).isEqualTo("EVENT_4")
    }

    @Test
    @DisplayName("Concurrent diagnostic writes across multiple threads are thread-safe and do not corrupt buffer")
    fun testConcurrentWrites() {
        val logger = PlaybackDiagnosticsLogger(capacity = 200)
        val threadCount = 10
        val writesPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                for (w in 0 until writesPerThread) {
                    logger.log(
                        eventType = "CONCURRENT_EVENT",
                        severity = DiagnosticSeverity.DEBUG,
                        message = "Thread $t write $w",
                        properties = mapOf("thread" to "$t", "write" to "$w")
                    )
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(5, TimeUnit.SECONDS)
        assertThat(finished).isTrue()

        // Ring buffer must be capped at 200 without throwing exceptions
        assertThat(logger.currentCount).isEqualTo(200)
        val allEvents = logger.getAllEventsOldestFirst()
        assertThat(allEvents).hasSize(200)
        for (event in allEvents) {
            assertThat(event.eventType).isEqualTo("CONCURRENT_EVENT")
        }
    }

    @Test
    @DisplayName("Logger automatically scrubs sensitive properties passed in log calls")
    fun testAutomaticScrubbingOnLog() {
        val logger = PlaybackDiagnosticsLogger(capacity = 10)

        logger.log(
            eventType = "STREAM_REQUEST",
            severity = DiagnosticSeverity.INFO,
            message = "Starting stream request",
            properties = mapOf(
                "url" to "https://googlevideo.com/playback?sig=secret123&itag=140",
                "authorization" to "Bearer token_secret",
                "normal_param" to "safe_value"
            )
        )

        val event = logger.getRecentEvents(1).first()
        assertThat(event.properties["url"]).contains("sig=[REDACTED]")
        assertThat(event.properties["url"]).contains("itag=140")
        assertThat(event.properties["authorization"]).isEqualTo("[REDACTED]")
        assertThat(event.properties["normal_param"]).isEqualTo("safe_value")
    }

    @Test
    @DisplayName("exportLogs outputs clean formatted text")
    fun testExportLogs() {
        val logger = PlaybackDiagnosticsLogger(capacity = 10)
        logger.log("START", DiagnosticSeverity.INFO, "Engine started")
        logger.log("PLAY", DiagnosticSeverity.DEBUG, "Track playing")

        val exported = logger.exportLogs()
        assertThat(exported).contains("[INFO] [START]: Engine started")
        assertThat(exported).contains("[DEBUG] [PLAY]: Track playing")
    }
}
