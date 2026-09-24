package com.omnilink.sdk

/** The host must obtain both principals from its own current identity checks, never from the peer. */
fun interface VerifiedAppPairResolver {
    fun resolve(): Pair<GrantPrincipal, GrantPrincipal>?
}

/** Host-owned, operation-specific adapter. The SDK never runs shell or accessibility commands. */
interface ExternalAppOperationAdapter {
    val descriptor: ExternalAppAdapterDescriptor
    suspend fun execute(): Boolean
}

sealed interface ExternalAppExecutionResult {
    data class Completed(val grantId: String, val adapterKind: ExternalAdapterKind) : ExternalAppExecutionResult
    data class Rejected(val reason: String) : ExternalAppExecutionResult
    data class Failed(val reason: String) : ExternalAppExecutionResult
}

/**
 * An opt-in execution boundary for external app operations. The host supplies independently
 * verified identities, adapter availability and a UI confirmation callback. A route is planned
 * anew for each call; denied adapters and execution failures never trigger a privileged fallback.
 * The adapter is responsible for checking its own Android permission immediately before use.
 */
class AuthorizedExternalAppExecutor(
    private val grants: CapabilityGrantLedger,
    private val identities: VerifiedAppPairResolver
) {
    suspend fun execute(
        route: ExternalAppRouteRequest,
        grant: CapabilityGrantRequest,
        adapters: Collection<ExternalAppOperationAdapter>,
        confirm: suspend (ExternalAppAdapterDescriptor) -> Boolean = { false }
    ): ExternalAppExecutionResult {
        if (grant.capability != route.operation || grant.provider.packageName != route.packageName) {
            return ExternalAppExecutionResult.Rejected("operation_or_provider_mismatch")
        }
        val initial = resolveIdentities() ?: return ExternalAppExecutionResult.Rejected("identity_unavailable")
        if (!matches(initial, grant)) return ExternalAppExecutionResult.Rejected("identity_mismatch")

        val selected = ExternalAppRoutePlanner.plan(route, adapters.map { it.descriptor })
        val descriptor = selected.selected
            ?: return ExternalAppExecutionResult.Rejected(selected.rationale)
        // Duplicate descriptors are ambiguous; never execute a different instance than the planned one.
        val candidates = adapters.filter { it.descriptor == descriptor }
        if (candidates.size != 1) return ExternalAppExecutionResult.Rejected("ambiguous_adapter")
        if (selected.requiresConfirmation) {
            val approved = try {
                confirm(descriptor)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!approved) return ExternalAppExecutionResult.Rejected("confirmation_required")
        }
        val current = resolveIdentities() ?: return ExternalAppExecutionResult.Rejected("identity_unavailable")
        if (!matches(current, grant)) return ExternalAppExecutionResult.Rejected("identity_mismatch")
        val authorized = grants.authorize(grant)
        if (authorized is CapabilityAuthorization.Denied) {
            return ExternalAppExecutionResult.Rejected(authorized.reason)
        }
        authorized as CapabilityAuthorization.Allowed
        return try {
            if (candidates.single().execute()) {
                ExternalAppExecutionResult.Completed(authorized.grantId, descriptor.kind)
            } else {
                ExternalAppExecutionResult.Failed("adapter_failed")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExternalAppExecutionResult.Failed("adapter_failed")
        }
    }

    private fun matches(pair: Pair<GrantPrincipal, GrantPrincipal>, request: CapabilityGrantRequest): Boolean =
        pair.first.matches(request.requester) && pair.second.matches(request.provider)

    private fun resolveIdentities(): Pair<GrantPrincipal, GrantPrincipal>? =
        try { identities.resolve() } catch (_: Exception) { null }
}
