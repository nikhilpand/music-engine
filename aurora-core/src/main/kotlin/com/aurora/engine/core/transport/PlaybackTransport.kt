package com.aurora.engine.core.transport

import com.aurora.engine.core.model.PlaybackSource
import java.util.concurrent.CopyOnWriteArrayList

interface PlaybackSession : AutoCloseable {
    val sessionId: String
    val source: PlaybackSource
    val isPrepared: Boolean

    suspend fun prepare()

    suspend fun release()

    override fun close() {
        // Default synchronous close hook
    }
}

interface PlaybackTransport {
    val transportId: String

    fun canHandle(source: PlaybackSource): Boolean

    suspend fun createSession(source: PlaybackSource): PlaybackSession
}

class PlaybackTransportRegistry {
    private val transports = CopyOnWriteArrayList<PlaybackTransport>()

    fun register(transport: PlaybackTransport) {
        if (!transports.any { it.transportId == transport.transportId }) {
            transports.add(transport)
        }
    }

    fun unregister(transportId: String) {
        transports.removeIf { it.transportId == transportId }
    }

    fun findTransportFor(source: PlaybackSource): PlaybackTransport? {
        return transports.firstOrNull { it.canHandle(source) }
    }

    fun getRegisteredTransports(): List<PlaybackTransport> {
        return transports.toList()
    }

    fun clear() {
        transports.clear()
    }
}
