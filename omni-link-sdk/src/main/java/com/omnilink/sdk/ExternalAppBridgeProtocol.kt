package com.omnilink.sdk

import kotlinx.serialization.Serializable

/**
 * Describes ways Omni may interact with an application that is not part of the same-signature
 * ecosystem. Foreign apps do not gain privileged access back into Omni through these adapters.
 */
@Serializable
enum class ExternalAdapterKind {
    NATIVE_API,
    INTENT_OR_DEEP_LINK,
    MEDIA_SESSION,
    NOTIFICATION_ACTION,
    ACCESSIBILITY,
    SHIZUKU,
    SHELL,
    ROOT
}

@Serializable
data class ExternalAppAdapterDescriptor(
    val packageName: String,
    val kind: ExternalAdapterKind,
    val available: Boolean,
    val semantic: Boolean = false,
    val requiresUserGrant: Boolean = false,
    val destructive: Boolean = false,
    val estimatedLatencyMillis: Long? = null,
    val reasonUnavailable: String? = null
)

@Serializable
data class ExternalAppRouteRequest(
    val packageName: String,
    val operation: String,
    val preferLowestPrivilege: Boolean = true,
    val requireSemanticAdapterWhenAvailable: Boolean = true
)

@Serializable
data class ExternalAppRouteDecision(
    val selected: ExternalAppAdapterDescriptor?,
    val alternatives: List<ExternalAppAdapterDescriptor> = emptyList(),
    val rationale: String,
    val requiresConfirmation: Boolean = false
)
