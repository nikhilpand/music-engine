package com.aurora.engine.transport.progressive.datasource

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AuroraHttpDataSourceFactory(
    private val baseClient: OkHttpClient = defaultOkHttpClient(),
    private val userAgent: String = "AuroraMusicEngine/1.2 (Linux; Android 14)",
    private val additionalHeaders: Map<String, String> = emptyMap(),
    private val connectTimeoutMs: Long = 10_000L,
    private val readTimeoutMs: Long = 15_000L
) : DataSource.Factory {

    companion object {
        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val internalFactory = OkHttpDataSource.Factory(client)
        .setUserAgent(userAgent)

    init {
        if (additionalHeaders.isNotEmpty()) {
            internalFactory.setDefaultRequestProperties(additionalHeaders)
        }
    }

    override fun createDataSource(): DataSource {
        return internalFactory.createDataSource()
    }
}
