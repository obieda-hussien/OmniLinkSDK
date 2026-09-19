package com.omnilink.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Narrow, unprivileged ingress for third-party apps. This is intentionally separate from
 * PERMISSION_BIND_AGENT. Workspace should expose it through a user-visible Intent/share surface,
 * never by weakening the privileged Agent Gateway permission.
 */
@Serializable
data class PublicOmniRequest(
    val kind: PublicRequestKind,
    val text: String? = null,
    val url: String? = null,
    val mimeType: String? = null,
    val extras: JsonObject = JsonObject(emptyMap()),
    val clientRequestId: String? = null
)

@Serializable
enum class PublicRequestKind {
    SHARE_TO_OMNI,
    ASK_OMNI,
    OPEN_OMNI
}

@Serializable
sealed interface PublicOmniDecision {
    @Serializable
    data class Accepted(
        val requestId: String,
        val requiresUserConfirmation: Boolean = true
    ) : PublicOmniDecision

    @Serializable
    data class Rejected(
        val code: String,
        val message: String
    ) : PublicOmniDecision
}

object PublicGatewayPolicy {
    const val MAX_PUBLIC_REQUEST_BYTES = 32 * 1024

    fun validate(requestJson: String): PublicOmniDecision.Rejected? {
        if (requestJson.toByteArray(Charsets.UTF_8).size > MAX_PUBLIC_REQUEST_BYTES) {
            return PublicOmniDecision.Rejected(
                "payload_too_large",
                "Public Omni requests are limited to $MAX_PUBLIC_REQUEST_BYTES bytes"
            )
        }
        return null
    }
}
