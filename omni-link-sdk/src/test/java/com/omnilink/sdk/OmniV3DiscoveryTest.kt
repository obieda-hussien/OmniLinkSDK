package com.omnilink.sdk

import com.omnilink.sdk.trusted.VerifiedService
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniV3DiscoveryTest {
    private val hostSigner = "a".repeat(64)
    private val targetSigner = "b".repeat(64)
    private val host = "com.omni.dev"
    private val target = "com.other.player"
    private val registry = HostAppIdentityRegistry(host, setOf(hostSigner))
    private var currentTarget = targetSigner
    private val identitySource = InstalledIdentitySource { packageName ->
        when (packageName) {
            host -> InstalledAppIdentityFacts(host, 42, setOf(hostSigner), setOf(hostSigner), null)
            target -> InstalledAppIdentityFacts(target, 43, setOf(currentTarget),
                setOf(targetSigner, currentTarget), "com.android.vending")
            else -> null
        }
    }

    @Test fun `installed signers come from current APK and never from claimed installer or signer history`() {
        val resolver = HostVerifiedAppPairResolver(host, target, identitySource, registry, expectedHostUid = 42)
        assertEquals(setOf(targetSigner), resolver.resolve()?.second?.signerSha256)
        currentTarget = "c".repeat(64)
        assertEquals(setOf(currentTarget), resolver.resolve()?.second?.signerSha256)
        assertNull(HostVerifiedAppPairResolver(host, target, identitySource, registry, 99).resolve())
    }

    @Test fun `blocked or invisible target fails closed`() {
        val blocked = HostAppIdentityRegistry(host, setOf(hostSigner), blockedPackages = setOf(target))
        assertNull(HostVerifiedAppPairResolver(host, target, identitySource, blocked).resolve())
        assertNull(HostVerifiedAppPairResolver(host, "com.invisible", identitySource, registry).resolve())
    }

    private val verified = VerifiedService(
        packageName = target,
        serviceClassName = "com.other.player.Extension",
        signerSha256 = setOf(targetSigner),
        sameSignerAsHost = false,
        requiredPermission = "com.omni.permission.EXTENSION"
    )

    @Test fun `catalog derives trust from verified service and rejects malicious manifests`() {
        val manifest = CapabilityManifest(
            protocolVersion = OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
            sdkVersion = "3.0.0",
            minSupportedVersion = OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
            maxSupportedVersion = OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
            capabilities = listOf(CapabilityDescriptor("media.pause"))
        )
        val json = OmniJson.instance.encodeToString(manifest)
        val accepted = TrustedCapabilityCatalog.ingest(verified, json) as CapabilityCatalogResult.Accepted
        assertEquals(TrustTier.TRUSTED_PARTNER, accepted.nodes.single().trustTier)
        assertEquals(target, accepted.nodes.single().appPackage)
        assertEquals("media.pause", accepted.nodes.single().capability.name)
        assertEquals(CapabilityCatalogResult.Rejected("invalid_capabilities"),
            TrustedCapabilityCatalog.ingest(verified, OmniJson.instance.encodeToString(
                manifest.copy(capabilities = listOf(CapabilityDescriptor("media.pause"), CapabilityDescriptor("media.pause")))
            )))
        assertEquals(CapabilityCatalogResult.Rejected("incompatible_protocol"),
            TrustedCapabilityCatalog.ingest(verified, OmniJson.instance.encodeToString(
                manifest.copy(minSupportedVersion = 10, maxSupportedVersion = 11)
            )))
        assertEquals(CapabilityCatalogResult.Rejected("unverified_provider"),
            TrustedCapabilityCatalog.ingest(verified.copy(signerSha256 = emptySet()), json))
        assertTrue(TrustedCapabilityCatalog.ingest(verified, " ".repeat(TrustedCapabilityCatalog.MAX_MANIFEST_BYTES + 1))
            is CapabilityCatalogResult.Rejected)
    }
}
