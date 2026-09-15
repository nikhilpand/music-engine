package com.aurora.engine.provider.ytmusic.transform

interface PlayerTransformProvider {
    val name: String
    val canTransform: Boolean get() = true

    suspend fun decipherSignature(encryptedSignature: String, scriptSource: String? = null): String

    suspend fun transformN(nParameter: String, scriptSource: String? = null): String
}

class PassThroughPlayerTransformProvider : PlayerTransformProvider {
    override val name: String = "PassThrough"
    override val canTransform: Boolean = false

    override suspend fun decipherSignature(encryptedSignature: String, scriptSource: String?): String {
        throw UnsupportedOperationException("PassThroughPlayerTransformProvider cannot decipher signatures")
    }

    override suspend fun transformN(nParameter: String, scriptSource: String?): String {
        throw UnsupportedOperationException("PassThroughPlayerTransformProvider cannot transform n-parameter")
    }
}
