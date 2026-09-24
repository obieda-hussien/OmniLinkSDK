package com.omnilink.transport

import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

@Serializable
data class PeerTrustRecord(
    val peerId: String,
    val publicKeySha256: String,
    val trustLevel: TransportTrustLevel,
    val inboundCapabilities: Set<String> = emptySet(),
    val outboundCapabilities: Set<String> = emptySet(),
    val expectedPlatformSignerSha256: Set<String> = emptySet(),
    val expiresAtEpochMs: Long? = null,
    val notes: String? = null
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs?.let { now >= it } ?: false

    fun permitsInbound(capability: String): Boolean {
        if (isExpired()) return false
        if (isInternalTransportCapability(capability)) {
            return trustLevel != TransportTrustLevel.UNTRUSTED
        }
        return trustCeilingAllows(capability) &&
            matchesCapability(inboundCapabilities, capability)
    }

    fun permitsOutbound(capability: String): Boolean {
        if (isExpired()) return false
        if (isInternalTransportCapability(capability)) {
            return trustLevel != TransportTrustLevel.UNTRUSTED
        }
        return trustCeilingAllows(capability) &&
            matchesCapability(outboundCapabilities, capability)
    }

    private fun trustCeilingAllows(capability: String): Boolean = when (trustLevel) {
        TransportTrustLevel.UNTRUSTED -> false
        TransportTrustLevel.PAIRED -> isChatSandboxCapability(capability)
        TransportTrustLevel.TRUSTED_PARTNER,
        TransportTrustLevel.FIRST_PARTY,
        TransportTrustLevel.CORE -> true
    }

    fun verifyCandidate(candidate: PeerCandidate): Boolean {
        if (peerId != candidate.peerId) return false
        if (!constantTimeEqualsHex(publicKeySha256, candidate.publicKeySha256)) return false
        if (expectedPlatformSignerSha256.isNotEmpty()) {
            val expected = expectedPlatformSignerSha256.map(::normalizeFingerprint).toSet()
            val actual = candidate.platformSignerSha256.map(::normalizeFingerprint).toSet()
            if (!actual.containsAll(expected)) return false
        }
        return !isExpired()
    }
}

internal const val TRANSPORT_PING = "_transport.ping"
internal const val TRANSPORT_PONG = "_transport.pong"

private fun isInternalTransportCapability(capability: String): Boolean =
    capability == TRANSPORT_PING || capability == TRANSPORT_PONG

private val chatSandboxPrefixes = listOf(
    "chat.",
    "search.",
    "summarize.",
    "translate.",
    "extract."
)

private fun isChatSandboxCapability(capability: String): Boolean =
    chatSandboxPrefixes.any(capability::startsWith)

private fun matchesCapability(patterns: Set<String>, capability: String): Boolean {
    return patterns.any { pattern ->
        when {
            pattern == "*" -> true
            pattern.endsWith(".*") -> capability.startsWith(pattern.removeSuffix("*"))
            else -> pattern == capability
        }
    }
}

interface PeerTrustStore {
    fun get(peerId: String): PeerTrustRecord?
    fun put(record: PeerTrustRecord)
    fun remove(peerId: String)
    fun all(): List<PeerTrustRecord>
}

/** Implemented by stores that can close active sessions as soon as trust changes. */
interface SessionTrustStore : PeerTrustStore {
    fun onPeerChanged(peerId: String, callback: () -> Unit): Closeable
}

private class SessionListeners {
    private val callbacks = ConcurrentHashMap<String, CopyOnWriteArraySet<() -> Unit>>()

    fun subscribe(peerId: String, callback: () -> Unit): Closeable {
        val set = callbacks.computeIfAbsent(peerId) { CopyOnWriteArraySet() }
        set.add(callback)
        return Closeable {
            set.remove(callback)
            if (set.isEmpty()) callbacks.remove(peerId, set)
        }
    }

    fun changed(peerId: String) {
        callbacks[peerId]?.forEach { it() }
    }
}

class InMemoryPeerTrustStore(
    records: Collection<PeerTrustRecord> = emptyList()
) : SessionTrustStore {
    private val records = ConcurrentHashMap<String, PeerTrustRecord>()
    private val sessions = SessionListeners()

    init {
        records.forEach(::put)
    }

    override fun get(peerId: String): PeerTrustRecord? = records[peerId]

    override fun put(record: PeerTrustRecord) {
        val normalized = record.copy(
            publicKeySha256 = normalizeFingerprint(record.publicKeySha256),
            expectedPlatformSignerSha256 =
                record.expectedPlatformSignerSha256.map(::normalizeFingerprint).toSet()
        )
        val previous = records.put(record.peerId, normalized)
        if (previous != null && previous != normalized) sessions.changed(record.peerId)
    }

    override fun remove(peerId: String) {
        if (records.remove(peerId) != null) sessions.changed(peerId)
    }

    override fun all(): List<PeerTrustRecord> = records.values.sortedBy { it.peerId }

    override fun onPeerChanged(peerId: String, callback: () -> Unit): Closeable =
        sessions.subscribe(peerId, callback)
}

class JsonFilePeerTrustStore(
    private val file: File
) : SessionTrustStore {
    private val lock = Any()
    private val sessions = SessionListeners()

    @Serializable
    private data class State(val records: List<PeerTrustRecord> = emptyList())

    override fun get(peerId: String): PeerTrustRecord? = synchronized(lock) {
        readState().records.firstOrNull { it.peerId == peerId }
    }

    override fun put(record: PeerTrustRecord) = synchronized(lock) {
        val current = readState().records.associateBy { it.peerId }.toMutableMap()
        val normalized = record.copy(
            publicKeySha256 = normalizeFingerprint(record.publicKeySha256),
            expectedPlatformSignerSha256 =
                record.expectedPlatformSignerSha256.map(::normalizeFingerprint).toSet()
        )
        val previous = current.put(record.peerId, normalized)
        writeState(State(current.values.sortedBy { it.peerId }))
        if (previous != null && previous != normalized) sessions.changed(record.peerId)
    }

    override fun remove(peerId: String) = synchronized(lock) {
        val current = readState().records
        if (current.any { it.peerId == peerId }) {
            writeState(State(current.filterNot { it.peerId == peerId }))
            sessions.changed(peerId)
        }
    }

    override fun all(): List<PeerTrustRecord> = synchronized(lock) {
        readState().records.sortedBy { it.peerId }
    }

    override fun onPeerChanged(peerId: String, callback: () -> Unit): Closeable =
        sessions.subscribe(peerId, callback)

    private fun readState(): State {
        if (!file.exists()) return State()
        val text = file.readText()
        if (text.isBlank()) return State()
        return TransportJson.instance.decodeFromString(State.serializer(), text)
    }

    private fun writeState(state: State) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: File("."), file.name + ".tmp")
        temp.writeText(TransportJson.instance.encodeToString(State.serializer(), state))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }
}

fun interface PeerAdmissionHandler {
    /**
     * Return a trust record to accept and optionally persist an unknown peer, or null to reject it.
     * Implementations should present/verify [PeerCandidate.pairingCode] or a full fingerprint with
     * the user rather than silently trusting arbitrary LAN peers.
     */
    fun admit(candidate: PeerCandidate): PeerTrustRecord?
}

object StrictPeerAdmission : PeerAdmissionHandler {
    override fun admit(candidate: PeerCandidate): PeerTrustRecord? = null
}
