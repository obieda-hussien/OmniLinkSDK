package com.omnilink.transport

import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableFileTransferTest {

    @Test
    fun `large file is streamed and committed without loading whole payload into protocol frames`() = runBlocking {
        val serverIdentity = JvmEcSigningIdentity.generate("server", PeerRole.SERVICE)
        val clientIdentity = JvmEcSigningIdentity.generate("client", PeerRole.DESKTOP)
        val otherIdentity = JvmEcSigningIdentity.generate("other-client", PeerRole.DESKTOP)
        val capabilities = setOf("_transfer.*")

        val serverTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    peerId = clientIdentity.peerId,
                    publicKeySha256 = clientIdentity.publicKeySha256(),
                    trustLevel = TransportTrustLevel.FIRST_PARTY,
                    inboundCapabilities = capabilities,
                    outboundCapabilities = capabilities
                ),
                PeerTrustRecord(
                    peerId = otherIdentity.peerId,
                    publicKeySha256 = otherIdentity.publicKeySha256(),
                    trustLevel = TransportTrustLevel.FIRST_PARTY,
                    inboundCapabilities = capabilities,
                    outboundCapabilities = capabilities
                )
            )
        )
        val clientTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    peerId = serverIdentity.peerId,
                    publicKeySha256 = serverIdentity.publicKeySha256(),
                    trustLevel = TransportTrustLevel.FIRST_PARTY,
                    inboundCapabilities = capabilities,
                    outboundCapabilities = capabilities
                )
            )
        )

        val receiveDir = Files.createTempDirectory("omnilink-recv").toFile()
        val receiver = OmniFileTransferReceiver(
            receiveDir,
            TransferPolicy(maxChunkBytes = 128 * 1024)
        )
        val server = OmniTcpServer(
            serverIdentity,
            serverTrust,
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0
        )

        server.start(onSession = { raw ->
            val connection = OmniMultiplexedConnection(raw, this)
            while (connection.isOpen) {
                val request = withTimeout(10_000) { connection.incomingRequests.first() }
                if (!receiver.tryHandle(connection, request)) {
                    connection.respondUtf8(request, "{\"error\":\"unsupported\"}")
                }
                if (request.capability == OmniTransferCapabilities.COMMIT) break
            }
        })

        val clientRaw = OmniTcpClient.connect(
            "127.0.0.1",
            server.localPort,
            clientIdentity,
            clientTrust
        )
        val client = OmniMultiplexedConnection(clientRaw, this)
        val otherRaw = OmniTcpClient.connect(
            "127.0.0.1", server.localPort, otherIdentity, clientTrust
        )
        val otherClient = OmniMultiplexedConnection(otherRaw, this)

        val bytes = ByteArray(900_000) { index -> (index % 251).toByte() }
        val source = Files.createTempFile("omnilink-send", ".bin").toFile().apply {
            writeBytes(bytes)
        }
        val manifest = TransferManifest(
            transferId = "transfer-test-0001",
            fileName = "video.bin",
            totalBytes = source.length(),
            sha256 = sha256(bytes),
            chunkBytes = 128 * 1024
        )

        // A CANCEL name must not resolve outside the receiver's directory.
        val sentinel = java.io.File(receiveDir.parentFile, "omnilink-sentinel-${System.nanoTime()}.part")
        sentinel.writeText("preserve")
        val traversal = client.request(
            OmniTransferCapabilities.CANCEL,
            ("../" + sentinel.name.removeSuffix(".part")).toByteArray()
        )
        assertFalse(traversal.payload.toString(Charsets.UTF_8).contains("\"accepted\":true"))
        assertTrue(sentinel.readText() == "preserve")

        val openPayload = TransportJson.instance.encodeToString(
            TransferManifest.serializer(), manifest
        ).toByteArray()
        val firstOpen = client.request(OmniTransferCapabilities.OPEN, openPayload)
        assertTrue(TransportJson.instance.decodeFromString(
            TransferOpenReply.serializer(), firstOpen.payload.toString(Charsets.UTF_8)
        ).accepted)

        val conflicting = client.request(
            OmniTransferCapabilities.OPEN,
            TransportJson.instance.encodeToString(
                TransferManifest.serializer(), manifest.copy(fileName = "different.bin")
            ).toByteArray()
        )
        assertFalse(TransportJson.instance.decodeFromString(
            TransferOpenReply.serializer(), conflicting.payload.toString(Charsets.UTF_8)
        ).accepted)

        val otherCancel = otherClient.request(
            OmniTransferCapabilities.CANCEL, manifest.transferId.toByteArray()
        )
        assertFalse(otherCancel.payload.toString(Charsets.UTF_8).contains("\"accepted\":true"))
        val otherOpen = otherClient.request(OmniTransferCapabilities.OPEN, openPayload)
        assertFalse(TransportJson.instance.decodeFromString(
            TransferOpenReply.serializer(), otherOpen.payload.toString(Charsets.UTF_8)
        ).accepted)
        val otherCommit = otherClient.request(
            OmniTransferCapabilities.COMMIT, manifest.transferId.toByteArray()
        )
        assertFalse(TransportJson.instance.decodeFromString(
            TransferCommitReply.serializer(), otherCommit.payload.toString(Charsets.UTF_8)
        ).accepted)

        val result = OmniFileTransferSender(
            client,
            TransferPolicy(maxChunkBytes = 128 * 1024)
        ).sendFile(source, manifest)

        assertTrue(result.accepted)
        val stored = requireNotNull(result.storedPath).let { java.io.File(it) }
        assertArrayEquals(bytes, stored.readBytes())

        client.close()
        otherClient.close()
        server.close()
        sentinel.delete()
        source.delete()
        receiveDir.deleteRecursively()
        Unit
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
