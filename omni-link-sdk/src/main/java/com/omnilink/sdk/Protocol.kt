package com.omnilink.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
data class ActionRequest(
    val name: String,
    val payload: JsonElement,
    val requestId: String? = null,
    val correlationId: String? = null,
    val idempotencyKey: String? = null,
    val deadlineEpochMs: Long? = null,
    val priority: ActionPriority = ActionPriority.NORMAL,
    val dryRun: Boolean = false,
    val confirmationToken: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap())
)

@Serializable
enum class ActionPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

@Serializable
sealed interface ActionOutcome {
    @Serializable
    data class Success(val data: JsonElement) : ActionOutcome

    @Serializable
    data class Failure(val error: ActionError) : ActionOutcome

    @Serializable
    data class RequiresConfirmation(val message: String) : ActionOutcome
}

@Serializable
data class ActionError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val retryAfterMillis: Long? = null,
    val details: JsonElement = JsonNull
)

@Serializable
data class CapabilityDescriptor(
    val name: String,
    val description: String = "",
    val destructive: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val executionMode: CapabilityExecutionMode = CapabilityExecutionMode.ASYNC,
    val supportsStreaming: Boolean = false,

    // v1.3 additive contract metadata. Defaults are intentionally conservative and omitted on wire.
    val requiredTrustTier: TrustTier = TrustTier.FIRST_PARTY,
    val communicationDirection: CommunicationDirection = CommunicationDirection.BIDIRECTIONAL,
    val risk: CapabilityRisk = CapabilityRisk.LOW,
    val idempotency: IdempotencySemantics = IdempotencySemantics.UNKNOWN,
    val supportsDryRun: Boolean = false,
    val timeoutMillis: Long = 30_000,
    val maxInlinePayloadBytes: Int = OmniLinkConstants.DEFAULT_MAX_INLINE_JSON_BYTES,
    val dataScopes: Set<DataScope> = setOf(DataScope.OMNI_ECOSYSTEM),
    val requiredPermissions: Set<String> = emptySet(),
    val inputSchema: JsonElement = JsonNull,
    val outputSchema: JsonElement = JsonNull
)

@Serializable
enum class CapabilityExecutionMode {
    IMMEDIATE,
    ASYNC,
    JOB
}

@Serializable
enum class CapabilityRisk {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

@Serializable
enum class IdempotencySemantics {
    IDEMPOTENT,
    IDEMPOTENT_WITH_KEY,
    NON_IDEMPOTENT,
    UNKNOWN
}

@Serializable
data class CapabilityManifest(
    val protocolVersion: Int,
    val sdkVersion: String,
    val minSupportedVersion: Int,
    val maxSupportedVersion: Int,
    val capabilities: List<CapabilityDescriptor>,
    val supportsTicks: Boolean = false,
    val preferredTickIntervalSeconds: Int = 900,
    val maxInlinePayloadBytes: Int = OmniLinkConstants.DEFAULT_MAX_INLINE_JSON_BYTES,
    val supportsSessionProtocol: Boolean = false,
    val supportsFlowControl: Boolean = false,
    val supportsLargePayloadTransport: Boolean = false,
    val supportedCodecs: Set<String> = setOf("json")
)

@Serializable
data class CallerContext(
    val callingUid: Int,
    val callingPackage: String,
    val callingPackages: List<String> = emptyList(),
    val signingCertificateSha256: List<String> = emptyList(),
    val sameSignerAsHost: Boolean = false
)

@Serializable
data class OmniEvent(
    val name: String,
    val payload: JsonElement,
    val sequence: Long = 0,
    val timestampEpochMs: Long = 0,
    val sourcePackage: String? = null
)
