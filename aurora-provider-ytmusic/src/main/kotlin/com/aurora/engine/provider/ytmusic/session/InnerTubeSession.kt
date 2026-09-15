package com.aurora.engine.provider.ytmusic.session

import com.aurora.engine.core.model.ErrorCategory
import com.aurora.engine.core.model.PlaybackError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class InnerTubeResponse(
    val statusCode: Int,
    val body: String,
    val isSuccess: Boolean,
    val latencyMs: Long
)

class InnerTubeSession(
    val baseUrl: String = "https://music.youtube.com",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val cachedVisitorData = AtomicReference<String?>(null)

    fun getVisitorData(): String? = cachedVisitorData.get()

    fun setVisitorData(visitorData: String?) {
        cachedVisitorData.set(visitorData)
    }

    fun buildContextPayload(clientConfig: InnerTubeClientConfig): JsonObject {
        return buildJsonObject {
            putJsonObject("client") {
                put("clientName", clientConfig.clientName)
                put("clientVersion", clientConfig.clientVersion)
                if (clientConfig.clientScreen != null) {
                    put("clientScreen", clientConfig.clientScreen)
                }
                put("userAgent", clientConfig.userAgent)
                put("osName", clientConfig.osName)
                put("osVersion", clientConfig.osVersion)
                put("platform", clientConfig.platform)
                put("hl", clientConfig.hl)
                put("gl", clientConfig.gl)
                put("utcOffsetMinutes", clientConfig.utcOffsetMinutes)

                val visitorData = cachedVisitorData.get()
                if (!visitorData.isNullOrBlank()) {
                    put("visitorData", visitorData)
                }
            }
        }
    }

    suspend fun postJson(
        endpoint: String,
        payload: JsonObject,
        clientConfig: InnerTubeClientConfig
    ): Result<InnerTubeResponse> = withContext(Dispatchers.IO) {
        val url = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            endpoint
        } else {
            val normalizedBase = baseUrl.removeSuffix("/")
            val normalizedEndpoint = endpoint.removePrefix("/")
            "$normalizedBase/$normalizedEndpoint"
        }

        val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("User-Agent", clientConfig.userAgent)
            .header("Content-Type", "application/json")
            .header("Accept", "*/*")
            .header("Accept-Language", "${clientConfig.hl}-${clientConfig.gl}")

        val visitorData = cachedVisitorData.get()
        if (!visitorData.isNullOrBlank()) {
            requestBuilder.header("X-Goog-Visitor-Id", visitorData)
        }

        val start = clock()
        try {
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val latency = clock() - start
            val code = response.code
            val body = response.body?.string().orEmpty()

            // Update visitorData if returned in headers or body
            val visitorHeader = response.header("X-Goog-Visitor-Id")
            if (!visitorHeader.isNullOrBlank()) {
                cachedVisitorData.set(visitorHeader)
            }

            Result.success(
                InnerTubeResponse(
                    statusCode = code,
                    body = body,
                    isSuccess = response.isSuccessful,
                    latencyMs = latency
                )
            )
        } catch (e: SocketTimeoutException) {
            val latency = clock() - start
            Result.failure(
                InnerTubeException(
                    PlaybackError(
                        code = "INNERTUBE_TIMEOUT",
                        message = "InnerTube request timed out: ${e.message}",
                        category = ErrorCategory.TIMEOUT,
                        isRecoverable = true
                    ),
                    latencyMs = latency
                )
            )
        } catch (e: IOException) {
            val latency = clock() - start
            Result.failure(
                InnerTubeException(
                    PlaybackError(
                        code = "INNERTUBE_NETWORK_ERROR",
                        message = "InnerTube network IO failure: ${e.message}",
                        category = ErrorCategory.NETWORK_FAILURE,
                        isRecoverable = true
                    ),
                    latencyMs = latency
                )
            )
        } catch (e: Throwable) {
            val latency = clock() - start
            Result.failure(
                InnerTubeException(
                    PlaybackError(
                        code = "INNERTUBE_UNEXPECTED_ERROR",
                        message = "Unexpected error during InnerTube request: ${e.message}",
                        category = ErrorCategory.UNKNOWN,
                        isRecoverable = false
                    ),
                    latencyMs = latency
                )
            )
        }
    }
}

class InnerTubeException(
    val playbackError: PlaybackError,
    val latencyMs: Long
) : Exception(playbackError.message)
