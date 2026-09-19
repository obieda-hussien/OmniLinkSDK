package com.omnilink.transport

import kotlinx.serialization.Serializable

object OmniTransferCapabilities {
    const val OPEN = "_transfer.open"
    const val CHUNK = "_transfer.chunk"
    const val COMMIT = "_transfer.commit"
    const val CANCEL = "_transfer.cancel"
}

@Serializable
data class TransferManifest(
    val transferId: String,
    val fileName: String,
    val mimeType: String = "application/octet-stream",
    val totalBytes: Long,
    val sha256: String,
    val chunkBytes: Int = 1024 * 1024,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class TransferOpenReply(
    val accepted: Boolean,
    val nextOffset: Long = 0,
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class TransferChunkReply(
    val accepted: Boolean,
    val nextOffset: Long,
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class TransferCommitReply(
    val accepted: Boolean,
    val storedPath: String? = null,
    val code: String? = null,
    val message: String? = null
)

data class TransferPolicy(
    val maxFileBytes: Long = 16L * 1024 * 1024 * 1024,
    val maxChunkBytes: Int = 1024 * 1024,
    val requestTimeoutMillis: Long = 60_000
) {
    init {
        require(maxFileBytes > 0)
        require(maxChunkBytes in 16 * 1024..4 * 1024 * 1024)
        require(requestTimeoutMillis > 0)
    }
}

data class TransferProgress(
    val transferId: String,
    val transferredBytes: Long,
    val totalBytes: Long
) {
    val fraction: Double
        get() = if (totalBytes <= 0) 1.0 else
            (transferredBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
}
