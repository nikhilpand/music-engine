package com.aurora.engine.provider.ytmusic.cipher

/**
 * Core cipher service interface for YouTube signature deobfuscation.
 *
 * ## Two-Phase Deobfuscation
 *
 * YouTube applies two layers of URL obfuscation:
 * 1. **Signature cipher**: The stream URL's `s` parameter contains an encrypted signature
 *    that must be deciphered using a set of operations (reverse, swap, splice) extracted
 *    from the YouTube player JavaScript.
 * 2. **N-parameter transform**: The `n` query parameter is throttle-keyed. Without
 *    transformation, YouTube serves data at ~50KB/s. The transform function is also
 *    extracted from the player JS.
 *
 * ## Script Lifecycle
 *
 * The player JavaScript URL changes with each YouTube deployment. Implementations must:
 * 1. Fetch the current player JS URL from the YouTube page or player response.
 * 2. Extract the deobfuscation functions from the script.
 * 3. Cache the extracted functions keyed by player script URL.
 * 4. Re-extract when the player URL changes.
 */
interface CipherService {

    /**
     * Whether this implementation can actually perform cipher operations.
     * Returns `false` for no-op/passthrough implementations.
     */
    val isOperational: Boolean

    /**
     * Decipher a signature-encrypted URL parameter.
     *
     * @param encryptedSignature The `s` parameter value from the cipher/signatureCipher.
     * @param playerScriptUrl The URL of the YouTube player JavaScript containing the cipher algorithm.
     * @return The deciphered signature string.
     * @throws CipherException if deobfuscation fails.
     */
    suspend fun decipherSignature(encryptedSignature: String, playerScriptUrl: String): String

    /**
     * Transform the `n` throttle parameter to bypass rate limiting.
     *
     * @param nParameter The current `n` parameter value.
     * @param playerScriptUrl The URL of the YouTube player JavaScript containing the transform function.
     * @return The transformed `n` parameter value.
     * @throws CipherException if transformation fails.
     */
    suspend fun transformN(nParameter: String, playerScriptUrl: String): String

    /**
     * Invalidate any cached extraction for the given player script URL.
     * Used when a cached cipher produces 403 errors, indicating the player JS has changed.
     */
    suspend fun invalidate(playerScriptUrl: String)

    /**
     * Release resources (e.g., QuickJS runtime).
     */
    fun close()
}

/**
 * Exception type for cipher-related failures.
 */
class CipherException(
    message: String,
    val phase: CipherPhase,
    cause: Throwable? = null
) : Exception(message, cause)

enum class CipherPhase {
    /** Failed to fetch or parse the player JavaScript. */
    SCRIPT_FETCH,
    /** Failed to extract the deobfuscation function from the script. */
    FUNCTION_EXTRACTION,
    /** Failed to evaluate the deobfuscation function with the input. */
    EVALUATION,
    /** The result of evaluation was invalid (empty, null, unchanged). */
    VALIDATION
}
