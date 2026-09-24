package com.omnilink.sdk

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString

abstract class ExtensionService : Service() {

    abstract val minSupportedVersion: Int
    abstract val maxSupportedVersion: Int
    abstract val accessController: AccessController
    abstract val auditLogger: AuditLogger

    /**
     * Privileged OmniLink endpoints are fail-closed by default. First-party apps sharing the Omni
     * signing certificate work without per-app hash configuration. Partner integrations must opt in
     * explicitly by overriding this validator.
     */
    open val securityValidator: SecurityValidator = SameSignerSecurityValidator()

    /**
     * Trust and capability authorization are independent from authentication. Same signature proves
     * who the caller is; it does not grant every capability automatically.
     */
    open val trustResolver: TrustResolver = DefaultTrustResolver()

    open val maxInlineRequestBytes: Int = OmniLinkConstants.DEFAULT_MAX_INLINE_JSON_BYTES
    open val maxConcurrentAsyncActions: Int = OmniLinkConstants.DEFAULT_ASYNC_CONCURRENCY

    /**
     * Discoverable capability metadata. Legacy services may keep this empty. Once a service declares
     * capabilities, undeclared action names are rejected and sync calls are allowed only for
     * IMMEDIATE capabilities.
     */
    open val capabilities: List<CapabilityDescriptor> = emptyList()

    open val capabilityManifest: CapabilityManifest
        get() = CapabilityManifest(
            protocolVersion = maxSupportedVersion,
            sdkVersion = OmniLinkConstants.SDK_VERSION,
            minSupportedVersion = minSupportedVersion,
            maxSupportedVersion = maxSupportedVersion,
            capabilities = capabilities,
            maxInlinePayloadBytes = maxInlineRequestBytes
        )

    private val serviceJob: Job = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val asyncSemaphore by lazy {
        Semaphore(maxConcurrentAsyncActions.coerceAtLeast(1))
    }

    abstract suspend fun onAction(caller: CallerContext, request: ActionRequest): ActionOutcome

    // Override these to support events in v2+ extensions.
    protected open fun onRegisterEventListener(callback: IOmniEventCallback): Boolean = false

    protected open fun onUnregisterEventListener(callback: IOmniEventCallback) = Unit

    private data class PreparedCall(
        val request: ActionRequest? = null,
        val terminalOutcome: ActionOutcome? = null
    )

    private fun resolveCaller(): CallerContext =
        CallerIdentityResolver.resolve(this, Binder.getCallingUid())

    private fun prepareCall(
        protocolVersion: Int,
        requestJson: String,
        caller: CallerContext,
        synchronous: Boolean
    ): PreparedCall {
        if (!securityValidator.isCallerAuthorized(this, caller)) {
            return PreparedCall(
                terminalOutcome = ActionOutcome.Failure(
                    ActionError("access_denied", "Caller failed OmniLink identity verification")
                )
            )
        }

        if (protocolVersion !in minSupportedVersion..maxSupportedVersion) {
            return PreparedCall(
                terminalOutcome = ActionOutcome.Failure(
                    ActionError(
                        "version_mismatch",
                        "Protocol version $protocolVersion is not supported. " +
                            "Supported range: $minSupportedVersion..$maxSupportedVersion"
                    )
                )
            )
        }

        if (requestJson.toByteArray(Charsets.UTF_8).size > maxInlineRequestBytes) {
            return PreparedCall(
                terminalOutcome = ActionOutcome.Failure(
                    ActionError(
                        "payload_too_large",
                        "Inline request exceeds the $maxInlineRequestBytes byte OmniLink limit. " +
                            "Use a paged/job/large-payload transport instead."
                    )
                )
            )
        }

        val request = try {
            OmniJson.instance.decodeFromString<ActionRequest>(requestJson)
        } catch (e: Exception) {
            return PreparedCall(
                terminalOutcome = ActionOutcome.Failure(
                    ActionError("invalid_request", e.message ?: "Malformed ActionRequest")
                )
            )
        }

        if (request.deadlineEpochMs?.let { System.currentTimeMillis() > it } == true) {
            return PreparedCall(
                request = request,
                terminalOutcome = ActionOutcome.Failure(
                    ActionError("deadline_exceeded", "Request deadline elapsed before execution")
                )
            )
        }

        val descriptor = capabilities.firstOrNull { it.name == request.name }
        if (capabilities.isNotEmpty() && descriptor == null) {
            return PreparedCall(
                request = request,
                terminalOutcome = ActionOutcome.Failure(
                    ActionError("unknown_capability", "Capability '${request.name}' is not declared")
                )
            )
        }

        if (synchronous && descriptor != null &&
            descriptor.executionMode != CapabilityExecutionMode.IMMEDIATE
        ) {
            return PreparedCall(
                request = request,
                terminalOutcome = ActionOutcome.Failure(
                    ActionError(
                        "async_required",
                        "Capability '${request.name}' must use executeActionAsync/JOB execution"
                    )
                )
            )
        }

        if (request.dryRun && descriptor != null && !descriptor.supportsDryRun) {
            return PreparedCall(
                request = request,
                terminalOutcome = ActionOutcome.Failure(
                    ActionError(
                        "dry_run_unsupported",
                        "Capability '${request.name}' has no dry-run contract"
                    )
                )
            )
        }

        if (descriptor != null) {
            val principal = trustResolver.resolve(this, caller)
            if (!CapabilityTrustPolicy.isAllowed(
                    principal,
                    descriptor,
                    InvocationDirection.OMNI_TO_APP
                )
            ) {
                return PreparedCall(
                    request = request,
                    terminalOutcome = ActionOutcome.Failure(
                        ActionError(
                            "capability_denied",
                            "Caller trust tier ${principal.tier} is not authorized for '${request.name}'"
                        )
                    )
                )
            }
        }

        return when (accessController.decide(caller, request)) {
            AccessDecision.DENY -> PreparedCall(
                request,
                ActionOutcome.Failure(ActionError("access_denied", "Access denied by policy"))
            )
            AccessDecision.REQUIRES_CONFIRMATION -> PreparedCall(
                request,
                ActionOutcome.RequiresConfirmation("Action requires confirmation")
            )
            AccessDecision.ALLOW -> PreparedCall(request = request)
        }
    }

