package com.omnilink.sdk

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json

fun IExtensionService.observeEvents(): Flow<OmniEvent> = callbackFlow {
    val callback = object : IOmniEventCallback.Stub() {
        override fun onEvent(eventJson: String) {
            try {
                val event = Json.decodeFromString<OmniEvent>(eventJson)
                trySend(event)
            } catch (e: Exception) {
                // Ignore parse errors on the consumer side
            }
        }
    }

    val registered = registerEventListener(callback)
    if (!registered) {
        close(UnsupportedOperationException("Extension does not support events"))
    }

    awaitClose {
        try {
            unregisterEventListener(callback)
        } catch (e: Exception) {
            // Ignore RemoteException if service died
        }
    }
}
