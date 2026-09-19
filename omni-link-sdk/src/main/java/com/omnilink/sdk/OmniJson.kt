package com.omnilink.sdk

import kotlinx.serialization.json.Json

/**
 * Canonical JSON configuration for OmniLink wire traffic.
 *
 * ignoreUnknownKeys makes newer peers forward-compatible with additive fields.
 * encodeDefaults=false keeps additive default fields off the wire so older peers are not exposed
 * to fields they do not understand unless a newer protocol feature is actually being used.
 */
object OmniJson {
    @JvmField
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
}
