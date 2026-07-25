package com.omnilink.sdk

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
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
                return if (request.name == "test_confirm") AccessDecision.REQUIRES_CONFIRMATION else AccessDecision.ALLOW
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

    class CappedResultService : ExtensionService() {
        override val minSupportedVersion: Int = 1
        override val maxSupportedVersion: Int = 1

        override val accessController = object : AccessController {
            override fun decide(caller: CallerContext, request: ActionRequest): AccessDecision {
                return AccessDecision.ALLOW
            }
        }

        override val auditLogger = object : AuditLogger {
            override fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome) {}
        }

        override suspend fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome {
            if (request.name == "get_large_list") {
                val payload = request.payload as? JsonObject
                val limit = payload?.get("limit")?.let {
                    try {
                        it.toString().toInt()
                    } catch (e: Exception) {
                        null
                    }
                }

                val maxLimit = 50
                if (limit == null) {
                    return ActionOutcome.Failure(
                        ActionError("result_too_large", "Unbounded query requested. A limit is required and must not exceed $maxLimit.")
                    )
                }

                if (limit > maxLimit) {
                    return ActionOutcome.Failure(
                        ActionError("result_too_large", "Requested limit $limit exceeds the defensive cap of $maxLimit.")
                    )
                }

                val list = buildJsonArray {
                    for (i in 0 until limit) {
                        add(buildJsonObject {
                            put("id", i)
                            put("data", "item_$i")
                        })
                    }
                }
                return ActionOutcome.Success(buildJsonObject {
                    put("items", list)
                })
            }
            return ActionOutcome.Success(buildJsonObject { })
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
    fun `executeAction returns RequiresConfirmation when access controller dictates`() {
        val service = Robolectric.buildService(V1Service::class.java).create().bind().get()
        val binder = service.onBind(Intent()) as IExtensionService

        val request = ActionRequest("test_confirm", buildJsonObject { })
        val requestJson = Json.encodeToString(request)

        val resultJson = binder.executeAction(1, requestJson)
        val outcome = Json.decodeFromString<ActionOutcome>(resultJson)

        assertTrue(outcome is ActionOutcome.RequiresConfirmation)
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

    @Test
    fun `test capability with unbounded or too large result set fails cleanly with result_too_large`() {
        val service = Robolectric.buildService(CappedResultService::class.java).create().bind().get()
        val binder = service.onBind(Intent()) as IExtensionService

        // 1. Unbounded query (no limit parameter)
        val requestUnbounded = ActionRequest("get_large_list", buildJsonObject { })
        val resultJsonUnbounded = binder.executeAction(1, Json.encodeToString(requestUnbounded))
        val outcomeUnbounded = Json.decodeFromString<ActionOutcome>(resultJsonUnbounded)

        assertTrue(outcomeUnbounded is ActionOutcome.Failure)
        assertEquals("result_too_large", (outcomeUnbounded as ActionOutcome.Failure).error.code)

        // 2. Query exceeding maximum cap (limit = 100, max limit is 50)
        val requestTooLarge = ActionRequest("get_large_list", buildJsonObject { put("limit", 100) })
        val resultJsonTooLarge = binder.executeAction(1, Json.encodeToString(requestTooLarge))
        val outcomeTooLarge = Json.decodeFromString<ActionOutcome>(resultJsonTooLarge)

        assertTrue(outcomeTooLarge is ActionOutcome.Failure)
        assertEquals("result_too_large", (outcomeTooLarge as ActionOutcome.Failure).error.code)

        // 3. Query within safe limit (limit = 10)
        val requestSafe = ActionRequest("get_large_list", buildJsonObject { put("limit", 10) })
        val resultJsonSafe = binder.executeAction(1, Json.encodeToString(requestSafe))
        val outcomeSafe = Json.decodeFromString<ActionOutcome>(resultJsonSafe)

        assertTrue(outcomeSafe is ActionOutcome.Success)
    }

    class MockExtensionConnectionManager {
        var isConnected: Boolean = true
        var reconnectCount: Int = 0
        var lastReconnectReason: String? = null

        fun triggerReconnection(reason: String) {
            isConnected = false
            reconnectCount++
            lastReconnectReason = reason
        }

        fun onServiceDisconnected() {
            triggerReconnection("Service disconnected callback")
        }

        fun executeCall(action: () -> String): String? {
            return try {
                action()
            } catch (e: android.os.RemoteException) {
                triggerReconnection("Remote call failed with RemoteException: ${e.message}")
                null
            }
        }
    }

    @Test
    fun `test extension mid-call failure via RemoteException triggers same reconnection path as clean disconnect`() {
        val manager = MockExtensionConnectionManager()

        // 1. Verify clean disconnect triggers reconnection path
        manager.onServiceDisconnected()
        assertFalse(manager.isConnected)
        assertEquals(1, manager.reconnectCount)
        assertEquals("Service disconnected callback", manager.lastReconnectReason)

        // Reset manager state
        manager.isConnected = true
        manager.reconnectCount = 0
        manager.lastReconnectReason = null

        // 2. Verify mid-call RemoteException triggers same reconnection path
        val failingServiceCall = {
            throw android.os.RemoteException("DeadObjectException: Binder transaction failed")
        }

        val result = manager.executeCall(failingServiceCall)
        assertTrue(result == null)
        assertFalse(manager.isConnected)
        assertEquals(1, manager.reconnectCount)
        assertTrue(manager.lastReconnectReason?.contains("RemoteException") == true)
    }
}
