package com.omnilink.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityRegistryTest {
    private val hostSigner = "a".repeat(64)
    private val partnerSigner = "b".repeat(64)
    private val rotatedSigner = "c".repeat(64)

    private fun facts(
        packageName: String = "com.example.app",
        current: Set<String> = setOf(partnerSigner),
        history: Set<String> = current,
        installer: String? = null
    ) = InstalledAppIdentityFacts(
        packageName = packageName,
        uid = 12345,
        currentSignerSha256 = current,
        signerHistorySha256 = history,
        installerPackageName = installer
    )

    @Test
    fun `host itself becomes core only when both package and signer match`() {
        val registry = HostAppIdentityRegistry("com.omni.host", setOf(hostSigner))

        val validHost = registry.classify(facts("com.omni.host", setOf(hostSigner)))
        val sameSignerOtherPackage = registry.classify(facts(current = setOf(hostSigner)))
        val samePackageWrongSigner = registry.classify(facts("com.omni.host"))

        assertEquals(AppClassification.OMNI_CORE, validHost.classification)
        assertTrue(validHost.cryptographicIdentityMatched)
        assertEquals(AppClassification.UNKNOWN_APP, sameSignerOtherPackage.classification)
        assertEquals(AppClassification.UNKNOWN_APP, samePackageWrongSigner.classification)
    }

    @Test
    fun `official app requires exact host registry package and signer match`() {
        val registry = HostAppIdentityRegistry(
            hostPackageName = "com.omni.host",
            hostSignerSha256 = setOf(hostSigner),
            registrations = listOf(
                HostAppRegistration(
                    packageName = "com.omni.notes",
                    signerSha256 = setOf(partnerSigner),
                    classification = AppClassification.OFFICIAL_OMNI_APP,
                    roles = setOf(OmniApplicationRole.CAPABILITY_PROVIDER),
                    capabilityCeiling = setOf("notes.read")
                )
            )
        )

        val genuine = registry.classify(facts("com.omni.notes"))
        val packageSpoof = registry.classify(facts("com.omni.notes", setOf(rotatedSigner)))

        assertEquals(AppClassification.OFFICIAL_OMNI_APP, genuine.classification)
        assertEquals(setOf("notes.read"), genuine.capabilityCeiling)
        assertTrue(genuine.cryptographicIdentityMatched)
        assertEquals(AppClassification.UNKNOWN_APP, packageSpoof.classification)
        assertTrue(packageSpoof.capabilityCeiling.isEmpty())
    }

    @Test
    fun `installer source is provenance and does not establish official identity`() {
        val registry = HostAppIdentityRegistry("com.omni.host", setOf(hostSigner))

        val playInstall = registry.classify(
            facts(current = setOf(rotatedSigner), installer = "com.android.vending")
        )

        assertEquals(AppClassification.UNKNOWN_APP, playInstall.classification)
        assertEquals("com.android.vending", playInstall.installerPackageName)
        assertFalse(playInstall.cryptographicIdentityMatched)
    }

    @Test
    fun `registered signing history permits package certificate rotation`() {
        val registry = HostAppIdentityRegistry(
            hostPackageName = "com.omni.host",
            hostSignerSha256 = setOf(hostSigner),
            registrations = listOf(
                HostAppRegistration(
                    packageName = "com.omni.notes",
                    signerSha256 = setOf(partnerSigner),
                    classification = AppClassification.OFFICIAL_OMNI_APP
                )
            )
        )

        val rotated = registry.classify(
            facts("com.omni.notes", current = setOf(rotatedSigner), history = setOf(partnerSigner, rotatedSigner))
        )

        assertEquals(AppClassification.OFFICIAL_OMNI_APP, rotated.classification)
        assertTrue(rotated.cryptographicIdentityMatched)
    }

    @Test
    fun `blocked package overrides official registration`() {
        val registry = HostAppIdentityRegistry(
            hostPackageName = "com.omni.host",
            hostSignerSha256 = setOf(hostSigner),
            registrations = listOf(
                HostAppRegistration(
                    packageName = "com.omni.notes",
                    signerSha256 = setOf(partnerSigner),
                    classification = AppClassification.OFFICIAL_OMNI_APP
                )
            ),
            blockedPackages = setOf("com.omni.notes")
        )

        assertEquals(AppClassification.BLOCKED_APP, registry.classify(facts("com.omni.notes")).classification)
    }

    @Test
    fun `user approval is exact signer bound and separate from official registry`() {
        val registry = HostAppIdentityRegistry(
            hostPackageName = "com.omni.host",
            hostSignerSha256 = setOf(hostSigner),
            userApprovedApps = listOf(
                UserApprovedApp(
                    packageName = "com.example.app",
                    signerSha256 = setOf(partnerSigner),
                    capabilityCeiling = setOf("share.text")
                )
            )
        )

        val approved = registry.classify(facts())
        val replacedPackage = registry.classify(facts(current = setOf(rotatedSigner)))

        assertEquals(AppClassification.USER_APPROVED_APP, approved.classification)
        assertEquals(setOf("share.text"), approved.capabilityCeiling)
        assertEquals(AppClassification.UNKNOWN_APP, replacedPackage.classification)
    }
}
