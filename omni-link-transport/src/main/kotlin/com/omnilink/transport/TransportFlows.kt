package com.omnilink.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

suspend fun SecureTransportSession.sendAsync(message: TransportMessage) {
    withContext(Dispatchers.IO) {
        send(message)
    }
}

fun SecureTransportSession.incomingMessages(): Flow<TransportMessage> = flow {
    while (isOpen) {
        val next = withContext(Dispatchers.IO) {
            receive()
        }
        emit(next)
    }
}
