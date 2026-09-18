package com.omnilink.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ActionRequest(
    val name: String,
    val payload: JsonElement
)

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
    val message: String
)

@Serializable
data class CapabilityDescriptor(
    val name: String,
    val description: String = "",
    val destructive: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val executionMode: CapabilityExecutionMode = CapabilityExecutionMode.ASYNC,
    val supportsStreaming: Boolean = false
)

@Serializable
enum class CapabilityExecutionMode {
    IMMEDIATE,
    ASYNC,
    JOB
}

@Serializable
data class CapabilityManifest(
    val protocolVersion: Int,
    val sdkVersion: String,
    val minSupportedVersion: Int,
    val maxSupportedVersion: Int,
    val capabilities: List<CapabilityDescriptor>,
    val supportsTicks: Boolean = false,
    val preferredTickIntervalSeconds: Int = 900
)

@Serializable
data class CallerContext(
    val callingUid: Int,
    val callingPackage: String
)

@Serializable
data class OmniEvent(
    val name: String,
    val payload: JsonElement
)
