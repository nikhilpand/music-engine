package com.aurora.engine.provider.ytmusic.cipher

/**
 * No-op CipherService for clients that do not require signature deobfuscation.
 *
 * Used for clients like VISIONOS and TVHTML5 where `requiresCipher = false`.
 * All operations return the input unchanged or throw if called unexpectedly.
 */
class NoOpCipherService : CipherService {

    override val isOperational: Boolean = false

    override suspend fun decipherSignature(encryptedSignature: String, playerScriptUrl: String): String {
        // Return as-is — this client doesn't use cipher
        return encryptedSignature
    }

    override suspend fun transformN(nParameter: String, playerScriptUrl: String): String {
        // Return as-is — this client doesn't use n-parameter throttling
        return nParameter
    }

    override suspend fun invalidate(playerScriptUrl: String) {
        // Nothing to invalidate
    }

    override fun close() {
        // Nothing to release
    }
}
