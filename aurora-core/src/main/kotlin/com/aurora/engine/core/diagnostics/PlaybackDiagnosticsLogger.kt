package com.aurora.engine.core.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

@Serializable
data class DiagnosticEvent(
    val eventType: String,
    val severity: DiagnosticSeverity,
    val message: String,
    val properties: Map<String, String> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis()
)

class PlaybackDiagnosticsLogger(
    val capacity: Int = 500,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    init {
        require(capacity > 0) { "Capacity must be greater than 0: $capacity" }
    }

    private val ringBuffer = arrayOfNulls<DiagnosticEvent>(capacity)
    private var writeIndex = 0
    private var count = 0
    private val lock = Any()

    fun log(
        eventType: String,
        severity: DiagnosticSeverity,
        message: String,
        properties: Map<String, String> = emptyMap()
    ) {
        val sanitizedProps = DiagnosticSanitizer.sanitizeProperties(properties)
        val sanitizedMsg = if (message.contains("http://") || message.contains("https://")) {
            // Scrub any raw URLs embedded in message text
            DiagnosticSanitizer.sanitizeUrl(message)
        } else {
            message
        }

        val event = DiagnosticEvent(
            eventType = eventType,
            severity = severity,
            message = sanitizedMsg,
            properties = sanitizedProps,
            timestampMs = clock()
        )

        logEvent(event)
    }

    fun logEvent(event: DiagnosticEvent) {
        // Ensure properties in pre-constructed events are also sanitized
        val safeEvent = if (event.properties.isNotEmpty()) {
            event.copy(properties = DiagnosticSanitizer.sanitizeProperties(event.properties))
        } else {
            event
        }

        synchronized(lock) {
            ringBuffer[writeIndex] = safeEvent
            writeIndex = (writeIndex + 1) % capacity
            if (count < capacity) {
                count++
            }
        }
    }

    fun getRecentEvents(limit: Int = count): List<DiagnosticEvent> {
        synchronized(lock) {
            val actualLimit = limit.coerceIn(0, count)
            val result = ArrayList<DiagnosticEvent>(actualLimit)

            // Start from newest to oldest
            for (i in 0 until actualLimit) {
                val index = (writeIndex - 1 - i + capacity) % capacity
                val event = ringBuffer[index]
                if (event != null) {
                    result.add(event)
                }
            }
            return result
        }
    }

    fun getAllEventsOldestFirst(): List<DiagnosticEvent> {
        synchronized(lock) {
            val result = ArrayList<DiagnosticEvent>(count)
            val start = if (count < capacity) 0 else writeIndex
            for (i in 0 until count) {
                val index = (start + i) % capacity
                val event = ringBuffer[index]
                if (event != null) {
                    result.add(event)
                }
            }
            return result
        }
    }

    fun clear() {
        synchronized(lock) {
            for (i in ringBuffer.indices) {
                ringBuffer[i] = null
            }
            writeIndex = 0
            count = 0
        }
    }

    val currentCount: Int
        get() = synchronized(lock) { count }

    fun exportLogs(): String {
        val events = getAllEventsOldestFirst()
        val sb = StringBuilder()
        for (event in events) {
            sb.append("[${event.timestampMs}] [${event.severity}] [${event.eventType}]: ${event.message}")
            if (event.properties.isNotEmpty()) {
                sb.append(" | props=${event.properties}")
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
