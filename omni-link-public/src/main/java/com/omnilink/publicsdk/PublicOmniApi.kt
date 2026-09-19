package com.omnilink.publicsdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Public OmniLink surface for unknown/untrusted third-party apps.
 *
 * This module intentionally contains no privileged Binder/AIDL interfaces and no FIRST_PARTY
 * trust helpers. A host may expose these requests through an Activity/share surface and must still
 * apply sanitization, rate limits and user policy.
 */
object OmniPublicConstants {
    @JvmField
    val SDK_VERSION: String = BuildConfig.OMNILINK_SDK_VERSION

    const val ACTION_PUBLIC_OMNI_REQUEST = "com.omnilink.sdk.action.PUBLIC_OMNI_REQUEST"
    const val EXTRA_PUBLIC_REQUEST_JSON = "com.omnilink.sdk.extra.PUBLIC_REQUEST_JSON"
    const val MAX_PUBLIC_REQUEST_BYTES = 32 * 1024
}

@Serializable
enum class PublicRequestKind {
    SHARE_TO_OMNI,
    ASK_OMNI,
    OPEN_OMNI
}

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
    fun validate(requestJson: String): PublicOmniDecision.Rejected? {
        if (requestJson.toByteArray(Charsets.UTF_8).size > OmniPublicConstants.MAX_PUBLIC_REQUEST_BYTES) {
            return PublicOmniDecision.Rejected(
                code = "payload_too_large",
                message = "Public Omni requests are limited to " +
                    OmniPublicConstants.MAX_PUBLIC_REQUEST_BYTES + " bytes"
            )
        }
        return null
    }
}
