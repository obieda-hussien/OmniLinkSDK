package com.omnilink.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportIdentityTest {

    @Test
    fun `JVM identity survives export import and signs correctly`() {
        val identity = JvmEcSigningIdentity.generate("desktop-1", PeerRole.DESKTOP)
        val restored = JvmEcSigningIdentity.import(identity.export())
        val payload = "omnilink-handshake".toByteArray()

        val signature = restored.sign(payload)

        assertArrayEquals(identity.publicKeyEncoded, restored.publicKeyEncoded)
        assertTrue(verifySignature(restored.publicKeyEncoded, payload, signature))
        assertFalse(
            verifySignature(
                restored.publicKeyEncoded,
                "tampered".toByteArray(),
                signature
            )
        )
    }
}
