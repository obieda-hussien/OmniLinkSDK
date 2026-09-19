package com.omnilink.sdk

import android.content.Context
import com.omnilink.transport.PeerTrustRecord
import com.omnilink.transport.PeerTrustStore
import com.omnilink.transport.TransportJson
import kotlinx.serialization.Serializable

/**
 * Persistent Android-side peer pinning. Records contain only public fingerprints/policy, never
 * private key material.
 */
class AndroidPeerTrustStore(
    context: Context,
    name: String = "omnilink_transport_trust"
) : PeerTrustStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        name,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    @Serializable
    private data class State(val records: List<PeerTrustRecord> = emptyList())

    override fun get(peerId: String): PeerTrustRecord? = synchronized(lock) {
        readState().records.firstOrNull { it.peerId == peerId }
    }

    override fun put(record: PeerTrustRecord) = synchronized(lock) {
        val map = readState().records.associateBy { it.peerId }.toMutableMap()
        map[record.peerId] = record
        writeState(State(map.values.sortedBy { it.peerId }))
    }

    override fun remove(peerId: String) = synchronized(lock) {
        writeState(State(readState().records.filterNot { it.peerId == peerId }))
    }

    override fun all(): List<PeerTrustRecord> = synchronized(lock) {
        readState().records.sortedBy { it.peerId }
    }

    private fun readState(): State {
        val json = preferences.getString(KEY_STATE, null) ?: return State()
        return try {
            TransportJson.instance.decodeFromString(State.serializer(), json)
        } catch (_: Exception) {
            State()
        }
    }

    private fun writeState(state: State) {
        preferences.edit()
            .putString(
                KEY_STATE,
                TransportJson.instance.encodeToString(State.serializer(), state)
            )
            .apply()
    }

    private companion object {
        const val KEY_STATE = "peer_trust_state"
    }
}
