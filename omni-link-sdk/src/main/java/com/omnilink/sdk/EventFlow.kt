package com.omnilink.sdk

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Idiomatic event stream wrapper.
 *
 * The local bounded buffer prevents an unbounded heap queue when a Binder producer is faster than
 * the collector. Events should carry monotonically increasing sequence values when replay matters;
 * a consumer detecting a gap can recover through the owning extension/job replay API.
 */
fun IExtensionService.observeEvents(
    bufferCapacity: Int = OmniLinkConstants.DEFAULT_EVENT_WINDOW
): Flow<OmniEvent> = callbackFlow {
    val callback = object : IOmniEventCallback.Stub() {
        override fun onEvent(eventJson: String) {
            try {
                val event = OmniJson.instance.decodeFromString<OmniEvent>(eventJson)
                trySend(event)
            } catch (_: Exception) {
                // Malformed/unrecognized events are data errors, not a reason to crash the client.
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
        } catch (_: Exception) {
            // Remote process may already be gone.
        }
    }
}.buffer(
    capacity = bufferCapacity.coerceAtLeast(1),
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
