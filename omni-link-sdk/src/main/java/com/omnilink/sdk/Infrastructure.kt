package com.omnilink.sdk

enum class AccessDecision {
    ALLOW,
    DENY,
    REQUIRES_CONFIRMATION
}

interface AccessController {
    fun decide(caller: CallerContext, request: ActionRequest): AccessDecision
}

interface AuditLogger {
    fun log(caller: CallerContext, request: ActionRequest, result: ActionOutcome)
}
