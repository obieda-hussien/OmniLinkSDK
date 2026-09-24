package com.omnilink.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrustProfilesV2Test {
    @Test
    fun `first party requires host-owned signer and explicit capabilities`() {
        val candidate = PeerCandidate(
            peerId = "android-1",
            role = PeerRole.ANDROID,
            publicKeySha256 = "aa",
            platformSignerSha256 = setOf("11:22"),
            pairingCode = "123456"
        )

        val record = PeerTrustProfiles.firstParty(
            candidate = candidate,
            expectedPlatformSignerSha256 = setOf("1122"),
            inboundCapabilities = setOf("workspace.memory.*"),
            outboundCapabilities = setOf("ide.*"),
            expectedTransportPublicKeySha256 = "aa"
        )

        assertEquals(TransportTrustLevel.FIRST_PARTY, record.trustLevel)
        assertEquals(setOf("1122"), record.expectedPlatformSignerSha256)
    }

    @Test
    fun `first party rejects self asserted unknown signer`() {
        val candidate = PeerCandidate(
            peerId = "android-1",
            role = PeerRole.ANDROID,
            publicKeySha256 = "aa",
            platformSignerSha256 = setOf("deadbeef"),
            pairingCode = "123456"
        )

        assertThrows(IllegalArgumentException::class.java) {
            PeerTrustProfiles.firstParty(
                candidate = candidate,
                expectedPlatformSignerSha256 = setOf("cafebabe"),
                inboundCapabilities = setOf("workspace.memory.read"),
                outboundCapabilities = setOf("ide.health"),
                expectedTransportPublicKeySha256 = "aa"
            )
        }
    }

    @Test
    fun `claiming an approved APK signer cannot promote an unpinned transport identity`() {
        val candidate = PeerCandidate(
            peerId = "spoofed-android", role = PeerRole.ANDROID,
            publicKeySha256 = "deadbeef", platformSignerSha256 = setOf("1122"),
            pairingCode = "123456"
        )
        assertThrows(IllegalArgumentException::class.java) {
            PeerTrustProfiles.firstParty(
                candidate, setOf("1122"), setOf("workspace.*"), setOf("ide.*")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerTrustProfiles.firstParty(
                candidate, setOf("1122"), setOf("workspace.*"), setOf("ide.*"),
                expectedTransportPublicKeySha256 = "cafebabe"
            )
        }
    }
}
