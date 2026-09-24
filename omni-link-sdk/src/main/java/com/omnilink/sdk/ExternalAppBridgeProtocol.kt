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
    val reasonUnavailable: String? = null,
    /** Operations the host has independently verified this adapter can perform. */
    val operations: Set<String> = emptySet(),
    /** Must be assigned by host policy; never trust a provider's self-reported value. */
    val authorization: AdapterAuthorization = AdapterAuthorization.UNKNOWN
)

@Serializable
enum class AdapterAuthorization {
    UNKNOWN,
    GRANTED,
    CONSENT_REQUIRED,
    DENIED
}

@Serializable
data class ExternalAppRouteRequest(
    val packageName: String,
    val operation: String,
    val preferLowestPrivilege: Boolean = true,
    val requireSemanticAdapterWhenAvailable: Boolean = true,
    /** Elevated adapters are excluded unless the host explicitly permits them for this request. */
    val permittedAdapterKinds: Set<ExternalAdapterKind> = setOf(
        ExternalAdapterKind.NATIVE_API,
        ExternalAdapterKind.INTENT_OR_DEEP_LINK,
        ExternalAdapterKind.MEDIA_SESSION,
        ExternalAdapterKind.NOTIFICATION_ACTION
    )
)

@Serializable
data class ExternalAppRouteDecision(
    val selected: ExternalAppAdapterDescriptor?,
    val alternatives: List<ExternalAppAdapterDescriptor> = emptyList(),
    val rationale: String,
    val requiresConfirmation: Boolean = false
)
