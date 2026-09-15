package com.aurora.engine.transport.progressive.session

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import com.aurora.engine.core.model.PlaybackSource
import com.aurora.engine.core.transport.PlaybackSession
import java.util.concurrent.atomic.AtomicBoolean

class ProgressivePlaybackSession(
    override val sessionId: String,
    override val source: PlaybackSource.Progressive,
    val mediaSource: MediaSource,
    val dataSourceFactory: DataSource.Factory,
    val createdAtMs: Long = System.currentTimeMillis()
) : PlaybackSession {

    private val prepared = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    override val isPrepared: Boolean
        get() = prepared.get() && !released.get()

    override suspend fun prepare() {
        if (released.get()) {
            throw IllegalStateException("Cannot prepare a released playback session ($sessionId)")
        }
        prepared.set(true)
    }

    override suspend fun release() {
        if (released.compareAndSet(false, true)) {
            prepared.set(false)
        }
    }

    override fun close() {
        released.set(true)
        prepared.set(false)
    }
}
