package com.omnilink.sdk

import kotlinx.coroutines.CancellationException

/** Exact details a host must show before saving a capability decision. */
data class CapabilityConsentPrompt(
    val requester: GrantPrincipal,
    val provider: GrantPrincipal,
    val capability: String,
    val resourceScope: CapabilityResourceScope,
    val purpose: String,
    val dataScopes: Set<DataScope>,
    val risk: CapabilityRisk,
    val requestId: String,
    val actionId: String?,
    val sessionId: String?
)

fun interface CapabilityConsentPresenter {
    /** Return null when dismissed or when no trusted foreground user prompt can be shown. */
    suspend fun present(prompt: CapabilityConsentPrompt): CapabilityGrantDecision?
}

sealed interface ConsentResult {
    data class Recorded(val decision: CapabilityGrantDecision) : ConsentResult
    data class Rejected(val reason: String) : ConsentResult
}

/**
 * The SDK provides the exact prompt and persists the selected decision before returning. The host
 * must implement a real user-facing presenter; callers must not supply decisions from a peer's
 * payload. Execution still calls ledger.authorize() separately and rechecks live identity.
 */
class CapabilityConsentCoordinator(
    private val grants: CapabilityGrantLedger,
    private val identities: VerifiedAppPairResolver
) {
    suspend fun request(
        grant: CapabilityGrantRequest,
        presenter: CapabilityConsentPresenter
    ): ConsentResult {
        if (!identityMatches(grant)) return ConsentResult.Rejected("identity_unavailable_or_changed")
        val prompt = CapabilityConsentPrompt(
            grant.requester, grant.provider, grant.capability, grant.resourceScope,
            grant.purpose, grant.dataScopes, grant.risk, grant.requestId, grant.actionId,
            grant.sessionId
        )
        val decision = try {
            presenter.present(prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return ConsentResult.Rejected("user_dismissed")
        if (!identityMatches(grant)) return ConsentResult.Rejected("identity_unavailable_or_changed")
        return when (val recorded = grants.recordDecision(grant, decision)) {
            GrantMutationResult.RECORDED -> ConsentResult.Recorded(decision)
            else -> ConsentResult.Rejected(recorded.name.lowercase())
        }
    }

    private fun identityMatches(grant: CapabilityGrantRequest): Boolean {
        val current = try { identities.resolve() } catch (_: Exception) { null } ?: return false
        return current.first.matches(grant.requester) && current.second.matches(grant.provider)
    }
}