    private fun logAttempt(caller: CallerContext, request: ActionRequest?, outcome: ActionOutcome) {
        if (request == null) return
        try {
            auditLogger.log(caller, request, outcome)
        } catch (_: Exception) {
            // Audit failures must not crash the Binder endpoint. Consumers should make their logger
            // durable and observable, but execution outcome remains authoritative.
        }
    }

    private fun executeSync(
        protocolVersion: Int,
        requestJson: String,
        caller: CallerContext
    ): ActionOutcome {
        val prepared = prepareCall(protocolVersion, requestJson, caller, synchronous = true)
        prepared.terminalOutcome?.let {
            logAttempt(caller, prepared.request, it)
            return it
        }

        val request = prepared.request
            ?: return ActionOutcome.Failure(
                ActionError("internal_error", "Request preparation failed")
            )

        val outcome = try {
            // This path is intentionally reserved for declared IMMEDIATE work.
            runBlocking(Dispatchers.Default) {
                onAction(caller, request)
            }
        } catch (e: Exception) {
            ActionOutcome.Failure(ActionError("internal_error", e.message ?: "Unknown error"))
        }
        logAttempt(caller, request, outcome)
        return outcome
    }

    private suspend fun executeAsync(
        protocolVersion: Int,
        requestJson: String,
        caller: CallerContext
    ): ActionOutcome = asyncSemaphore.withPermit {
        val prepared = prepareCall(protocolVersion, requestJson, caller, synchronous = false)
        prepared.terminalOutcome?.let {
            logAttempt(caller, prepared.request, it)
            return@withPermit it
        }

        val request = prepared.request
            ?: return@withPermit ActionOutcome.Failure(
                ActionError("internal_error", "Request preparation failed")
            )

        val outcome = try {
            onAction(caller, request)
        } catch (e: Exception) {
            ActionOutcome.Failure(ActionError("internal_error", e.message ?: "Unknown error"))
        }
        logAttempt(caller, request, outcome)
        outcome
    }

    private val binder = object : IExtensionService.Stub() {
        override fun getCapabilityManifest(): String =
            // Qualify the OUTER service property explicitly. The AIDL Stub exposes a Java
            // getCapabilityManifest() method which Kotlin also sees as a synthetic property
            // on this anonymous Binder object. Unqualified "capabilityManifest" resolves
            // back to this Binder getter, recurses, and crashes the hosting app with
            // StackOverflowError during discovery (confirmed by AndroidIDE logcat).
            OmniJson.instance.encodeToString(this@ExtensionService.capabilityManifest)

        override fun executeAction(protocolVersion: Int, requestJson: String): String {
            val outcome = executeSync(protocolVersion, requestJson, resolveCaller())
            return OmniJson.instance.encodeToString(outcome)
        }

        override fun executeActionAsync(
            protocolVersion: Int,
            requestJson: String,
            callback: IOmniResultCallback
        ) {
            val caller = resolveCaller()
            serviceScope.launch {
                val outcome = executeAsync(protocolVersion, requestJson, caller)
                try {
                    callback.onResult(OmniJson.instance.encodeToString(outcome))
                } catch (_: Exception) {
                    // Caller may have died/cancelled while the work was running.
                }
            }
        }

        override fun registerEventListener(callback: IOmniEventCallback): Boolean {
            val caller = resolveCaller()
            if (!securityValidator.isCallerAuthorized(this@ExtensionService, caller)) return false

            val request = ActionRequest(
                name = "register_event_listener",
                payload = kotlinx.serialization.json.JsonObject(emptyMap())
            )
            if (accessController.decide(caller, request) != AccessDecision.ALLOW) return false
            return onRegisterEventListener(callback)
        }

        override fun unregisterEventListener(callback: IOmniEventCallback) {
            val caller = resolveCaller()
            if (!securityValidator.isCallerAuthorized(this@ExtensionService, caller)) return
            onUnregisterEventListener(callback)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
