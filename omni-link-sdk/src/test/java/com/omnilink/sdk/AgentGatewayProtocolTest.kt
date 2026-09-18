package com.omnilink.sdk

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGatewayProtocolTest {
    @Test
    fun `agent task request round trips with IDE context`() {
        val request = AgentTaskRequest(
            taskId = "task-1",
            clientConversationId = "project-chat-1",
            title = "Fix build",
            appDisplayName = "Omni AndroidIDE",
            prompt = "Fix the current build error",
            scopePath = "/storage/emulated/0/AndroidIDEProjects/demo",
            mode = AgentClientMode.AGENT,
            context = buildJsonObject {
                put("activeFile", "app/src/main/java/demo/MainActivity.kt")
                put("buildId", "build-42")
            }
        )

        val encoded = Json.encodeToString(request)
        val decoded = Json.decodeFromString<AgentTaskRequest>(encoded)

        assertEquals(request.taskId, decoded.taskId)
        assertEquals(request.clientConversationId, decoded.clientConversationId)
        assertEquals(request.scopePath, decoded.scopePath)
        assertTrue(decoded.context.toString().contains("MainActivity.kt"))
    }

    @Test
    fun `gateway manifest advertises persistent full-agent features`() {
        val manifest = AgentGatewayManifest(
            minSupportedVersion = OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
            maxSupportedVersion = OmniLinkConstants.CURRENT_PROTOCOL_VERSION
        )

        assertTrue(manifest.supportsMcp)
        assertTrue(manifest.supportsWebSearch)
        assertTrue(manifest.supportsTools)
        assertTrue(manifest.supportsPersistentHistory)
        assertTrue(manifest.supportsAgentConsole)
    }
}
