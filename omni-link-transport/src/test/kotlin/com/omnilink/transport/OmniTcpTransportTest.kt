package com.omnilink.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class OmniTcpTransportTest {

    @Test
    fun `removing or changing peer trust closes already authenticated sessions`() = runBlocking {
        val serverIdentity = JvmEcSigningIdentity.generate("revocation-server", PeerRole.SERVICE)
        val clientIdentity = JvmEcSigningIdentity.generate("revocation-client", PeerRole.DESKTOP)
        val serverRecord = PeerTrustRecord(
            clientIdentity.peerId, clientIdentity.publicKeySha256(),
            TransportTrustLevel.FIRST_PARTY,
            inboundCapabilities = setOf("chat.*"), outboundCapabilities = setOf("chat.*")
        )
        val clientRecord = PeerTrustRecord(
            serverIdentity.peerId, serverIdentity.publicKeySha256(),
            TransportTrustLevel.FIRST_PARTY,
            inboundCapabilities = setOf("chat.*"), outboundCapabilities = setOf("chat.*")
        )
        val serverTrust = InMemoryPeerTrustStore(listOf(serverRecord))
        val clientTrust = InMemoryPeerTrustStore(listOf(clientRecord))
        val captured = CompletableDeferred<SecureTransportSession>()
        val finish = CompletableDeferred<Unit>()
        val server = OmniTcpServer(serverIdentity, serverTrust,
            bindAddress = InetAddress.getLoopbackAddress(), port = 0)
        server.start(onSession = { session -> captured.complete(session); finish.await() })
        val client = OmniTcpClient.connect("127.0.0.1", server.localPort, clientIdentity, clientTrust)
        val serverSession = withTimeout(5_000) { captured.await() }

        assertTrue(serverSession.isOpen)
        serverTrust.remove(clientIdentity.peerId)
        assertFalse(serverSession.isOpen)
        assertTrue(client.isOpen)

        clientTrust.put(clientRecord.copy(outboundCapabilities = emptySet()))
        assertFalse(client.isOpen)
        finish.complete(Unit)
        client.close()
        server.close()
    }

    @Test
    fun `trusted peers exchange encrypted capability-scoped messages bidirectionally`() = runBlocking {
        val serverIdentity = JvmEcSigningIdentity.generate("android-device", PeerRole.ANDROID)
        val clientIdentity = JvmEcSigningIdentity.generate("desktop-device", PeerRole.DESKTOP)

        val serverTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    peerId = clientIdentity.peerId,
                    publicKeySha256 = clientIdentity.publicKeySha256(),
                    trustLevel = TransportTrustLevel.FIRST_PARTY,
                    inboundCapabilities = setOf("chat.*"),
                    outboundCapabilities = setOf("reply.*")
                )
            )
        )
        val clientTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    peerId = serverIdentity.peerId,
                    publicKeySha256 = serverIdentity.publicKeySha256(),
                    trustLevel = TransportTrustLevel.FIRST_PARTY,
                    inboundCapabilities = setOf("reply.*"),
                    outboundCapabilities = setOf("chat.*")
                )
            )
        )

        val completed = CompletableDeferred<Unit>()
        val errors = mutableListOf<Throwable>()
        val server = OmniTcpServer(
            identity = serverIdentity,
            trustStore = serverTrust,
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0
        )

        server.start(
            onSession = { session ->
                val request = session.receive()
                assertEquals("chat.ask", request.capability)
                assertEquals("hello", request.payload.toString(Charsets.UTF_8))

                session.sendUtf8(
                    capability = "reply.answer",
                    text = "world",
                    type = TransportMessageType.RESPONSE,
                    correlationId = request.correlationId
                )
                session.close()
                completed.complete(Unit)
            },
            onError = { errors += it }
        )

        val client = OmniTcpClient.connect(
            host = "127.0.0.1",
            port = server.localPort,
            identity = clientIdentity,
            trustStore = clientTrust
        )

        assertEquals("android-device", client.remotePeerId)
        assertTrue(client.sessionId.isNotBlank())

        client.sendUtf8(
            capability = "chat.ask",
            text = "hello",
            correlationId = "corr-1"
        )

        val reply = client.receive()
        assertEquals(TransportMessageType.RESPONSE, reply.type)
        assertEquals("reply.answer", reply.capability)
        assertEquals("corr-1", reply.correlationId)
        assertEquals("world", reply.payload.toString(Charsets.UTF_8))

        withTimeout(5_000) { completed.await() }
        client.close()
        server.close()

        if (errors.isNotEmpty()) {
            throw AssertionError("Server error", errors.first())
        }
    }

    @Test
    fun `outbound capability policy blocks unauthorized message before network write`() = runBlocking {
        val serverIdentity = JvmEcSigningIdentity.generate("server", PeerRole.SERVICE)
        val clientIdentity = JvmEcSigningIdentity.generate("client", PeerRole.DESKTOP)

        val serverTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    clientIdentity.peerId,
                    clientIdentity.publicKeySha256(),
                    TransportTrustLevel.PAIRED,
                    inboundCapabilities = setOf("chat.*"),
                    outboundCapabilities = setOf("reply.*")
                )
            )
        )
        val clientTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    serverIdentity.peerId,
                    serverIdentity.publicKeySha256(),
                    TransportTrustLevel.PAIRED,
                    inboundCapabilities = setOf("reply.*"),
                    outboundCapabilities = setOf("chat.*")
                )
            )
        )

        val server = OmniTcpServer(
            serverIdentity,
            serverTrust,
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0
        )
        server.start(onSession = { session ->
            try {
                session.receive()
            } catch (_: Exception) {
            } finally {
                session.close()
            }
        })

        val client = OmniTcpClient.connect(
            "127.0.0.1",
            server.localPort,
            clientIdentity,
            clientTrust
        )

        var denied = false
        try {
            client.sendUtf8("terminal.exec", "whoami")
        } catch (_: TransportAuthorizationException) {
            denied = true
        }

        assertTrue(denied)
        client.close()
        server.close()
    }

    @Test
    fun `strict server rejects unknown peer even when crypto is otherwise valid`() = runBlocking {
        val serverIdentity = JvmEcSigningIdentity.generate("server", PeerRole.SERVICE)
        val clientIdentity = JvmEcSigningIdentity.generate("unknown-client", PeerRole.DESKTOP)

        val serverTrust = InMemoryPeerTrustStore()
        val clientTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    serverIdentity.peerId,
                    serverIdentity.publicKeySha256(),
                    TransportTrustLevel.PAIRED,
                    inboundCapabilities = setOf("*"),
                    outboundCapabilities = setOf("*")
                )
            )
        )

        val server = OmniTcpServer(
            serverIdentity,
            serverTrust,
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0
        )
        server.start(onSession = { it.close() })

        var rejected = false
        try {
            OmniTcpClient.connect(
                "127.0.0.1",
                server.localPort,
                clientIdentity,
                clientTrust
            ).close()
        } catch (_: HandshakeRejectedException) {
            rejected = true
        } catch (_: PeerAuthenticationException) {
            rejected = true
        }

        assertTrue(rejected)
        server.close()
    }
}
