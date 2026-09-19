package com.omnilink.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Shared wire types for applications that embed the Omni agent experience.
 *
 * The caller package is intentionally not trusted from this payload. Workspace resolves the
 * actual package/UID from Binder and treats [appDisplayName] as presentation metadata only.
 */
@Serializable
data class AgentTaskRequest(
    val taskId: String,
    val clientConversationId: String? = null,
    val title: String? = null,
    val appDisplayName: String? = null,
    val prompt: String,
    val scopePath: String? = null,
    val mode: AgentClientMode = AgentClientMode.AGENT,
    val context: JsonElement = JsonNull
)

@Serializable
enum class AgentClientMode {
    CHAT,
    AGENT,
    TEAM
}

@Serializable
data class AgentGatewayManifest(
    val protocolVersion: Int = OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
    val sdkVersion: String = OmniLinkConstants.SDK_VERSION,
    val minSupportedVersion: Int,
    val maxSupportedVersion: Int,
    val supportsMcp: Boolean = true,
    val supportsWebSearch: Boolean = true,
    val supportsTools: Boolean = true,
    val supportsTeamMode: Boolean = true,
    val supportsPersistentHistory: Boolean = true,
    val supportsAgentConsole: Boolean = true,
    val supportsHistoryRead: Boolean = false,
    val supportsEventReplay: Boolean = false
)

@Serializable
data class AgentTaskSnapshot(
    val taskId: String,
    val workspaceSessionId: Long? = null,
    val state: AgentTaskState,
    val lastSequence: Long = 0,
    val finalAnswer: String? = null,
    val error: String? = null
)

@Serializable
enum class AgentTaskState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
sealed interface AgentTaskEvent {
    val taskId: String
    val sequence: Long
    val timestamp: Long

    @Serializable
    @SerialName("started")
    data class Started(
        override val taskId: String,
        override val sequence: Long,
        override val timestamp: Long,
        val workspaceSessionId: Long,
        val conversationTitle: String,
        val sourceAppPackage: String,
        val sourceAppName: String
    ) : AgentTaskEvent

    @Serializable
    @SerialName("status")
    data class Status(
        override val taskId: String,
        override val sequence: Long,
        override val timestamp: Long,
        val label: String,
        val detail: String? = null
    ) : AgentTaskEvent

    @Serializable
    @SerialName("stream")
    data class StreamChunk(
        override val taskId: String,
        override val sequence: Long,
        override val timestamp: Long,
        val delta: String
    ) : AgentTaskEvent

    @Serializable
    @SerialName("console")
    data class Console(
        override val taskId: String,
        override val sequence: Long,
        override val timestamp: Long,
        val kind: String,
        val name: String? = null,
        val summary: String,
        val detail: String? = null,
        val isError: Boolean = false
    ) : AgentTaskEvent

    @Serializable
    @SerialName("final")
    data class FinalAnswer(
        override val taskId: String,
        override val sequence: Long,
        override val timestamp: Long,
        val content: String
    ) : AgentTaskEvent

    @Serializable
    @SerialName("error")
    data class Error(
        override val taskId: String,
        override val sequence: Long,
        override val timestamp: Long,
        val code: String,
        val message: String
    ) : AgentTaskEvent

    @Serializable
    @SerialName("cancelled")
    data class Cancelled(
        override val taskId: String,
        override val sequence: Long,
        override val timestamp: Long
    ) : AgentTaskEvent
}


/** Query parameters for listing conversations owned by the calling application. */
@Serializable
data class AgentConversationQuery(
    val limit: Int = 50,
    val beforeUpdatedAt: Long? = null,
    val search: String? = null
)

/** Query parameters for reading one conversation page. */
@Serializable
data class AgentConversationReadQuery(
    val limit: Int = 100,
    val beforeMessageId: Long? = null
)

@Serializable
data class AgentConversationSummary(
    val clientConversationId: String,
    val workspaceSessionId: Long,
    val title: String,
    val sourceAppPackage: String,
    val sourceAppName: String,
    val lastUpdated: Long,
    val status: String? = null
)

@Serializable
data class AgentConversationList(
    val conversations: List<AgentConversationSummary>,
    val nextBeforeUpdatedAt: Long? = null
)

@Serializable
data class AgentConversationMessage(
    val id: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    /** Serialized Workspace Agent Console payload for faithful rendering by clients. */
    val consoleJson: String? = null
)

@Serializable
data class AgentConversationSnapshot(
    val conversation: AgentConversationSummary,
    val messages: List<AgentConversationMessage>,
    val nextBeforeMessageId: Long? = null
)

@Serializable
data class AgentTaskEventPage(
    val taskId: String,
    val events: List<AgentTaskEvent>,
    val lastSequence: Long,
    val hasMore: Boolean = false
)
