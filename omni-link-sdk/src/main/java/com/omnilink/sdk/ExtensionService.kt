package com.omnilink.sdk

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

abstract class ExtensionService : Service() {

    abstract val minSupportedVersion: Int
    abstract val maxSupportedVersion: Int
    abstract val accessController: AccessController
    abstract val auditLogger: AuditLogger

    // Optional robust security validator (defaults to null for backward compatibility unless specified)
    open val securityValidator: SecurityValidator? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    abstract suspend fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome

    // Override these to support events in v2+ extensions
    protected open fun onRegisterEventListener(callback: IOmniEventCallback): Boolean {
        return false
    }

    protected open fun onUnregisterEventListener(callback: IOmniEventCallback) {
        // No-op by default
    }

    private fun resolveCaller(): CallerContext {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid)
        val callingPackage = packages?.firstOrNull() ?: "unknown"
        return CallerContext(callingUid, callingPackage)
    }

    private fun executeInternal(protocolVersion: Int, requestJson: String, callerContext: CallerContext): ActionOutcome {
        // Pre-check cryptographic signature if provided
        securityValidator?.let { validator ->
            if (!validator.isCallerAuthorized(this@ExtensionService, callerContext)) {
                 return ActionOutcome.Failure(ActionError("access_denied", "Caller failed signature verification"))
            }
        }

        if (protocolVersion !in minSupportedVersion..maxSupportedVersion) {
            return ActionOutcome.Failure(ActionError("version_mismatch", "Protocol version $protocolVersion is not supported. Supported range: $minSupportedVersion..$maxSupportedVersion"))
        }

        return try {
            val request = Json.decodeFromString<ActionRequest>(requestJson)

            val decision = accessController.decide(callerContext, request)

            if (decision == AccessDecision.DENY) {
                ActionOutcome.Failure(ActionError("access_denied", "Access denied by policy"))
            } else if (decision == AccessDecision.REQUIRES_CONFIRMATION) {
                ActionOutcome.RequiresConfirmation("Action requires confirmation")
            } else {
                runBlocking(Dispatchers.Default) {
                    onAction(callerContext, request)
                }
            }
        } catch (e: Exception) {
            ActionOutcome.Failure(ActionError("internal_error", e.message ?: "Unknown error"))
        }
    }

    private suspend fun executeInternalAsync(protocolVersion: Int, requestJson: String, callerContext: CallerContext): ActionOutcome {
        // Pre-check cryptographic signature if provided
        securityValidator?.let { validator ->
            if (!validator.isCallerAuthorized(this@ExtensionService, callerContext)) {
                 return ActionOutcome.Failure(ActionError("access_denied", "Caller failed signature verification"))
            }
        }

        if (protocolVersion !in minSupportedVersion..maxSupportedVersion) {
            return ActionOutcome.Failure(ActionError("version_mismatch", "Protocol version $protocolVersion is not supported. Supported range: $minSupportedVersion..$maxSupportedVersion"))
        }

        return try {
            val request = Json.decodeFromString<ActionRequest>(requestJson)

            val decision = accessController.decide(callerContext, request)

            if (decision == AccessDecision.DENY) {
                ActionOutcome.Failure(ActionError("access_denied", "Access denied by policy"))
            } else if (decision == AccessDecision.REQUIRES_CONFIRMATION) {
                ActionOutcome.RequiresConfirmation("Action requires confirmation")
            } else {
                onAction(callerContext, request)
            }
        } catch (e: Exception) {
            ActionOutcome.Failure(ActionError("internal_error", e.message ?: "Unknown error"))
        }
    }

    private fun logAttempt(protocolVersion: Int, requestJson: String, callerContext: CallerContext, outcome: ActionOutcome) {
         try {
            if (protocolVersion in minSupportedVersion..maxSupportedVersion) {
                 val request = Json.decodeFromString<ActionRequest>(requestJson)
                 auditLogger.log(callerContext, request, outcome)
            }
        } catch (e: Exception) {
             // ignore log error if request was malformed
        }
    }

    private val binder = object : IExtensionService.Stub() {
        override fun executeAction(protocolVersion: Int, requestJson: String): String {
            val callerContext = resolveCaller()
            val outcome = executeInternal(protocolVersion, requestJson, callerContext)
            logAttempt(protocolVersion, requestJson, callerContext, outcome)
            return Json.encodeToString(outcome)
        }

        override fun executeActionAsync(protocolVersion: Int, requestJson: String, callback: IOmniResultCallback) {
            val callerContext = resolveCaller()
            serviceScope.launch {
                val outcome = executeInternalAsync(protocolVersion, requestJson, callerContext)
                logAttempt(protocolVersion, requestJson, callerContext, outcome)
                try {
                    callback.onResult(Json.encodeToString(outcome))
                } catch (e: Exception) {
                    // Ignore DeadObjectException if caller died before result
                }
            }
        }

        override fun registerEventListener(callback: IOmniEventCallback): Boolean {
             val callerContext = resolveCaller()

             // Event buses must enforce at least the global security validator pre-check to prevent snooping
             securityValidator?.let { validator ->
                if (!validator.isCallerAuthorized(this@ExtensionService, callerContext)) {
                     return false
                }
            }

            // To enforce proper access control on event listening we simulate a "register_event_listener" action
            val request = ActionRequest("register_event_listener", kotlinx.serialization.json.buildJsonObject { })
            val decision = accessController.decide(callerContext, request)
            if (decision != AccessDecision.ALLOW) {
                return false
            }

            return onRegisterEventListener(callback)
        }

        override fun unregisterEventListener(callback: IOmniEventCallback) {
            // Unregister doesn't strictly need a policy check to succeed if it's just cleanup,
            // but we can still gate it behind the signature validator
            val callerContext = resolveCaller()
            securityValidator?.let { validator ->
                if (!validator.isCallerAuthorized(this@ExtensionService, callerContext)) {
                     return
                }
            }

            onUnregisterEventListener(callback)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }
}
