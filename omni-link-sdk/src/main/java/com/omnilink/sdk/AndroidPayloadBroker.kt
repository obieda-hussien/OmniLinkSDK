package com.omnilink.sdk

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Android same-device data plane for large payloads.
 *
 * Binder carries only [PayloadDescriptor] metadata. The bytes themselves are streamed through a
 * content URI/file descriptor so images, videos, logs and archives never need to fit in Binder's
 * transaction buffer or application heap.
 */
object AndroidPayloadBroker {
    private const val DEFAULT_COPY_BUFFER_BYTES = 256 * 1024
    const val DEFAULT_MAX_PAYLOAD_BYTES: Long = 16L * 1024 * 1024 * 1024

    fun openReadOnly(
        context: Context,
        descriptor: PayloadDescriptor,
        expectedAuthority: String? = null
    ): ParcelFileDescriptor {
        require(descriptor.transport == PayloadTransport.CONTENT_URI) {
            "openReadOnly requires CONTENT_URI transport"
        }
        val uri = checkedUri(descriptor, expectedAuthority)
        return requireNotNull(
            context.contentResolver.openFileDescriptor(uri, "r")
        ) { "Unable to open payload URI" }
    }

    suspend fun copyContentUri(
        context: Context,
        descriptor: PayloadDescriptor,
        destination: File,
        maxBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES,
        expectedAuthority: String? = null
    ): Long = withContext(Dispatchers.IO) {
        require(descriptor.transport == PayloadTransport.CONTENT_URI) {
            "copyContentUri requires CONTENT_URI transport"
        }
        require(descriptor.lengthBytes in 0..maxBytes) { "Payload size exceeds policy" }

        val uri = checkedUri(descriptor, expectedAuthority)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        val parent = destination.absoluteFile.parentFile ?: error("Destination has no parent")
        require(parent.isDirectory || parent.mkdirs()) { "Unable to create payload destination directory" }
        // A unique same-directory temporary file prevents concurrent copies sharing one .part.
        val temp = File.createTempFile("omnilink-", ".part", parent)

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open payload URI" }
                FileOutputStream(temp).use { fileOutput ->
                    val output = fileOutput.buffered(DEFAULT_COPY_BUFFER_BYTES)
                    val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        copied += read
                        require(copied <= maxBytes && copied <= descriptor.lengthBytes) {
                            "Payload exceeded declared/policy size"
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            require(copied == descriptor.lengthBytes) {
                "Payload length mismatch: expected=${descriptor.lengthBytes}, actual=$copied"
            }

            descriptor.sha256?.let { expected ->
                val actual = digest.digest().joinToString("") {
                    "%02x".format(it.toInt() and 0xff)
                }
                require(actual.equals(expected.replace(":", ""), ignoreCase = true)) {
                    "Payload SHA-256 mismatch"
                }
            }

            // POSIX rename in the same directory atomically replaces the old destination.
            // If it fails, the old file remains intact and the temporary file is removed.
            Os.rename(temp.absolutePath, destination.absolutePath)
            copied
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun checkedUri(descriptor: PayloadDescriptor, expectedAuthority: String?): Uri {
        val uri = Uri.parse(requireNotNull(descriptor.uri) { "CONTENT_URI payload is missing uri" })
        require(uri.scheme == "content" && !uri.isOpaque && !uri.authority.isNullOrBlank()) {
            "Payload must use a hierarchical content URI with a provider authority"
        }
        require(expectedAuthority == null || uri.authority == expectedAuthority) {
            "Payload URI authority does not match the approved provider"
        }
        return uri
    }
}

object AndroidSessionCapabilities {
    fun hello(
        maxInlineBytes: Int = OmniLinkConstants.DEFAULT_MAX_INLINE_JSON_BYTES
    ): OmniSessionHello = OmniSessionHello(
        supportedPayloadTransports = setOf(
            PayloadTransport.INLINE,
            PayloadTransport.FILE_DESCRIPTOR,
            PayloadTransport.PIPE,
            PayloadTransport.CONTENT_URI
        ),
        maxInlineBytes = maxInlineBytes,
        supportsResume = true,
        supportsMultiplexing = true,
        supportsControlLane = true
    )
}
