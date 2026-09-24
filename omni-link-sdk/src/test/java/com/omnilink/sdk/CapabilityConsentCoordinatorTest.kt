package com.omnilink.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityConsentCoordinatorTest {
    private val host = GrantPrincipal("com.omni.dev", setOf("a".repeat(64)))
    private val target = GrantPrincipal("com.example.app", setOf("b".repeat(64)))
    private val grant = CapabilityGrantRequest(
        requester = host, provider = target, capability = "file.read",
        resourceScope = CapabilityResourceScope(resourceIds = setOf("document:42")),
        purpose = "Read one document", dataScopes = setOf(DataScope.OMNI_AGENT),
        risk = CapabilityRisk.MEDIUM, requestId = "request-42", actionId = "action-42"
    )

    @Test fun `prompt shows exact scope and persisted one-time decision authorizes only once`() = runBlocking {
        val ledger = CapabilityGrantLedger(InMemoryCapabilityGrantPersistence())
        val coordinator = CapabilityConsentCoordinator(ledger) { host to target }
        val result = coordinator.request(grant) { prompt ->
            assertEquals(grant.requester, prompt.requester)
            assertEquals(grant.provider, prompt.provider)
            assertEquals(grant.resourceScope, prompt.resourceScope)
            assertEquals(grant.dataScopes, prompt.dataScopes)
            assertEquals(grant.purpose, prompt.purpose)
            CapabilityGrantDecision.THIS_ACTION
        }
        assertEquals(ConsentResult.Recorded(CapabilityGrantDecision.THIS_ACTION), result)
        assertTrue(ledger.authorize(grant) is CapabilityAuthorization.Allowed)
        assertTrue(ledger.authorize(grant) is CapabilityAuthorization.Denied)
    }

    @Test fun `signer replacement while UI is open never records consent`() = runBlocking {
        val ledger = CapabilityGrantLedger(InMemoryCapabilityGrantPersistence())
        var current = target
        val coordinator = CapabilityConsentCoordinator(ledger) { host to current }
        assertEquals(ConsentResult.Rejected("identity_unavailable_or_changed"), coordinator.request(grant) {
            current = target.copy(signerSha256 = setOf("c".repeat(64)))
            CapabilityGrantDecision.THIS_SESSION
        })
        assertTrue(ledger.currentSnapshot().grants.isEmpty())
    }

    @Test fun `dismissal or unsupported permanent storage fails closed`() = runBlocking {
        val ledger = CapabilityGrantLedger(InMemoryCapabilityGrantPersistence())
        val coordinator = CapabilityConsentCoordinator(ledger) { host to target }
        assertEquals(ConsentResult.Rejected("user_dismissed"), coordinator.request(grant) { null })
        assertEquals(ConsentResult.Rejected("requires_durable_storage"), coordinator.request(grant) {
            CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE
        })
        assertTrue(ledger.currentSnapshot().grants.isEmpty())
    }
}
