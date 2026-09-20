package com.omnilink.sdk

import android.content.Intent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExtensionServiceHardeningTest {

    class ModernService : ExtensionService() {
        override val minSupportedVersion = 1
        override val maxSupportedVersion = OmniLinkConstants.CURRENT_PROTOCOL_VERSION
        override val maxInlineRequestBytes = 512

        override val capabilities = listOf(
            CapabilityDescriptor(
                name = "async_action",
                executionMode = CapabilityExecutionMode.ASYNC
            ),
            CapabilityDescriptor(
                name = "immediate_action",
                executionMode = CapabilityExecutionMode.IMMEDIATE
            )
        )

        override val accessController = object : AccessController {
            override fun decide(
                caller: CallerContext,
                request: ActionRequest
            ): AccessDecision = AccessDecision.ALLOW
        }

        override val auditLogger = object : AuditLogger {
            override fun log(
                caller: CallerContext,
                request: ActionRequest,
                result: ActionOutcome
            ) = Unit
        }

        override suspend fun onAction(
            caller: CallerContext,
            request: ActionRequest
        ): ActionOutcome = ActionOutcome.Success(buildJsonObject { put("ok", true) })
    }

    private fun binder(): IExtensionService {
        val service = Robolectric.buildService(ModernService::class.java).create().bind().get()
        return service.onBind(Intent()) as IExtensionService
    }

    @Test
    fun `binder manifest reads outer service metadata without recursive getter`() {
        val service = Robolectric.buildService(ModernService::class.java).create().bind().get()
        val binder = service.onBind(Intent()) as IExtensionService

        // This exact Binder call previously recursed into itself indefinitely when the
        // unqualified Kotlin property resolved the AIDL getter instead of the outer service.
        val raw = binder.getCapabilityManifest()
        val manifest = OmniJson.instance.decodeFromString<CapabilityManifest>(raw)

        assertEquals(service.minSupportedVersion, manifest.minSupportedVersion)
        assertEquals(service.maxSupportedVersion, manifest.maxSupportedVersion)
        assertEquals(2, manifest.capabilities.size)
        assertEquals("async_action", manifest.capabilities[0].name)
        assertEquals("immediate_action", manifest.capabilities[1].name)
        assertEquals(service.maxInlineRequestBytes, manifest.maxInlinePayloadBytes)
    }

    @Test
    fun `declared async capability rejects synchronous binder path`() {
        val request = ActionRequest("async_action", buildJsonObject { })
        val outcome = OmniJson.instance.decodeFromString<ActionOutcome>(
            binder().executeAction(
                OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
                OmniJson.instance.encodeToString(request)
            )
        )

        assertTrue(outcome is ActionOutcome.Failure)
        assertEquals("async_required", (outcome as ActionOutcome.Failure).error.code)
    }

    @Test
    fun `undeclared capability is rejected when manifest is explicit`() {
        val request = ActionRequest("not_declared", buildJsonObject { })
        val outcome = OmniJson.instance.decodeFromString<ActionOutcome>(
            binder().executeAction(
                OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
                OmniJson.instance.encodeToString(request)
            )
        )

        assertTrue(outcome is ActionOutcome.Failure)
        assertEquals("unknown_capability", (outcome as ActionOutcome.Failure).error.code)
    }

    @Test
    fun `expired request is rejected before execution`() {
        val request = ActionRequest(
            "immediate_action",
            buildJsonObject { },
            deadlineEpochMs = 1L
        )
        val outcome = OmniJson.instance.decodeFromString<ActionOutcome>(
            binder().executeAction(
                OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
                OmniJson.instance.encodeToString(request)
            )
        )

        assertTrue(outcome is ActionOutcome.Failure)
        assertEquals("deadline_exceeded", (outcome as ActionOutcome.Failure).error.code)
    }

    @Test
    fun `oversized inline request fails before binder work executes`() {
        val request = ActionRequest(
            "immediate_action",
            buildJsonObject { put("blob", "x".repeat(2_000)) }
        )
        val outcome = OmniJson.instance.decodeFromString<ActionOutcome>(
            binder().executeAction(
                OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
                OmniJson.instance.encodeToString(request)
            )
        )

        assertTrue(outcome is ActionOutcome.Failure)
        assertEquals("payload_too_large", (outcome as ActionOutcome.Failure).error.code)
    }

    @Test
    fun `canonical json ignores additive unknown fields`() {
        val decoded = OmniJson.instance.decodeFromString<ActionRequest>(
            """{"name":"immediate_action","payload":{},"futureField":"safe-to-ignore"}"""
        )
        assertEquals("immediate_action", decoded.name)
    }
}
