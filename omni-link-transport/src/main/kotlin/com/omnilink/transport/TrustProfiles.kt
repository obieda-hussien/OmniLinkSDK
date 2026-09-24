package com.omnilink.transport

/**
 * Safe defaults for common pairings.
 *
 * v2 deliberately refuses to derive FIRST_PARTY authority from claims supplied by the candidate.
 * The host must provide the signer/key material it already trusts and explicit directional ACLs.
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
            expectedPlatformSignerSha256 = emptySet(),
            notes = "Chat-only sandbox pairing; platform signer is not treated as trusted identity"
        )

    fun firstParty(
        candidate: PeerCandidate,
        expectedPlatformSignerSha256: Set<String>,
        inboundCapabilities: Set<String>,
        outboundCapabilities: Set<String>,
        // Source-compatible default fails closed for old callers. Supply a previously pinned
        // transport identity verified by the host, not a key copied from this candidate.
        expectedTransportPublicKeySha256: String? = null
    ): PeerTrustRecord {
        require(!expectedTransportPublicKeySha256.isNullOrBlank()) {
            "FIRST_PARTY requires a host-owned, previously pinned transport public key"
        }
        require(constantTimeEqualsHex(expectedTransportPublicKeySha256, candidate.publicKeySha256)) {
            "Candidate transport key does not match the host-owned first-party pin"
        }
        require(expectedPlatformSignerSha256.isNotEmpty()) {
            "FIRST_PARTY requires host-owned expected platform signer fingerprints"
        }
        require(inboundCapabilities.isNotEmpty()) {
            "FIRST_PARTY inbound capabilities must be explicit"
        }
        require(outboundCapabilities.isNotEmpty()) {
            "FIRST_PARTY outbound capabilities must be explicit"
        }

        val expected = expectedPlatformSignerSha256.map(::normalizeFingerprint).toSet()
        val actual = candidate.platformSignerSha256.map(::normalizeFingerprint).toSet()
        require(actual.any(expected::contains)) {
            "Candidate platform signer does not match the host-owned first-party signer allowlist"
        }

        return PeerTrustRecord(
            peerId = candidate.peerId,
            publicKeySha256 = candidate.publicKeySha256,
            trustLevel = TransportTrustLevel.FIRST_PARTY,
            inboundCapabilities = inboundCapabilities,
            outboundCapabilities = outboundCapabilities,
            expectedPlatformSignerSha256 = expected,
            notes = "Host-pinned transport identity; remotely asserted APK signer is metadata, not independent verification"
        )
    }

    fun trustedDesktop(
        candidate: PeerCandidate,
        expectedPublicKeySha256: String,
        inboundCapabilities: Set<String>,
        outboundCapabilities: Set<String>
    ): PeerTrustRecord {
        require(constantTimeEqualsHex(expectedPublicKeySha256, candidate.publicKeySha256)) {
            "Desktop transport key does not match the host-owned pinned identity"
        }
        require(inboundCapabilities.isNotEmpty() && outboundCapabilities.isNotEmpty()) {
            "Trusted desktop capabilities must be explicit in both directions"
        }
        return PeerTrustRecord(
            peerId = candidate.peerId,
            publicKeySha256 = candidate.publicKeySha256,
            trustLevel = TransportTrustLevel.FIRST_PARTY,
            inboundCapabilities = inboundCapabilities,
            outboundCapabilities = outboundCapabilities,
            expectedPlatformSignerSha256 = emptySet(),
            notes = "Pinned trusted desktop identity with explicit directional ACLs"
        )
    }
}
