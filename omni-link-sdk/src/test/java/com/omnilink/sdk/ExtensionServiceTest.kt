package com.omnilink.sdk

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ExtensionServiceTest {

    class V1Service : ExtensionService() {
        override val minSupportedVersion: Int = 1
        override val maxSupportedVersion: Int = 1

        override val accessController = object : AccessController {
            override fun decide(caller: CallerContext, request: ActionRequest): AccessDecision {
                return AccessDecision.ALLOW
            }
        }

        override val auditLogger = object : AuditLogger {
            override fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome) {
            }
        }

        override suspend fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome {
            return ActionOutcome.Success(buildJsonObject { })
        }
    }

    class V2Service : ExtensionService() {
        override val minSupportedVersion: Int = 1
        override val maxSupportedVersion: Int = 2

        override val accessController = object : AccessController {
            override fun decide(caller: CallerContext, request: ActionRequest): AccessDecision {
                return AccessDecision.ALLOW
            }
        }

        override val auditLogger = object : AuditLogger {
            override fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome) {
            }
        }

        override suspend fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome {
            return ActionOutcome.Success(buildJsonObject { })
        }

        private var currentCallback: IOmniEventCallback? = null

        override fun onRegisterEventListener(callback: IOmniEventCallback): Boolean {
            currentCallback = callback
            return true
        }

        override fun onUnregisterEventListener(callback: IOmniEventCallback) {
            if (currentCallback == callback) {
                currentCallback = null
            }
        }

        fun fireTestEvent(event: OmniEvent) {
            currentCallback?.onEvent(Json.encodeToString(event))
        }
    }

    class SecuredService : ExtensionService() {
        override val minSupportedVersion: Int = 1
        override val maxSupportedVersion: Int = 2

        override val accessController = object : AccessController {
            override fun decide(caller: CallerContext, request: ActionRequest): AccessDecision {
                return AccessDecision.ALLOW
            }
        }

        override val auditLogger = object : AuditLogger {
            override fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome) {}
        }

        override val securityValidator = object : SecurityValidator {
            override fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean {
                return false // Deny everyone for test
            }
        }

        override suspend fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome {
            return ActionOutcome.Success(buildJsonObject { })
        }

        override fun onRegisterEventListener(callback: IOmniEventCallback): Boolean {
            return true
        }
    }


    @Test
    fun `executeAction with unsupported protocol version returns version_mismatch error`() {
        val service = Robolectric.buildService(V1Service::class.java).create().bind().get()
        val binder = service.onBind(Intent()) as IExtensionService

        val request = ActionRequest("test", buildJsonObject { })
        val requestJson = Json.encodeToString(request)

        val resultJson = binder.executeAction(2, requestJson)
        val outcome = Json.decodeFromString<ActionOutcome>(resultJson)

        assertTrue(outcome is ActionOutcome.Failure)
        val failure = outcome as ActionOutcome.Failure
        assertEquals("version_mismatch", failure.error.code)
    }

    @Test
    fun `v1 service defaults to events not supported`() = runTest {
        val service = Robolectric.buildService(V1Service::class.java).create().bind().get()
        val binder = service.onBind(Intent()) as IExtensionService

        var error: Throwable? = null
        try {
            binder.observeEvents().first()
        } catch (e: UnsupportedOperationException) {
            error = e
        }

        assertTrue(error != null)
    }

    @Test
    fun `v2 service can broadcast events and consumer can collect them`() = runTest {
        val serviceController = Robolectric.buildService(V2Service::class.java).create().bind()
        val service = serviceController.get()
        val binder = service.onBind(Intent()) as IExtensionService

        val testEvent = OmniEvent("test_event", buildJsonObject { })

        val job = launch {
            val events = binder.observeEvents().take(1).toList()
            assertEquals(1, events.size)
            assertEquals("test_event", events[0].name)
        }

        kotlinx.coroutines.delay(10)

        service.fireTestEvent(testEvent)

        job.join()
    }

    @Test
    fun `secured service rejects execution if signature verification fails`() {
        val service = Robolectric.buildService(SecuredService::class.java).create().bind().get()
        val binder = service.onBind(Intent()) as IExtensionService

        val request = ActionRequest("test", buildJsonObject { })
        val requestJson = Json.encodeToString(request)

        val resultJson = binder.executeAction(1, requestJson)
        val outcome = Json.decodeFromString<ActionOutcome>(resultJson)

        assertTrue(outcome is ActionOutcome.Failure)
        assertEquals("access_denied", (outcome as ActionOutcome.Failure).error.code)
    }

    @Test
    fun `secured service rejects event registration if signature verification fails`() {
        val service = Robolectric.buildService(SecuredService::class.java).create().bind().get()
        val binder = service.onBind(Intent()) as IExtensionService

        val callback = object : IOmniEventCallback.Stub() {
            override fun onEvent(eventJson: String) {}
        }

        val result = binder.registerEventListener(callback)
        assertFalse(result)
    }

    @Test
    fun `executeActionAsync returns outcome via callback`() {
        val service = Robolectric.buildService(V2Service::class.java).create().bind().get()
        val binder = service.onBind(Intent()) as IExtensionService

        val request = ActionRequest("test_async", buildJsonObject { })
        val requestJson = Json.encodeToString(request)

        val latch = CountDownLatch(1)
        var capturedOutcome: ActionOutcome? = null

        val callback = object : IOmniResultCallback.Stub() {
            override fun onResult(resultJson: String) {
                capturedOutcome = Json.decodeFromString<ActionOutcome>(resultJson)
                latch.countDown()
            }
        }

        binder.executeActionAsync(1, requestJson, callback)

        // Wait for async execution
        latch.await(5, TimeUnit.SECONDS)

        assertTrue(capturedOutcome is ActionOutcome.Success)
    }
}
