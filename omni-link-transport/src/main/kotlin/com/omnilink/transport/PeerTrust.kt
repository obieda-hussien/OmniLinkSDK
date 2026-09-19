package com.omnilink.transport

import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    fun permitsInbound(capability: String): Boolean =
        !isExpired() && matchesCapability(inboundCapabilities, capability)

    fun permitsOutbound(capability: String): Boolean =
        !isExpired() && matchesCapability(outboundCapabilities, capability)

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

class InMemoryPeerTrustStore(
    records: Collection<PeerTrustRecord> = emptyList()
) : PeerTrustStore {
    private val records = ConcurrentHashMap<String, PeerTrustRecord>()

    init {
        records.forEach(::put)
    }

    override fun get(peerId: String): PeerTrustRecord? = records[peerId]

    override fun put(record: PeerTrustRecord) {
        records[record.peerId] = record.copy(
            publicKeySha256 = normalizeFingerprint(record.publicKeySha256),
            expectedPlatformSignerSha256 =
                record.expectedPlatformSignerSha256.map(::normalizeFingerprint).toSet()
        )
    }

    override fun remove(peerId: String) {
        records.remove(peerId)
    }

    override fun all(): List<PeerTrustRecord> = records.values.sortedBy { it.peerId }
}

class JsonFilePeerTrustStore(
    private val file: File
) : PeerTrustStore {
    private val lock = Any()

    @Serializable
    private data class State(val records: List<PeerTrustRecord> = emptyList())

    override fun get(peerId: String): PeerTrustRecord? = synchronized(lock) {
        readState().records.firstOrNull { it.peerId == peerId }
    }

    override fun put(record: PeerTrustRecord) = synchronized(lock) {
        val current = readState().records.associateBy { it.peerId }.toMutableMap()
        current[record.peerId] = record.copy(
            publicKeySha256 = normalizeFingerprint(record.publicKeySha256),
            expectedPlatformSignerSha256 =
                record.expectedPlatformSignerSha256.map(::normalizeFingerprint).toSet()
        )
        writeState(State(current.values.sortedBy { it.peerId }))
    }

    override fun remove(peerId: String) = synchronized(lock) {
        writeState(State(readState().records.filterNot { it.peerId == peerId }))
    }

    override fun all(): List<PeerTrustRecord> = synchronized(lock) {
        readState().records.sortedBy { it.peerId }
    }

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
