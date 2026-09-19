package com.omnilink.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ReliabilityTest {

    @Test
    fun `retry policy grows exponentially and respects maximum`() {
        val policy = RetryPolicy(
            initialDelayMillis = 100,
            maxDelayMillis = 1_000,
            multiplier = 2.0,
            jitterRatio = 0.0
        )

        assertEquals(100, policy.delayForAttempt(1))
        assertEquals(200, policy.delayForAttempt(2))
        assertEquals(400, policy.delayForAttempt(3))
        assertEquals(800, policy.delayForAttempt(4))
        assertEquals(1_000, policy.delayForAttempt(5))
        assertEquals(1_000, policy.delayForAttempt(20))
    }

    @Test
    fun `multiplexed ping receives authenticated pong without app handler`() = runBlocking {
        val serverIdentity = JvmEcSigningIdentity.generate("ping-server", PeerRole.SERVICE)
        val clientIdentity = JvmEcSigningIdentity.generate("ping-client", PeerRole.DESKTOP)

        val serverTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    peerId = clientIdentity.peerId,
                    publicKeySha256 = clientIdentity.publicKeySha256(),
                    trustLevel = TransportTrustLevel.PAIRED
                )
            )
        )
        val clientTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    peerId = serverIdentity.peerId,
                    publicKeySha256 = serverIdentity.publicKeySha256(),
                    trustLevel = TransportTrustLevel.PAIRED
                )
            )
        )

        val serverConnectionReady = CompletableDeferred<OmniMultiplexedConnection>()
        val server = OmniTcpServer(
            serverIdentity,
            serverTrust,
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0
        )
        server.start(onSession = { raw ->
            val mux = OmniMultiplexedConnection(raw, this)
            serverConnectionReady.complete(mux)
            mux.awaitClosed()
        })

        val clientRaw = OmniTcpClient.connect(
            "127.0.0.1",
            server.localPort,
            clientIdentity,
            clientTrust
        )
        val client = OmniMultiplexedConnection(clientRaw, this)

        withTimeout(5_000) { serverConnectionReady.await() }
        val rtt = withTimeout(5_000) { client.ping(2_000) }
        assertTrue(rtt >= 0)

        client.close()
        server.close()
    }

    @Test
    fun `reliable client reaches connected state and records heartbeat rtt`() = runBlocking {
        val serverIdentity = JvmEcSigningIdentity.generate("reliable-server", PeerRole.SERVICE)
        val clientIdentity = JvmEcSigningIdentity.generate("reliable-client", PeerRole.DESKTOP)

        val serverTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    peerId = clientIdentity.peerId,
                    publicKeySha256 = clientIdentity.publicKeySha256(),
                    trustLevel = TransportTrustLevel.PAIRED
                )
            )
        )
        val clientTrust = InMemoryPeerTrustStore(
            listOf(
                PeerTrustRecord(
                    peerId = serverIdentity.peerId,
                    publicKeySha256 = serverIdentity.publicKeySha256(),
                    trustLevel = TransportTrustLevel.PAIRED
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
            OmniMultiplexedConnection(raw, this).awaitClosed()
        })

        val client = OmniReliableClient(
            host = "127.0.0.1",
            port = server.localPort,
            identity = clientIdentity,
            trustStore = clientTrust,
            heartbeatPolicy = HeartbeatPolicy(
                intervalMillis = 25,
                timeoutMillis = 1_000
            ),
            retryPolicy = RetryPolicy(
                initialDelayMillis = 10,
                maxDelayMillis = 50,
                jitterRatio = 0.0
            )
        )

        client.start()

        val state = withTimeout(5_000) {
            client.state
                .filterIsInstance<ReliableConnectionState.Connected>()
                .first { it.lastRoundTripMillis != null }
        }

        assertEquals(serverIdentity.peerId, state.peerId)
        assertTrue(state.lastRoundTripMillis!! >= 0)

        client.close()
        server.close()
        delay(10)
    }
}
