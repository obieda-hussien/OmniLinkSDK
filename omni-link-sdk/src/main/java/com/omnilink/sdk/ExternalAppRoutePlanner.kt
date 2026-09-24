package com.omnilink.sdk

/**
 * Chooses among host-discovered adapters for one exact operation. This is a planner only: it
 * neither probes adapters nor grants authority. The caller must populate authorization from
 * host-controlled policy and must obtain confirmation before executing a selected route when
 * [ExternalAppRouteDecision.requiresConfirmation] is true.
 */
object ExternalAppRoutePlanner {
    fun plan(
        request: ExternalAppRouteRequest,
        adapters: Collection<ExternalAppAdapterDescriptor>
    ): ExternalAppRouteDecision {
        val matching = adapters.asSequence()
            .filter { it.packageName == request.packageName }
            .filter { request.operation in it.operations }
            .filter { it.kind in request.permittedAdapterKinds }
            .sortedWith(
                compareBy<ExternalAppAdapterDescriptor> {
                    if (request.requireSemanticAdapterWhenAvailable && it.semantic) 0 else 1
                }.thenBy {
                    if (request.preferLowestPrivilege) privilegeRank(it.kind) else 0
                }.thenBy { it.estimatedLatencyMillis ?: Long.MAX_VALUE }
            )
            .toList()

        if (matching.isEmpty()) {
            return ExternalAppRouteDecision(
                selected = null,
                rationale = "no_permitted_adapter_supports_operation"
            )
        }

        // Only a transport/availability failure permits trying the next route. A permission
        // denial or unknown authorization must stop planning rather than silently escalating.
        for ((index, adapter) in matching.withIndex()) {
            when (adapter.authorization) {
                AdapterAuthorization.DENIED -> return ExternalAppRouteDecision(
                    selected = null,
                    rationale = "adapter_authorization_denied"
                )

                AdapterAuthorization.UNKNOWN -> return ExternalAppRouteDecision(
                    selected = null,
                    rationale = "adapter_authorization_unknown"
                )

                AdapterAuthorization.GRANTED,
                AdapterAuthorization.CONSENT_REQUIRED -> if (!adapter.available) {
                    // An unavailable route with an authorization denial was handled above.
                    // Availability failure alone is safe to recover from using another route.
                    continue
                } else {
                    val consentRequired =
                        adapter.authorization == AdapterAuthorization.CONSENT_REQUIRED
                    return ExternalAppRouteDecision(
                        selected = adapter,
                        alternatives = matching.drop(index + 1).filter {
                            it.available && it.authorization == AdapterAuthorization.GRANTED
                        },
                        rationale = if (consentRequired) {
                            "route_requires_user_consent"
                        } else {
                            "selected_best_authorized_route"
                        },
                        requiresConfirmation = consentRequired || adapter.destructive
                    )
                }
            }
        }

        return ExternalAppRouteDecision(
            selected = null,
            rationale = "all_authorized_adapters_unavailable"
        )
    }

    private fun privilegeRank(kind: ExternalAdapterKind): Int = when (kind) {
        ExternalAdapterKind.NATIVE_API -> 0
        ExternalAdapterKind.INTENT_OR_DEEP_LINK -> 1
        ExternalAdapterKind.MEDIA_SESSION -> 2
        ExternalAdapterKind.NOTIFICATION_ACTION -> 3
        ExternalAdapterKind.ACCESSIBILITY -> 4
        ExternalAdapterKind.SHIZUKU -> 5
        ExternalAdapterKind.SHELL -> 6
        ExternalAdapterKind.ROOT -> 7
    }
}
