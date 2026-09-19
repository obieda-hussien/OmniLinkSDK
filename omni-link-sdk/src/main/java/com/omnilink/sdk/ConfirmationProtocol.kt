package com.omnilink.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Two-phase preview/commit primitives for destructive capabilities.
 *
 * A commit token is opaque, short-lived and bound by the host to the exact prepared operation.
 * The SDK never treats possession of a same-signer certificate as user consent.
 */
@Serializable
data class ActionPreview(
    val title: String,
    val summary: String,
    val affectedResources: List<String> = emptyList(),
    val risk: CapabilityRisk,
    val diffOrDetails: JsonElement = JsonNull
)

@Serializable
data class PreparedAction(
    val requestId: String,
    val capabilityName: String,
    val preview: ActionPreview,
    val commitToken: String,
    val expiresAtEpochMs: Long
)

@Serializable
data class ActionCommit(
    val requestId: String,
    val commitToken: String
)

@Serializable
enum class ConfirmationGrantScope {
    THIS_ACTION,
    THIS_CAPABILITY_ONCE,
    THIS_SESSION,
    ALWAYS_FOR_THIS_CAPABILITY
}
