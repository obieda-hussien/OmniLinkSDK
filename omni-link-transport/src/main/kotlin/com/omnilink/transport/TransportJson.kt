package com.omnilink.transport

import kotlinx.serialization.json.Json

object TransportJson {
    @JvmField
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "type"
    }
}
