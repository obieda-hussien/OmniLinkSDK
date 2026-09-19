package com.omnilink.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustProtocolTest {

    @Test
    fun `first party can invoke default bidirectional capability`() {
        val principal = TrustPrincipal(
            tier = TrustTier.FIRST_PARTY,
            packageNames = listOf("com.omni.firstparty")
        )
        val capability = CapabilityDescriptor(name = "notes.search")

        assertTrue(
            CapabilityTrustPolicy.isAllowed(
                principal,
                capability,
                InvocationDirection.OMNI_TO_APP
            )
        )
        assertTrue(
            CapabilityTrustPolicy.isAllowed(
                principal,
                capability,
                InvocationDirection.APP_TO_OMNI
            )
        )
    }

    @Test
    fun `untrusted caller is denied first party capability`() {
        val principal = TrustPrincipal(
            tier = TrustTier.UNTRUSTED,
            packageNames = listOf("com.example.foreign")
        )

        assertFalse(
            CapabilityTrustPolicy.isAllowed(
                principal,
                CapabilityDescriptor(name = "memory.read"),
                InvocationDirection.APP_TO_OMNI
            )
        )
    }

    @Test
    fun `trusted partner only receives explicitly allowlisted capability`() {
        val principal = TrustPrincipal(
            tier = TrustTier.TRUSTED_PARTNER,
            packageNames = listOf("com.partner.app"),
            allowedCapabilities = setOf("media.pause")
        )

        val allowed = CapabilityDescriptor(
            name = "media.pause",
            requiredTrustTier = TrustTier.TRUSTED_PARTNER
        )
        val denied = CapabilityDescriptor(
            name = "memory.read",
            requiredTrustTier = TrustTier.TRUSTED_PARTNER
        )

        assertTrue(
            CapabilityTrustPolicy.isAllowed(
                principal,
                allowed,
                InvocationDirection.APP_TO_OMNI
            )
        )
        assertFalse(
            CapabilityTrustPolicy.isAllowed(
                principal,
                denied,
                InvocationDirection.APP_TO_OMNI
            )
        )
    }

    @Test
    fun `one way capability rejects reverse direction`() {
        val principal = TrustPrincipal(
            tier = TrustTier.FIRST_PARTY,
            packageNames = listOf("com.omni.workspace")
        )
        val capability = CapabilityDescriptor(
            name = "foreign.control",
            communicationDirection = CommunicationDirection.OMNI_TO_APP_ONLY
        )

        assertTrue(
            CapabilityTrustPolicy.isAllowed(
                principal,
                capability,
                InvocationDirection.OMNI_TO_APP
            )
        )
        assertFalse(
            CapabilityTrustPolicy.isAllowed(
                principal,
                capability,
                InvocationDirection.APP_TO_OMNI
            )
        )
    }
}
