package com.omnilink.sdk

import kotlinx.serialization.Serializable

/**
 * A point-in-time graph of discoverable Omni capabilities. Workspace can build this from manifests
 * and use it for cross-app planning without hard-coded knowledge of each application.
 */
@Serializable
data class CapabilityGraphNode(
    val nodeId: String,
    val appPackage: String,
    val appDisplayName: String? = null,
    val capability: CapabilityDescriptor,
    val trustTier: TrustTier,
    val available: Boolean = true,
    val latencyHintMillis: Long? = null
)

@Serializable
data class CapabilityGraphEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val relation: CapabilityRelation,
    val description: String? = null
)

@Serializable
enum class CapabilityRelation {
    PRODUCES_INPUT_FOR,
    REQUIRES,
    ALTERNATIVE_TO,
    DELEGATES_TO,
    OBSERVES
}

@Serializable
data class CapabilityGraphSnapshot(
    val generatedAtEpochMs: Long,
    val nodes: List<CapabilityGraphNode>,
    val edges: List<CapabilityGraphEdge>
)

@Serializable
data class CapabilityRoute(
    val nodeIds: List<String>,
    val estimatedLatencyMillis: Long? = null,
    val requiresUserConfirmation: Boolean = false,
    val rationale: String? = null
)
