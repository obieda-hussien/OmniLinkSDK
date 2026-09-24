package com.omnilink.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityGrantLedgerTest {
    private val requester = GrantPrincipal("com.omni.dev", setOf("a".repeat(64)))
    private val provider = GrantPrincipal("com.omni.ide", setOf("b".repeat(64)))
    private val scope = CapabilityResourceScope(resourceIds = setOf("project:42"))

    private class TestPersistence(
        override val supportsPersistentGrants: Boolean = true,
        private var current: CapabilityGrantSnapshot = CapabilityGrantSnapshot(),
        var failSaves: Boolean = false,
        var failLoads: Boolean = false
    ) : CapabilityGrantPersistence {
        override fun load(): CapabilityGrantSnapshot {
            check(!failLoads) { "simulated unreadable grant store" }
            return current
        }

        override fun save(snapshot: CapabilityGrantSnapshot): Boolean {
            if (failSaves) return false
            current = snapshot
            return true
        }
    }

    private fun request(
        requestId: String = "req-1",
        actionId: String? = "action-1",
        sessionId: String? = "session-1",
        resourceScope: CapabilityResourceScope = scope,
        capability: String = "ide.file.read",
        requester: GrantPrincipal = this.requester,
        provider: GrantPrincipal = this.provider
    ) = CapabilityGrantRequest(
        requester = requester,
        provider = provider,
        capability = capability,
        resourceScope = resourceScope,
        purpose = "Read the file selected for this task",
        dataScopes = setOf(DataScope.OMNI_AGENT),
        requestId = requestId,
        actionId = actionId,
        sessionId = sessionId
    )

    @Test
    fun `this action is exact and consumed once`() {
        val ledger = CapabilityGrantLedger(TestPersistence())
        val first = request()
        assertEquals(GrantMutationResult.RECORDED, ledger.recordDecision(first, CapabilityGrantDecision.THIS_ACTION))
        assertTrue(ledger.authorize(first) is CapabilityAuthorization.Allowed)
        assertEquals("no_matching_grant", (ledger.authorize(first) as CapabilityAuthorization.Denied).reason)

        val otherAction = first.copy(requestId = "req-2", actionId = "action-2")
        assertEquals("no_matching_grant", (ledger.authorize(otherAction) as CapabilityAuthorization.Denied).reason)
    }

    @Test
    fun `once grant is bound to one request and exact resource scope`() {
        val ledger = CapabilityGrantLedger(TestPersistence())
        val first = request(actionId = null)
        assertEquals(GrantMutationResult.RECORDED, ledger.recordDecision(first, CapabilityGrantDecision.THIS_CAPABILITY_ONCE))

        val differentScope = first.copy(
            requestId = "req-other-scope",
            resourceScope = CapabilityResourceScope(resourceIds = setOf("project:99"))
        )
        assertEquals("no_matching_grant", (ledger.authorize(differentScope) as CapabilityAuthorization.Denied).reason)
        assertTrue(ledger.authorize(first) is CapabilityAuthorization.Allowed)
        assertEquals("no_matching_grant", (ledger.authorize(first) as CapabilityAuthorization.Denied).reason)
    }

    @Test
    fun `session grants end and notify revocation listeners immediately`() {
        val ledger = CapabilityGrantLedger(TestPersistence())
        val issued = request()
        var revoked: Set<String> = emptySet()
        val listener = ledger.addRevocationListener { revoked = it }
        try {
            ledger.recordDecision(issued, CapabilityGrantDecision.THIS_SESSION)
            assertTrue(ledger.authorize(issued) is CapabilityAuthorization.Allowed)
            assertEquals(1, ledger.endSession("session-1"))
            assertEquals("no_matching_grant", (ledger.authorize(issued) as CapabilityAuthorization.Denied).reason)
            assertEquals(1, revoked.size)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `always grant requires durable storage and survives ledger recreation`() {
        val memoryLedger = CapabilityGrantLedger(InMemoryCapabilityGrantPersistence())
        assertEquals(
            GrantMutationResult.REQUIRES_DURABLE_STORAGE,
            memoryLedger.recordDecision(request(), CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE)
        )

        val persistence = TestPersistence()
        val firstLedger = CapabilityGrantLedger(persistence)
        val issued = request(actionId = null, sessionId = null)
        assertEquals(
            GrantMutationResult.RECORDED,
            firstLedger.recordDecision(issued, CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE)
        )
        val reloadedLedger = CapabilityGrantLedger(persistence)
        val sameScope = issued.copy(requestId = "request-after-restart")
        assertTrue(reloadedLedger.authorize(sameScope) is CapabilityAuthorization.Allowed)
        val broaderScope = sameScope.copy(resourceScope = CapabilityResourceScope(global = true))
        assertEquals("no_matching_grant", (reloadedLedger.authorize(broaderScope) as CapabilityAuthorization.Denied).reason)

        val alteredPurpose = sameScope.copy(purpose = "Use this data for a different task")
        assertEquals("no_matching_grant", (reloadedLedger.authorize(alteredPurpose) as CapabilityAuthorization.Denied).reason)
    }

    @Test
    fun `deny wins and block prevents every capability until explicitly removed`() {
        val persistence = TestPersistence()
        val ledger = CapabilityGrantLedger(persistence)
        val issued = request()
        ledger.recordDecision(issued, CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE)

        val deniedAction = issued.copy(actionId = "denied-action", requestId = "deny-req")
        ledger.recordDecision(deniedAction, CapabilityGrantDecision.DENY)
        assertEquals("user_denied", (ledger.authorize(deniedAction) as CapabilityAuthorization.Denied).reason)

        assertEquals(GrantMutationResult.RECORDED, ledger.recordDecision(issued, CapabilityGrantDecision.BLOCK))
        val otherCapability = issued.copy(capability = "ide.file.patch")
        assertEquals("app_pair_blocked", (ledger.authorize(otherCapability) as CapabilityAuthorization.Denied).reason)
        assertTrue(ledger.unblockPair(requester, provider))
        ledger.recordDecision(
            issued.copy(requestId = "re-approved", actionId = null, sessionId = null),
            CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE
        )
        assertTrue(ledger.authorize(issued) is CapabilityAuthorization.Allowed)
    }

    @Test
    fun `failed durable write and unreadable storage fail closed`() {
        val failingPersistence = TestPersistence(failSaves = true)
        val failingLedger = CapabilityGrantLedger(failingPersistence)
        val issued = request(actionId = null, sessionId = null)
        assertEquals(
            GrantMutationResult.STORAGE_UNAVAILABLE,
            failingLedger.recordDecision(issued, CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE)
        )
        assertEquals("grant_store_unavailable", (failingLedger.authorize(issued) as CapabilityAuthorization.Denied).reason)

        val unreadable = CapabilityGrantLedger(TestPersistence(failLoads = true))
        assertEquals("grant_store_unavailable", (unreadable.authorize(issued) as CapabilityAuthorization.Denied).reason)
    }

    @Test
    fun `failed revocation write disables authorization and notifies active consumers`() {
        val persistence = TestPersistence()
        val ledger = CapabilityGrantLedger(persistence)
        val issued = request(actionId = null, sessionId = null)
        ledger.recordDecision(issued, CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE)
        val grantId = (ledger.authorize(issued) as CapabilityAuthorization.Allowed).grantId
        var revoked: Set<String> = emptySet()
        val listener = ledger.addRevocationListener { revoked = it }

        try {
            persistence.failSaves = true
            assertFalse(ledger.revoke(grantId))
            assertEquals(setOf(grantId), revoked)
            assertEquals("grant_store_unavailable", (ledger.authorize(issued) as CapabilityAuthorization.Denied).reason)
        } finally {
            listener.close()
        }
    }

    @Test
    fun `expiry revokes session grants after the maximum session lifetime`() {
        val persistence = TestPersistence()
        var now = 10_000L
        val ledger = CapabilityGrantLedger(persistence, nowEpochMs = { now })
        val issued = request()
        ledger.recordDecision(issued, CapabilityGrantDecision.THIS_SESSION)
        assertTrue(ledger.authorize(issued) is CapabilityAuthorization.Allowed)

        now += 24 * 60 * 60 * 1000L
        assertEquals("no_matching_grant", (ledger.authorize(issued) as CapabilityAuthorization.Denied).reason)
    }

}
