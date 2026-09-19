package com.omnilink.sdk

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
        descriptor: PayloadDescriptor
    ): ParcelFileDescriptor {
        require(descriptor.transport == PayloadTransport.CONTENT_URI) {
            "openReadOnly requires CONTENT_URI transport"
        }
        val uri = Uri.parse(requireNotNull(descriptor.uri) { "CONTENT_URI payload is missing uri" })
        return requireNotNull(
            context.contentResolver.openFileDescriptor(uri, "r")
        ) { "Unable to open payload URI" }
    }

    suspend fun copyContentUri(
        context: Context,
        descriptor: PayloadDescriptor,
        destination: File,
        maxBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES
    ): Long = withContext(Dispatchers.IO) {
        require(descriptor.transport == PayloadTransport.CONTENT_URI) {
            "copyContentUri requires CONTENT_URI transport"
        }
        require(descriptor.lengthBytes in 0..maxBytes) { "Payload size exceeds policy" }

        val uri = Uri.parse(requireNotNull(descriptor.uri) { "CONTENT_URI payload is missing uri" })
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile ?: File("."), destination.name + ".part")

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open payload URI" }
                temp.outputStream().buffered(DEFAULT_COPY_BUFFER_BYTES).use { output ->
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

            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            copied
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
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
