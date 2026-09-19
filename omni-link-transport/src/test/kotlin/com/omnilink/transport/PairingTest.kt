package com.omnilink.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetAddress

class PairingTest {

    @Test
    fun `unknown peers can be explicitly paired into chat sandbox and persist trust`() = runBlocking {
        val serverIdentity = JvmEcSigningIdentity.generate("android", PeerRole.ANDROID)
        val clientIdentity = JvmEcSigningIdentity.generate("desktop", PeerRole.DESKTOP)

        val serverTrust = InMemoryPeerTrustStore()
        val clientTrust = InMemoryPeerTrustStore()
        val done = CompletableDeferred<Unit>()

        val server = OmniTcpServer(
            identity = serverIdentity,
            trustStore = serverTrust,
            admissionHandler = PeerAdmissionHandler { candidate ->
                PeerTrustProfiles.chatSandbox(candidate)
            },
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0
        )

        server.start(onSession = { session ->
            val message = session.receive()
            assertEquals("chat.ask", message.capability)
            session.sendUtf8("chat.answer", "paired")
            session.close()
            done.complete(Unit)
        })

        val client = OmniTcpClient.connect(
            host = "127.0.0.1",
            port = server.localPort,
            identity = clientIdentity,
            trustStore = clientTrust,
            admissionHandler = PeerAdmissionHandler { candidate ->
                PeerTrustProfiles.chatSandbox(candidate)
            }
        )

        client.sendUtf8("chat.ask", "hello")
        assertEquals("paired", client.receive().payload.toString(Charsets.UTF_8))
        withTimeout(5_000) { done.await() }

        assertNotNull(serverTrust.get(clientIdentity.peerId))
        assertNotNull(clientTrust.get(serverIdentity.peerId))

        client.close()
        server.close()
    }
}
