package com.omnilink.sdk

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

abstract class ExtensionService : Service() {

    abstract val minSupportedVersion: Int
    abstract val maxSupportedVersion: Int
    abstract val accessController: AccessController
    abstract val auditLogger: AuditLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    abstract suspend fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome

    private val binder = object : IExtensionService.Stub() {
        override fun executeAction(protocolVersion: Int, requestJson: String): String {
            val callingUid = Binder.getCallingUid()
            val packages = packageManager.getPackagesForUid(callingUid)
            val callingPackage = packages?.firstOrNull() ?: "unknown"

            val callerContext = CallerContext(callingUid, callingPackage)

            var parsedRequest: ActionRequest? = null

            val outcome: ActionOutcome = try {
                if (protocolVersion !in minSupportedVersion..maxSupportedVersion) {
                    ActionOutcome.Failure(ActionError("version_mismatch", "Protocol version $protocolVersion is not supported. Supported range: $minSupportedVersion..$maxSupportedVersion"))
                } else {
                    val request = Json.decodeFromString<ActionRequest>(requestJson)
                    parsedRequest = request

                    val decision = accessController.decide(callerContext, request)

                    if (decision == AccessDecision.DENY) {
                        ActionOutcome.Failure(ActionError("access_denied", "Access denied by policy"))
                    } else if (decision == AccessDecision.REQUIRES_CONFIRMATION) {
                        ActionOutcome.Failure(ActionError("requires_confirmation", "Action requires confirmation"))
                    } else {
                        runBlocking(Dispatchers.Default) {
                            onAction(callerContext, request)
                        }
                    }
                }
            } catch (e: Exception) {
                ActionOutcome.Failure(ActionError("internal_error", e.message ?: "Unknown error"))
            }

            // Log attempt
            // In case of a parse error we can't log the request, so we only log if request is known
            try {
                if (parsedRequest != null) {
                     auditLogger.log(callerContext, parsedRequest, outcome)
                } else {
                    // Try to decode one more time if version mismatched but json was valid
                    val request = Json.decodeFromString<ActionRequest>(requestJson)
                    auditLogger.log(callerContext, request, outcome)
                }
            } catch (e: Exception) {
                 // ignore log error if request was malformed
            }

            return Json.encodeToString(outcome)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }
}
