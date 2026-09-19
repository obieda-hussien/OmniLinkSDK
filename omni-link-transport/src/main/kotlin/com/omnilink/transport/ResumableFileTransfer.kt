package com.omnilink.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class OmniFileTransferSender(
    private val connection: OmniMultiplexedConnection,
    private val policy: TransferPolicy = TransferPolicy()
) {
    suspend fun sendFile(
        source: File,
        manifest: TransferManifest,
        onProgress: (TransferProgress) -> Unit = {}
    ): TransferCommitReply = withContext(Dispatchers.IO) {
        require(source.isFile) { "Source file does not exist: $source" }
        require(manifest.totalBytes == source.length()) { "Manifest size does not match source" }
        require(manifest.totalBytes <= policy.maxFileBytes) { "File exceeds transfer policy" }
        require(manifest.chunkBytes in 1..policy.maxChunkBytes) { "Invalid chunk size" }

        val openResponse = connection.request(
            capability = OmniTransferCapabilities.OPEN,
            payload = TransportJson.instance.encodeToString(
                TransferManifest.serializer(),
                manifest
            ).toByteArray(Charsets.UTF_8),
            timeoutMillis = policy.requestTimeoutMillis
        )
        val open = TransportJson.instance.decodeFromString(
            TransferOpenReply.serializer(),
            openResponse.payload.toString(Charsets.UTF_8)
        )
        if (!open.accepted) {
            return@withContext TransferCommitReply(
                accepted = false,
                code = open.code ?: "open_rejected",
                message = open.message
            )
        }

        val startOffset = open.nextOffset.coerceIn(0, manifest.totalBytes)
        RandomAccessFile(source, "r").use { input ->
            input.seek(startOffset)
            var offset = startOffset
            val buffer = ByteArray(manifest.chunkBytes)
            onProgress(TransferProgress(manifest.transferId, offset, manifest.totalBytes))

            while (offset < manifest.totalBytes) {
                val remaining = manifest.totalBytes - offset
                val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, wanted)
                if (read <= 0) error("Unexpected EOF at offset $offset")

                val chunk = buffer.copyOf(read)
                val chunkSha = sha256(chunk)
                val response = connection.request(
                    capability = OmniTransferCapabilities.CHUNK,
                    payload = chunk,
                    metadata = mapOf(
                        "transferId" to manifest.transferId,
                        "offset" to offset.toString(),
                        "chunkSha256" to chunkSha
                    ),
                    timeoutMillis = policy.requestTimeoutMillis
                )
                val ack = TransportJson.instance.decodeFromString(
                    TransferChunkReply.serializer(),
                    response.payload.toString(Charsets.UTF_8)
                )
                if (!ack.accepted) {
                    return@withContext TransferCommitReply(
                        accepted = false,
                        code = ack.code ?: "chunk_rejected",
                        message = ack.message
                    )
                }
                offset = ack.nextOffset
                input.seek(offset)
                onProgress(TransferProgress(manifest.transferId, offset, manifest.totalBytes))
            }
        }

        val commitResponse = connection.request(
            capability = OmniTransferCapabilities.COMMIT,
            payload = manifest.transferId.toByteArray(Charsets.UTF_8),
            timeoutMillis = policy.requestTimeoutMillis
        )
        TransportJson.instance.decodeFromString(
            TransferCommitReply.serializer(),
            commitResponse.payload.toString(Charsets.UTF_8)
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Durable receiver for request/response transfer messages.
 *
 * Call [tryHandle] from the application's single OmniLink request router before normal capability
 * dispatch. Partial `.part` files survive disconnects, so reopening the same transferId resumes
 * from the last durably written byte.
 */
class OmniFileTransferReceiver(
    private val rootDir: File,
    private val policy: TransferPolicy = TransferPolicy()
) {
    private val lock = Any()
    private val manifests = mutableMapOf<String, TransferManifest>()

    init {
        rootDir.mkdirs()
    }

    suspend fun tryHandle(
        connection: OmniMultiplexedConnection,
        request: TransportMessage
    ): Boolean = withContext(Dispatchers.IO) {
        when (request.capability) {
            OmniTransferCapabilities.OPEN -> {
                val reply = handleOpen(request.payload)
                connection.respond(
                    request,
                    TransportJson.instance.encodeToString(
                        TransferOpenReply.serializer(),
                        reply
                    ).toByteArray(Charsets.UTF_8)
                )
                true
            }
            OmniTransferCapabilities.CHUNK -> {
                val reply = handleChunk(request)
                connection.respond(
                    request,
                    TransportJson.instance.encodeToString(
                        TransferChunkReply.serializer(),
                        reply
                    ).toByteArray(Charsets.UTF_8)
                )
                true
            }
            OmniTransferCapabilities.COMMIT -> {
                val id = request.payload.toString(Charsets.UTF_8)
                val reply = handleCommit(id)
                connection.respond(
                    request,
                    TransportJson.instance.encodeToString(
                        TransferCommitReply.serializer(),
                        reply
                    ).toByteArray(Charsets.UTF_8)
                )
                true
            }
            OmniTransferCapabilities.CANCEL -> {
                val id = request.payload.toString(Charsets.UTF_8)
                cancel(id)
                connection.respondUtf8(request, "{\"accepted\":true}")
                true
            }
            else -> false
        }
    }

    private fun handleOpen(payload: ByteArray): TransferOpenReply = synchronized(lock) {
        val manifest = runCatching {
            TransportJson.instance.decodeFromString(
                TransferManifest.serializer(),
                payload.toString(Charsets.UTF_8)
            )
        }.getOrElse {
            return TransferOpenReply(false, code = "invalid_manifest", message = it.message)
        }

        if (!SAFE_ID.matches(manifest.transferId)) {
            return TransferOpenReply(false, code = "invalid_transfer_id")
        }
        if (manifest.totalBytes !in 0..policy.maxFileBytes) {
            return TransferOpenReply(false, code = "size_rejected")
        }
        if (manifest.chunkBytes !in 1..policy.maxChunkBytes) {
            return TransferOpenReply(false, code = "chunk_size_rejected")
        }
        if (!SHA256.matches(manifest.sha256.lowercase())) {
            return TransferOpenReply(false, code = "invalid_sha256")
        }

        manifests[manifest.transferId] = manifest
        val partial = partialFile(manifest.transferId)
        val current = partial.takeIf(File::exists)?.length()?.coerceAtMost(manifest.totalBytes) ?: 0L
        if (partial.exists() && partial.length() > manifest.totalBytes) {
            partial.delete()
            return TransferOpenReply(true, nextOffset = 0)
        }
        TransferOpenReply(true, nextOffset = current)
    }

    private fun handleChunk(request: TransportMessage): TransferChunkReply = synchronized(lock) {
        val id = request.metadata["transferId"].orEmpty()
        val manifest = manifests[id]
            ?: return TransferChunkReply(false, 0, "unknown_transfer")
        val offset = request.metadata["offset"]?.toLongOrNull()
            ?: return TransferChunkReply(false, 0, "invalid_offset")
        if (request.payload.size > policy.maxChunkBytes) {
            return TransferChunkReply(false, offset, "chunk_too_large")
        }
        val expectedChunkHash = request.metadata["chunkSha256"].orEmpty().lowercase()
        if (!SHA256.matches(expectedChunkHash) || sha256(request.payload) != expectedChunkHash) {
            return TransferChunkReply(false, offset, "chunk_hash_mismatch")
        }

        val partial = partialFile(id)
        partial.parentFile?.mkdirs()
        val current = partial.takeIf(File::exists)?.length() ?: 0L
        if (offset != current) {
            return TransferChunkReply(false, current, "resume_offset_mismatch")
        }
        if (current + request.payload.size > manifest.totalBytes) {
            return TransferChunkReply(false, current, "size_overflow")
        }

        RandomAccessFile(partial, "rw").use { out ->
            out.seek(current)
            out.write(request.payload)
            out.fd.sync()
        }
        TransferChunkReply(true, partial.length())
    }

    private fun handleCommit(transferId: String): TransferCommitReply = synchronized(lock) {
        val manifest = manifests[transferId]
            ?: return TransferCommitReply(false, code = "unknown_transfer")
        val partial = partialFile(transferId)
        if (!partial.isFile || partial.length() != manifest.totalBytes) {
            return TransferCommitReply(false, code = "incomplete_transfer")
        }
        if (sha256(partial) != manifest.sha256.lowercase()) {
            return TransferCommitReply(false, code = "file_hash_mismatch")
        }

        val finalFile = File(rootDir, transferId + "-" + safeFileName(manifest.fileName))
        if (finalFile.exists()) finalFile.delete()
        if (!partial.renameTo(finalFile)) {
            partial.copyTo(finalFile, overwrite = true)
            partial.delete()
        }
        manifests.remove(transferId)
        TransferCommitReply(true, storedPath = finalFile.absolutePath)
    }

    private fun cancel(transferId: String) = synchronized(lock) {
        manifests.remove(transferId)
        partialFile(transferId).delete()
    }

    private fun partialFile(id: String): File = File(rootDir, "$id.part")

    private fun safeFileName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "payload.bin" }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{8,128}")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}
