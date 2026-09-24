package com.omnilink.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedExternalAppExecutorTest {
    private val requester = GrantPrincipal("com.omni.dev", setOf("a".repeat(64)))
    private val provider = GrantPrincipal("com.example.player", setOf("b".repeat(64)))
    private val grant = CapabilityGrantRequest(
        requester, provider, "media.pause", CapabilityResourceScope(resourceIds = setOf("player")),
        "Pause playback", requestId = "request-1", actionId = "action-1"
    )
    private val route = ExternalAppRouteRequest(provider.packageName, grant.capability)

    private class Adapter(
        override val descriptor: ExternalAppAdapterDescriptor,
        private val result: Boolean = true
    ) : ExternalAppOperationAdapter {
        var calls = 0
        override suspend fun execute(): Boolean {
            calls++
            return result
        }
    }

    private fun adapter(
        authorization: AdapterAuthorization = AdapterAuthorization.GRANTED,
        kind: ExternalAdapterKind = ExternalAdapterKind.NATIVE_API,
        destructive: Boolean = false
    ) = Adapter(ExternalAppAdapterDescriptor(
        provider.packageName, kind, available = true, operations = setOf(grant.capability),
        authorization = authorization, destructive = destructive
    ))

    @Test fun `executes only with verified identities and exact grant`() = runBlocking {
        val ledger = CapabilityGrantLedger(InMemoryCapabilityGrantPersistence())
        val action = adapter()
        val executor = AuthorizedExternalAppExecutor(ledger) { requester to provider }
        assertEquals(ExternalAppExecutionResult.Rejected("no_matching_grant"),
            executor.execute(route, grant, listOf(action)))
        assertEquals(0, action.calls)
        ledger.recordDecision(grant, CapabilityGrantDecision.THIS_ACTION)
        assertTrue(executor.execute(route, grant, listOf(action)) is ExternalAppExecutionResult.Completed)
        assertEquals(1, action.calls)
        assertEquals(ExternalAppExecutionResult.Rejected("no_matching_grant"),
            executor.execute(route, grant, listOf(action)))
    }

    @Test fun `changed signer or target prevents consumption and execution`() = runBlocking {
        val ledger = CapabilityGrantLedger(InMemoryCapabilityGrantPersistence())
        ledger.recordDecision(grant, CapabilityGrantDecision.THIS_ACTION)
        val action = adapter()
        val swapped = AuthorizedExternalAppExecutor(ledger) {
            requester to provider.copy(signerSha256 = setOf("c".repeat(64)))
        }
        assertEquals(ExternalAppExecutionResult.Rejected("identity_mismatch"),
            swapped.execute(route, grant, listOf(action)))
        val valid = AuthorizedExternalAppExecutor(ledger) { requester to provider }
        assertEquals(ExternalAppExecutionResult.Rejected("operation_or_provider_mismatch"),
            valid.execute(route.copy(operation = "media.play"), grant, listOf(action)))
        assertTrue(valid.execute(route, grant, listOf(action)) is ExternalAppExecutionResult.Completed)
    }

    @Test fun `confirmation and live identity recheck precede grant consumption`() = runBlocking {
        val ledger = CapabilityGrantLedger(InMemoryCapabilityGrantPersistence())
        ledger.recordDecision(grant, CapabilityGrantDecision.THIS_ACTION)
        val action = adapter(destructive = true)
        var current = provider
        val executor = AuthorizedExternalAppExecutor(ledger) { requester to current }
        assertEquals(ExternalAppExecutionResult.Rejected("confirmation_required"),
            executor.execute(route, grant, listOf(action)))
        assertEquals(ExternalAppExecutionResult.Rejected("identity_mismatch"),
            executor.execute(route, grant, listOf(action)) {
                current = provider.copy(signerSha256 = setOf("c".repeat(64)))
                true
            })
        current = provider
        assertTrue(executor.execute(route, grant, listOf(action)) { true } is ExternalAppExecutionResult.Completed)
        assertEquals(1, action.calls)
    }

    @Test fun `denied route never falls through to a second adapter`() = runBlocking {
        val ledger = CapabilityGrantLedger(InMemoryCapabilityGrantPersistence())
        ledger.recordDecision(grant, CapabilityGrantDecision.THIS_ACTION)
        val denied = adapter(AdapterAuthorization.DENIED)
        val alternate = adapter(kind = ExternalAdapterKind.INTENT_OR_DEEP_LINK)
        val executor = AuthorizedExternalAppExecutor(ledger) { requester to provider }
        assertEquals(ExternalAppExecutionResult.Rejected("adapter_authorization_denied"),
            executor.execute(route, grant, listOf(denied, alternate)))
        assertEquals(0, alternate.calls)
    }
}
