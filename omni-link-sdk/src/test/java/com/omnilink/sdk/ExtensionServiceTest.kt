package com.omnilink.sdk

import android.content.Intent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExtensionServiceTest {

    class TestService : ExtensionService() {
        override val minSupportedVersion: Int = 1
        override val maxSupportedVersion: Int = 1

        override val accessController = object : AccessController {
            override fun decide(caller: CallerContext, request: ActionRequest): AccessDecision {
                return AccessDecision.ALLOW
            }
        }

        override val auditLogger = object : AuditLogger {
            override fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome) {
                // No-op
            }
        }

        override suspend fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome {
            return ActionOutcome.Success(buildJsonObject { })
        }
    }

    @Test
    fun `executeAction with unsupported protocol version returns version_mismatch error`() {
        val service = Robolectric.buildService(TestService::class.java).create().bind().get()
        val binder = service.onBind(Intent()) as IExtensionService

        val request = ActionRequest("test", buildJsonObject { })
        val requestJson = Json.encodeToString(request)

        val resultJson = binder.executeAction(2, requestJson)
        val outcome = Json.decodeFromString<ActionOutcome>(resultJson)

        assertTrue(outcome is ActionOutcome.Failure)
        val failure = outcome as ActionOutcome.Failure
        assertEquals("version_mismatch", failure.error.code)
    }
}
