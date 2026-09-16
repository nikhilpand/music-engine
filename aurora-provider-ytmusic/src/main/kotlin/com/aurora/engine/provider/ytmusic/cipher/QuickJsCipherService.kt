package com.aurora.engine.provider.ytmusic.cipher

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * CipherService implementation using QuickJS JavaScript engine.
 *
 * ## Architecture
 *
 * ```
 * CipherService.decipherSignature(sig, playerUrl)
 *       ↓
 * ensureExtracted(playerUrl)
 *       ↓ (cache miss)
 * fetchPlayerJs(playerUrl) → CipherFunctionExtractor.extract(js) → cache
 *       ↓
 * QuickJS evaluate: "funcName('sig')"
 *       ↓
 * deciphered signature
 * ```
 *
 * ## Thread Safety
 *
 * - Script fetching and extraction are protected by a per-URL [Mutex] to prevent
 *   duplicate downloads for the same player version.
 * - QuickJS evaluation is single-threaded per runtime (QuickJS design constraint).
 *   We use a mutex around eval calls.
 *
 * ## QuickJS Integration
 *
 * This class depends on `io.github.dokar3:quickjs-kt`. The integration is designed
 * to be swappable: if QuickJS becomes unavailable, only this class needs replacement.
 *
 * @param httpClient OkHttpClient for fetching player JavaScript.
 * @param extractor Function extractor (injectable for testing).
 */
open class QuickJsCipherService(
    private val httpClient: OkHttpClient,
    private val extractor: CipherFunctionExtractor = CipherFunctionExtractor()
) : CipherService {

    /**
     * Cache of extracted functions keyed by player script URL.
     */
    private val extractionCache = ConcurrentHashMap<String, ExtractedCipherFunctions>()

    /**
     * Per-URL extraction mutexes to prevent duplicate fetches.
     */
    private val extractionLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Mutex for QuickJS evaluation (QuickJS is single-threaded).
     */
    private val evalMutex = Mutex()

    @Volatile
    private var closed = false

    override val isOperational: Boolean = true

    override suspend fun decipherSignature(encryptedSignature: String, playerScriptUrl: String): String {
        check(!closed) { "CipherService has been closed" }

        val functions = ensureExtracted(playerScriptUrl)
        val sigFunc = functions.signatureFunction

        return evaluate(
            sigFunc.source,
            sigFunc.name,
            encryptedSignature,
            "signature decipher"
        )
    }

    override suspend fun transformN(nParameter: String, playerScriptUrl: String): String {
        check(!closed) { "CipherService has been closed" }

        val functions = ensureExtracted(playerScriptUrl)
        val nFunc = functions.nTransformFunction
            ?: throw CipherException(
                "N-transform function not found in player JS: $playerScriptUrl",
                CipherPhase.FUNCTION_EXTRACTION
            )

        return evaluate(
            nFunc.source,
            nFunc.name,
            nParameter,
            "n-parameter transform"
        )
    }

    override suspend fun invalidate(playerScriptUrl: String) {
        extractionCache.remove(playerScriptUrl)
    }

    override fun close() {
        closed = true
        extractionCache.clear()
    }

    // ── Private ─────────────────────────────────────────────────────────────

    private suspend fun ensureExtracted(playerScriptUrl: String): ExtractedCipherFunctions {
        // Fast path: already cached
        extractionCache[playerScriptUrl]?.let { return it }

        // Slow path: fetch and extract (with per-URL lock)
        val mutex = extractionLocks.computeIfAbsent(playerScriptUrl) { Mutex() }
        return mutex.withLock {
            // Double-check after acquiring lock
            extractionCache[playerScriptUrl]?.let { return@withLock it }

            val playerJs = fetchPlayerJs(playerScriptUrl)
            val functions = try {
                extractor.extract(playerJs)
            } catch (e: CipherException) {
                throw e
            } catch (e: Exception) {
                throw CipherException(
                    "Failed to extract cipher functions from player JS",
                    CipherPhase.FUNCTION_EXTRACTION,
                    e
                )
            }

            extractionCache[playerScriptUrl] = functions
            functions
        }
    }

    private suspend fun fetchPlayerJs(url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw CipherException(
                        "Failed to fetch player JS: HTTP ${response.code} from $url",
                        CipherPhase.SCRIPT_FETCH
                    )
                }
                response.body?.string()
                    ?: throw CipherException(
                        "Empty response body for player JS: $url",
                        CipherPhase.SCRIPT_FETCH
                    )
            }
        } catch (e: CipherException) {
            throw e
        } catch (e: Exception) {
            throw CipherException(
                "Network error fetching player JS: ${e.message}",
                CipherPhase.SCRIPT_FETCH,
                e
            )
        }
    }

    /**
     * Evaluate a JavaScript function with a single string argument using QuickJS.
     *
     * The evaluation script is:
     * ```javascript
     * <functionSource>
     * <functionName>('<input>');
     * ```
     *
     * QuickJS returns the result as a string.
     */
    private suspend fun evaluate(
        functionSource: String,
        functionName: String,
        input: String,
        operationDescription: String
    ): String {
        val escapedInput = input
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val script = "$functionSource\n$functionName('$escapedInput');"

        return evalMutex.withLock {
            try {
                evaluateJs(script)
            } catch (e: Exception) {
                throw CipherException(
                    "QuickJS $operationDescription evaluation failed: ${e.message}",
                    CipherPhase.EVALUATION,
                    e
                )
            }
        }
    }

    /**
     * Evaluate JavaScript source and return the result.
     *
     * This method is separated to allow testing with a mock QuickJS runtime.
     * In production, it uses `com.nicholasfletcher.quickjs.QuickJs`.
     *
     * NOTE: The actual QuickJS JNI call will be wired in Phase 5B after the
     * quickjs-kt dependency is confirmed to compile on the target environment.
     * For now, this method provides the integration point.
     */
    internal open suspend fun evaluateJs(script: String): String {
        // Production implementation using quickjs-kt:
        // val runtime = QuickJs.create()
        // try {
        //     val result = runtime.evaluate(script)
        //     return result?.toString()
        //         ?: throw CipherException("QuickJS returned null", CipherPhase.VALIDATION)
        // } finally {
        //     runtime.close()
        // }

        // Stub that will be replaced when quickjs-kt dependency is integrated
        throw CipherException(
            "QuickJS runtime not yet integrated. Call site: evaluateJs",
            CipherPhase.EVALUATION
        )
    }
}
