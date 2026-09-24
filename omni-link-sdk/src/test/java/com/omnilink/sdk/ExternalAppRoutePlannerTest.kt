package com.omnilink.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAppRoutePlannerTest {
    private fun adapter(
        kind: ExternalAdapterKind,
        authorization: AdapterAuthorization = AdapterAuthorization.GRANTED,
        available: Boolean = true,
        semantic: Boolean = true,
        destructive: Boolean = false,
        packageName: String = "example.provider",
        operations: Set<String> = setOf("files.read")
    ) = ExternalAppAdapterDescriptor(
        packageName = packageName,
        kind = kind,
        available = available,
        semantic = semantic,
        destructive = destructive,
        operations = operations,
        authorization = authorization
    )

    private val request = ExternalAppRouteRequest(
        packageName = "example.provider",
        operation = "files.read"
    )

    @Test
    fun `selects authorized semantic adapter and ignores other packages and operations`() {
        val native = adapter(ExternalAdapterKind.NATIVE_API)
        val unrelated = adapter(
            ExternalAdapterKind.INTENT_OR_DEEP_LINK,
            packageName = "other.package"
        )
        val wrongOperation = adapter(
            ExternalAdapterKind.INTENT_OR_DEEP_LINK,
            operations = setOf("files.write")
        )

        val decision = ExternalAppRoutePlanner.plan(request, listOf(wrongOperation, unrelated, native))

        assertEquals(native, decision.selected)
        assertEquals("selected_best_authorized_route", decision.rationale)
    }

    @Test
    fun `does not fall back to a more privileged adapter after permission denial`() {
        val deniedNative = adapter(
            ExternalAdapterKind.NATIVE_API,
            authorization = AdapterAuthorization.DENIED
        )
        val root = adapter(
            ExternalAdapterKind.ROOT,
            semantic = false
        )

        val decision = ExternalAppRoutePlanner.plan(
            request.copy(permittedAdapterKinds = request.permittedAdapterKinds + ExternalAdapterKind.ROOT),
            listOf(root, deniedNative)
        )

        assertNull(decision.selected)
        assertEquals("adapter_authorization_denied", decision.rationale)
        assertTrue(decision.alternatives.isEmpty())
    }

    @Test
    fun `falls back only when the preferred authorized route is unavailable`() {
        val unavailableNative = adapter(ExternalAdapterKind.NATIVE_API, available = false)
        val intent = adapter(ExternalAdapterKind.INTENT_OR_DEEP_LINK, semantic = false)

        val decision = ExternalAppRoutePlanner.plan(request, listOf(intent, unavailableNative))

        assertEquals(intent, decision.selected)
    }

    @Test
    fun `unknown authorization fails closed and does not select a fallback`() {
        val unknownNative = adapter(
            ExternalAdapterKind.NATIVE_API,
            authorization = AdapterAuthorization.UNKNOWN
        )
        val intent = adapter(ExternalAdapterKind.INTENT_OR_DEEP_LINK, semantic = false)

        val decision = ExternalAppRoutePlanner.plan(request, listOf(intent, unknownNative))

        assertNull(decision.selected)
        assertEquals("adapter_authorization_unknown", decision.rationale)
        assertTrue(decision.alternatives.isEmpty())
    }

    @Test
    fun `consent and destructive operations require confirmation`() {
        val consent = adapter(
            ExternalAdapterKind.NATIVE_API,
            authorization = AdapterAuthorization.CONSENT_REQUIRED
        )
        val consentDecision = ExternalAppRoutePlanner.plan(request, listOf(consent))
        assertEquals(consent, consentDecision.selected)
        assertTrue(consentDecision.requiresConfirmation)

        val destructive = adapter(ExternalAdapterKind.NATIVE_API, destructive = true)
        val destructiveDecision = ExternalAppRoutePlanner.plan(request, listOf(destructive))
        assertTrue(destructiveDecision.requiresConfirmation)
    }

    @Test
    fun `privileged adapter kinds are excluded unless host policy permits them`() {
        val shell = adapter(ExternalAdapterKind.SHELL)

        val deniedByDefault = ExternalAppRoutePlanner.plan(request, listOf(shell))
        assertNull(deniedByDefault.selected)
        assertFalse(deniedByDefault.requiresConfirmation)

        val explicitlyPermitted = ExternalAppRoutePlanner.plan(
            request.copy(permittedAdapterKinds = request.permittedAdapterKinds + ExternalAdapterKind.SHELL),
            listOf(shell)
        )
        assertEquals(shell, explicitlyPermitted.selected)
    }
}
