package com.omnilink.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concurrent request/response/event dispatcher on top of one encrypted transport session.
 *
 * The underlying session has exactly one reader; responses are correlated without allowing multiple
 * coroutines to race on the socket input stream.
 */
class OmniMultiplexedConnection(
    private val session: SecureTransportSession,
    parentScope: CoroutineScope? = null,
    requestQueueCapacity: Int = 64,
    eventBufferCapacity: Int = 128
) : Closeable {

    init {
        require(requestQueueCapacity in 1..1024) { "Request queue capacity must be bounded" }
    }

    private val ownsScope = parentScope == null
    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TransportMessage>>()
    private val requests = Channel<TransportMessage>(requestQueueCapacity)
    private val _events = MutableSharedFlow<TransportMessage>(
        extraBufferCapacity = eventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _errors = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val closed = AtomicBoolean(false)
    private val closedSignal = CompletableDeferred<Throwable?>()

    val incomingRequests: Flow<TransportMessage> = requests.receiveAsFlow()
    val events: Flow<TransportMessage> = _events.asSharedFlow()
    val errors: Flow<Throwable> = _errors.asSharedFlow()

    val remotePeerId: String
        get() = session.remotePeerId

    val sessionId: String
        get() = session.sessionId

    val isOpen: Boolean
        get() = !closed.get() && session.isOpen

    private val readerJob: Job = scope.launch(Dispatchers.IO) {
        try {
            while (session.isOpen && !closed.get()) {
                val message = session.receive()
                when (message.type) {
                    TransportMessageType.RESPONSE -> {
                        val id = message.correlationId
                        if (id != null) {
                            val deferred = pending.remove(id)
                            if (deferred != null) {
                                deferred.complete(message)
                            } else {
                                _events.emit(message)
                            }
                        } else {
                            _events.emit(message)
                        }
                    }

                    TransportMessageType.REQUEST -> {
                        // The sole socket reader must never suspend behind a slow application
                        // consumer: doing so also stalls responses, pings and authorization.
                        if (!requests.trySend(message).isSuccess) {
                            throw TransportOverloadedException(
                                "Incoming request queue full for peer '${session.remotePeerId}'"
                            )
                        }
                    }
                    TransportMessageType.EVENT,
                    TransportMessageType.STREAM_CHUNK -> _events.emit(message)
                    TransportMessageType.CONTROL -> handleControl(message)
                }
            }
        } catch (cancelled: CancellationException) {
            if (!closedSignal.isCompleted) closedSignal.complete(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            if (!closed.get()) {
                _errors.emit(error)
                failPending(error)
            }
            if (!closedSignal.isCompleted) closedSignal.complete(error)
        } finally {
            requests.close()
            if (!closed.get()) {
                closed.set(true)
                session.close()
            }
            if (!closedSignal.isCompleted) closedSignal.complete(null)
        }
    }

    suspend fun request(
        capability: String,
        payload: ByteArray = byteArrayOf(),
        metadata: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 30_000,
        correlationId: String = UUID.randomUUID().toString()
    ): TransportMessage {
        check(isOpen) { "Transport connection is closed" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val deferred = CompletableDeferred<TransportMessage>()
        val previous = pending.putIfAbsent(correlationId, deferred)
        require(previous == null) { "Duplicate correlationId '$correlationId'" }

        try {
            session.sendAsync(
                TransportMessage(
                    type = TransportMessageType.REQUEST,
                    capability = capability,
                    correlationId = correlationId,
                    metadata = metadata,
                    payload = payload
                )
            )
            return withTimeout(timeoutMillis) {
                deferred.await()
            }
        } finally {
            pending.remove(correlationId, deferred)
        }
    }

    suspend fun requestUtf8(
        capability: String,
        text: String,
        metadata: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 30_000,
        correlationId: String = UUID.randomUUID().toString()
    ): TransportMessage = request(
        capability = capability,
        payload = text.toByteArray(Charsets.UTF_8),
        metadata = metadata,
        timeoutMillis = timeoutMillis,
        correlationId = correlationId
    )

    suspend fun respond(
        request: TransportMessage,
        payload: ByteArray = byteArrayOf(),
        capability: String = request.capability,
        metadata: Map<String, String> = emptyMap()
    ) {
        val correlationId = requireNotNull(request.correlationId) {
            "Cannot respond to a request without correlationId"
        }
        session.sendAsync(
            TransportMessage(
                type = TransportMessageType.RESPONSE,
                capability = capability,
                correlationId = correlationId,
                metadata = metadata,
                payload = payload
            )
        )
    }

    suspend fun respondUtf8(
        request: TransportMessage,
        text: String,
        capability: String = request.capability,
        metadata: Map<String, String> = emptyMap()
    ) = respond(
        request = request,
        payload = text.toByteArray(Charsets.UTF_8),
        capability = capability,
        metadata = metadata
    )

    suspend fun ping(timeoutMillis: Long = 5_000): Long {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        check(isOpen) { "Transport connection is closed" }

        val correlationId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TransportMessage>()
        check(pending.putIfAbsent(correlationId, deferred) == null)

        val startedAt = System.nanoTime()
        try {
            session.sendAsync(
                TransportMessage(
                    type = TransportMessageType.CONTROL,
                    capability = TRANSPORT_PING,
                    correlationId = correlationId
                )
            )
            withTimeout(timeoutMillis) { deferred.await() }
            return (System.nanoTime() - startedAt) / 1_000_000L
        } finally {
            pending.remove(correlationId, deferred)
        }
    }

    suspend fun awaitClosed(): Throwable? = closedSignal.await()

    suspend fun emitEvent(
        capability: String,
        payload: ByteArray = byteArrayOf(),
        metadata: Map<String, String> = emptyMap(),
        correlationId: String? = null
    ) {
        session.sendAsync(
            TransportMessage(
                type = TransportMessageType.EVENT,
                capability = capability,
                correlationId = correlationId,
                metadata = metadata,
                payload = payload
            )
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session.close()
        readerJob.cancel()
        requests.close()
        failPending(OmniTransportException("Transport connection closed"))
        if (!closedSignal.isCompleted) closedSignal.complete(null)
        if (ownsScope) {
            scope.cancel()
        }
    }

    private suspend fun handleControl(message: TransportMessage) {
        when (message.capability) {
            TRANSPORT_PING -> {
                session.sendAsync(
                    TransportMessage(
                        type = TransportMessageType.CONTROL,
                        capability = TRANSPORT_PONG,
                        correlationId = message.correlationId
                    )
                )
            }

            TRANSPORT_PONG -> {
                val id = message.correlationId
                val waiter = id?.let { pending.remove(it) }
                if (waiter != null) waiter.complete(message) else _events.emit(message)
            }

            else -> _events.emit(message)
        }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { deferred ->
            deferred.completeExceptionally(error)
        }
        pending.clear()
    }
}
