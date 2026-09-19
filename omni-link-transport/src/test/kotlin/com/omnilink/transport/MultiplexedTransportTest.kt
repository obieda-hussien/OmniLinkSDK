package com.omnilink.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class MultiplexedTransportTest {

    @Test
    fun `multiple concurrent requests are correlated over one encrypted session`() = runBlocking {
        val serverIdentity = JvmEcSigningIdentity.generate("server", PeerRole.SERVICE)
        val clientIdentity = JvmEcSigningIdentity.generate("client", PeerRole.DESKTOP)

        val serverTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    clientIdentity.peerId,
                    clientIdentity.publicKeySha256(),
                    TransportTrustLevel.FIRST_PARTY,
                    inboundCapabilities = setOf("rpc.*"),
                    outboundCapabilities = setOf("rpc.*", "event.*")
                )
            )
        )
        val clientTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    serverIdentity.peerId,
                    serverIdentity.publicKeySha256(),
                    TransportTrustLevel.FIRST_PARTY,
                    inboundCapabilities = setOf("rpc.*", "event.*"),
                    outboundCapabilities = setOf("rpc.*")
                )
            )
        )

        val server = OmniTcpServer(
            serverIdentity,
            serverTrust,
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0
        )

        server.start(onSession = { raw ->
            val connection = OmniMultiplexedConnection(raw, this)
            repeat(2) {
                val request = withTimeout(5_000) {
                    connection.incomingRequests.first()
                }
                connection.respondUtf8(
                    request,
                    request.payload.toString(Charsets.UTF_8).uppercase()
                )
            }
            connection.emitEvent("event.ready", "ok".toByteArray())
        })

        val clientRaw = OmniTcpClient.connect(
            "127.0.0.1",
            server.localPort,
            clientIdentity,
            clientTrust
        )
        val client = OmniMultiplexedConnection(clientRaw, this)

        val a = async { client.requestUtf8("rpc.echo", "alpha") }
        val b = async { client.requestUtf8("rpc.echo", "beta") }

        val results = setOf(
            a.await().payload.toString(Charsets.UTF_8),
            b.await().payload.toString(Charsets.UTF_8)
        )
        assertEquals(setOf("ALPHA", "BETA"), results)

        val event = withTimeout(5_000) { client.events.first() }
        assertEquals("event.ready", event.capability)
        assertEquals("ok", event.payload.toString(Charsets.UTF_8))

        client.close()
        server.close()
    }
}
