package com.omnilink.transport

/**
 * Safe defaults for common pairings. Applications may define narrower policies.
 */
object PeerTrustProfiles {

    fun chatSandbox(candidate: PeerCandidate): PeerTrustRecord =
        PeerTrustRecord(
            peerId = candidate.peerId,
            publicKeySha256 = candidate.publicKeySha256,
            trustLevel = TransportTrustLevel.PAIRED,
            inboundCapabilities = setOf(
                "chat.*",
                "search.*",
                "summarize.*",
                "translate.*",
                "extract.*"
            ),
            outboundCapabilities = setOf(
                "chat.*",
                "search.*",
                "summarize.*",
                "translate.*",
                "extract.*"
            ),
            expectedPlatformSignerSha256 = candidate.platformSignerSha256,
            notes = "Chat-only sandbox pairing"
        )

    fun firstParty(
        candidate: PeerCandidate,
        inboundCapabilities: Set<String> = setOf("*"),
        outboundCapabilities: Set<String> = setOf("*")
    ): PeerTrustRecord =
        PeerTrustRecord(
            peerId = candidate.peerId,
            publicKeySha256 = candidate.publicKeySha256,
            trustLevel = TransportTrustLevel.FIRST_PARTY,
            inboundCapabilities = inboundCapabilities,
            outboundCapabilities = outboundCapabilities,
            expectedPlatformSignerSha256 = candidate.platformSignerSha256,
            notes = "Explicitly paired first-party Omni peer"
        )
}
