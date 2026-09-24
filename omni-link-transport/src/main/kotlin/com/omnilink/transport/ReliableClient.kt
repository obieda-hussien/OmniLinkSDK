package com.omnilink.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.random.Random

data class RetryPolicy(
    val initialDelayMillis: Long = 500,
    val maxDelayMillis: Long = 30_000,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.20
) {
    init {
        require(initialDelayMillis >= 0)
        require(maxDelayMillis >= initialDelayMillis)
        require(multiplier >= 1.0)
        require(jitterRatio in 0.0..1.0)
    }

    fun delayForAttempt(attempt: Int, randomUnit: Double = Random.nextDouble()): Long {
        require(attempt >= 1)
        val exponent = Math.pow(multiplier, (attempt - 1).toDouble())
        val base = min(maxDelayMillis.toDouble(), initialDelayMillis * exponent)
        if (base == 0.0 || jitterRatio == 0.0) return base.toLong()

        val boundedRandom = randomUnit.coerceIn(0.0, 1.0)
        val jitter = base * jitterRatio * ((boundedRandom * 2.0) - 1.0)
        return (base + jitter).coerceIn(0.0, maxDelayMillis.toDouble()).toLong()
    }
}

data class CircuitBreakerPolicy(
    val failureThreshold: Int = 5,
    val openDurationMillis: Long = 30_000
) {
    init {
        require(failureThreshold > 0)
        require(openDurationMillis > 0)
    }
}

data class HeartbeatPolicy(
    val intervalMillis: Long = 15_000,
    val timeoutMillis: Long = 5_000
) {
    init {
        require(intervalMillis > 0)
        require(timeoutMillis > 0)
    }
}

sealed interface ReliableConnectionState {
    data object Stopped : ReliableConnectionState
    data class Connecting(val attempt: Int) : ReliableConnectionState
    data class Connected(
        val peerId: String,
        val sessionId: String,
        val lastRoundTripMillis: Long? = null
    ) : ReliableConnectionState
    data class BackingOff(
        val attempt: Int,
        val delayMillis: Long,
        val lastError: String
    ) : ReliableConnectionState
    data class CircuitOpen(
        val untilEpochMs: Long,
        val consecutiveFailures: Int,
        val lastError: String
    ) : ReliableConnectionState
}

class OmniReliableClient(
    private val host: String,
    private val port: Int = OmniTransportConstants.DEFAULT_PORT,
    private val identity: SigningIdentity,
    private val trustStore: PeerTrustStore,
    private val admissionHandler: PeerAdmissionHandler = StrictPeerAdmission,
    private val transportConfig: TransportConfig = TransportConfig(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val circuitBreakerPolicy: CircuitBreakerPolicy = CircuitBreakerPolicy(),
    private val heartbeatPolicy: HeartbeatPolicy = HeartbeatPolicy()
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var supervisor: Job? = null
    private var closed = false
    private val _state = MutableStateFlow<ReliableConnectionState>(
        ReliableConnectionState.Stopped
    )

    @Volatile
    private var activeConnection: OmniMultiplexedConnection? = null

    val state: StateFlow<ReliableConnectionState> = _state.asStateFlow()

    fun connectionOrNull(): OmniMultiplexedConnection? =
        activeConnection?.takeIf { it.isOpen }

    /** Repeated calls share one supervisor; after [stop] completes this client can restart. */
    fun start(): Job = synchronized(lifecycleLock) {
        check(!closed) { "Client has been closed" }
        supervisor?.let { existing ->
            if (!existing.isCompleted) {
                check(!existing.isCancelled) { "Wait for stop() to complete before restarting" }
                return existing
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { supervise() }
        supervisor = job
        job.invokeOnCompletion {
            synchronized(lifecycleLock) {
                if (supervisor === job) {
                    activeConnection?.close()
                    activeConnection = null
                    supervisor = null
                    _state.value = ReliableConnectionState.Stopped
                }
            }
        }
        job.start()
        job
    }

    private suspend fun supervise() {
        var attempt = 0
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            attempt++
            _state.value = ReliableConnectionState.Connecting(attempt)

            try {
                // Socket.connect is blocking. Finish it before observing cancellation so a
                // successfully connected socket cannot be abandoned by a cancelled dispatcher.
                val raw = withContext(Dispatchers.IO + NonCancellable) {
                    OmniTcpClient.connect(
                        host = host,
                        port = port,
                        identity = identity,
                        trustStore = trustStore,
                        admissionHandler = admissionHandler,
                        config = transportConfig
                    )
                }
                if (!currentCoroutineContext().isActive) {
                    raw.close()
                    throw CancellationException("Client stopped while connecting")
                }
                val connection = OmniMultiplexedConnection(raw, scope)
                activeConnection = connection
                attempt = 0
                consecutiveFailures = 0
                _state.value = ReliableConnectionState.Connected(
                    peerId = connection.remotePeerId,
                    sessionId = connection.sessionId
                )

                while (currentCoroutineContext().isActive && connection.isOpen) {
                    delay(heartbeatPolicy.intervalMillis)
                    val rtt = connection.ping(heartbeatPolicy.timeoutMillis)
                    _state.value = ReliableConnectionState.Connected(
                        peerId = connection.remotePeerId,
                        sessionId = connection.sessionId,
                        lastRoundTripMillis = rtt
                    )
                }

                connection.awaitClosed()?.let { throw it }
                throw OmniTransportException("Connection closed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                activeConnection?.close()
                activeConnection = null
                consecutiveFailures++

                if (consecutiveFailures >= circuitBreakerPolicy.failureThreshold) {
                    val until = System.currentTimeMillis() +
                        circuitBreakerPolicy.openDurationMillis
                    _state.value = ReliableConnectionState.CircuitOpen(
                        untilEpochMs = until,
                        consecutiveFailures = consecutiveFailures,
                        lastError = error.message ?: error::class.java.simpleName
                    )
                    delay(circuitBreakerPolicy.openDurationMillis)
                    consecutiveFailures = 0
                    attempt = 0
                    continue
                }

                val retryAttempt = attempt.coerceAtLeast(1)
                val backoff = retryPolicy.delayForAttempt(retryAttempt)
                _state.value = ReliableConnectionState.BackingOff(
                    attempt = retryAttempt,
                    delayMillis = backoff,
                    lastError = error.message ?: error::class.java.simpleName
                )
                delay(backoff)
            }
        }
    }

    /** Cancel the supervisor and return its job so callers can join before [start] again. */
    fun stop(): Job? = synchronized(lifecycleLock) {
        supervisor?.also { it.cancel() }
        activeConnection?.close()
        activeConnection = null
        _state.value = ReliableConnectionState.Stopped
        supervisor
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            stop()
            scope.cancel()
        }
    }
}
