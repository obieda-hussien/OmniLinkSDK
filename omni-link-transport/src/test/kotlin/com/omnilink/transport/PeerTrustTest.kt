package com.omnilink.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTrustTest {

    @Test
    fun `capability wildcards are directional`() {
        val record = PeerTrustRecord(
            peerId = "pc",
            publicKeySha256 = "aa",
            trustLevel = TransportTrustLevel.FIRST_PARTY,
            inboundCapabilities = setOf("chat.*", "build.read"),
            outboundCapabilities = setOf("reply.*")
        )

        assertTrue(record.permitsInbound("chat.ask"))
        assertTrue(record.permitsInbound("build.read"))
        assertFalse(record.permitsInbound("terminal.exec"))

        assertTrue(record.permitsOutbound("reply.answer"))
        assertFalse(record.permitsOutbound("chat.ask"))
    }

    @Test
    fun `expired record denies both directions`() {
        val record = PeerTrustRecord(
            peerId = "old",
            publicKeySha256 = "aa",
            trustLevel = TransportTrustLevel.PAIRED,
            inboundCapabilities = setOf("*"),
            outboundCapabilities = setOf("*"),
            expiresAtEpochMs = 1L
        )

        assertFalse(record.permitsInbound("anything"))
        assertFalse(record.permitsOutbound("anything"))
    }
}
