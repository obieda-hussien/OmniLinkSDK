package com.omnilink.sdk

object OmniLinkConstants {
    /**
     * Runtime SDK version. The value is generated from OMNILINK_VERSION in gradle.properties,
     * which is also used by Maven publication and the release workflow.
     */
    @JvmField
    val SDK_VERSION: String = BuildConfig.OMNILINK_SDK_VERSION

    /**
     * v2 keeps the deployed AIDL transaction ordering intact. Protocol 5 marks the stricter
     * trusted/public integration contract while negotiation remains backward-compatible with v4.
     */
    const val CURRENT_PROTOCOL_VERSION = 5
    const val SESSION_PROTOCOL_VERSION = 2

    const val DEFAULT_MAX_INLINE_JSON_BYTES = 256 * 1024
    const val DEFAULT_ASYNC_CONCURRENCY = 8
    const val DEFAULT_EVENT_WINDOW = 64

    const val PERMISSION_BIND_EXTENSION = "com.omnilink.sdk.permission.BIND_EXTENSION"
    const val ACTION_EXTENSION_BIND = "com.omnilink.sdk.action.EXTENSION_BIND"

    const val PERMISSION_BIND_AGENT = "com.omnilink.sdk.permission.BIND_AGENT"
    const val ACTION_AGENT_GATEWAY_BIND = "com.omnilink.sdk.action.AGENT_GATEWAY_BIND"

    /**
     * Deliberately unprivileged entry point for narrowly-scoped share/ask/open requests.
     * Workspace must still sanitize the payload and apply user/policy confirmation.
     */
    const val ACTION_PUBLIC_OMNI_REQUEST = "com.omnilink.sdk.action.PUBLIC_OMNI_REQUEST"
    const val EXTRA_PUBLIC_REQUEST_JSON = "com.omnilink.sdk.extra.PUBLIC_REQUEST_JSON"
}
