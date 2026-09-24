package com.omnilink.sdk

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class GrantPrincipal(
    val packageName: String,
    val signerSha256: Set<String>
) {
    init {
        require(packageName.isNotBlank() && packageName.length <= 255) { "packageName must be 1..255 characters" }
        require(signerSha256.isNotEmpty() && signerSha256.size <= MAX_SIGNER_COUNT &&
            signerSha256.all { normalizeFingerprint(it) != null }
        ) {
            "signerSha256 must contain one or more SHA-256 fingerprints"
        }
    }

    internal fun canonical(): GrantPrincipal = copy(
        signerSha256 = signerSha256.mapNotNull(::normalizeFingerprint).toSortedSet()
    )

    internal fun matches(other: GrantPrincipal): Boolean =
        packageName == other.packageName &&
            signerSha256.mapNotNull(::normalizeFingerprint).toSet() ==
            other.signerSha256.mapNotNull(::normalizeFingerprint).toSet()

    companion object {
        const val MAX_SIGNER_COUNT = 8
    }
}

/** An exact resource scope. Global access must be explicit; wildcards are not interpreted. */
@Serializable
data class CapabilityResourceScope(
    val resourceIds: Set<String> = emptySet(),
    val global: Boolean = false
) {
    init {
        require(global.xor(resourceIds.isNotEmpty())) {
            "scope must be either explicitly global or contain exact resource IDs"
        }
        require(resourceIds.all { it.isNotBlank() && it.length <= MAX_RESOURCE_ID_LENGTH }) {
            "resource IDs must be non-blank and at most $MAX_RESOURCE_ID_LENGTH characters"
        }
        require(resourceIds.size <= MAX_SCOPE_RESOURCES) {
            "scope cannot contain more than $MAX_SCOPE_RESOURCES resources"
        }
    }

    companion object {
        const val MAX_RESOURCE_ID_LENGTH = 512
        const val MAX_SCOPE_RESOURCES = 64
    }
}

@Serializable
data class CapabilityGrantRequest(
    val requester: GrantPrincipal,
    val provider: GrantPrincipal,
    val capability: String,
    val resourceScope: CapabilityResourceScope,
    val purpose: String,
    val dataScopes: Set<DataScope> = emptySet(),
    val risk: CapabilityRisk = CapabilityRisk.LOW,
    val requestId: String,
    val actionId: String? = null,
    val sessionId: String? = null
) {
    init {
        require(capability.isNotBlank() && capability.length <= MAX_CAPABILITY_LENGTH)
        require(requestId.isNotBlank() && requestId.length <= MAX_TOKEN_LENGTH)
        require(actionId == null || actionId.isNotBlank() && actionId.length <= MAX_TOKEN_LENGTH)
        require(sessionId == null || sessionId.isNotBlank() && sessionId.length <= MAX_TOKEN_LENGTH)
        require(purpose.isNotBlank() && purpose.length <= MAX_PURPOSE_LENGTH)
    }

    companion object {
        const val MAX_CAPABILITY_LENGTH = 128
        const val MAX_TOKEN_LENGTH = 256
        const val MAX_PURPOSE_LENGTH = 2_048
        const val MAX_GRANT_RECORDS = 2_048
        const val MAX_BLOCKED_PAIRS = 512
    }

    internal fun canonical(): CapabilityGrantRequest = copy(
        requester = requester.canonical(),
        provider = provider.canonical(),
        resourceScope = resourceScope.copy(resourceIds = resourceScope.resourceIds.toSet()),
        dataScopes = dataScopes.toSet()
    )
}

@Serializable
enum class CapabilityGrantDecision {
    THIS_ACTION,
    THIS_CAPABILITY_ONCE,
    THIS_SESSION,
    ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE,
    DENY,
    BLOCK
}

@Serializable
data class CapabilityGrantRecord(
    val grantId: String,
    val requester: GrantPrincipal,
    val provider: GrantPrincipal,
    val capability: String,
    val resourceScope: CapabilityResourceScope,
    val purpose: String,
    val dataScopes: Set<DataScope>,
    val risk: CapabilityRisk,
    val decision: CapabilityGrantDecision,
    val requestId: String,
    val actionId: String? = null,
    val sessionId: String? = null,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null
)

@Serializable
data class BlockedAppPair(
    val requester: GrantPrincipal,
    val provider: GrantPrincipal
)

@Serializable
data class CapabilityGrantSnapshot(
    val grants: List<CapabilityGrantRecord> = emptyList(),
    val blockedPairs: List<BlockedAppPair> = emptyList()
)

interface CapabilityGrantPersistence {
    /** Load must throw on corruption. Returning an empty snapshot on parse failure grants fail-open behavior. */
    fun load(): CapabilityGrantSnapshot
    /** Must durably commit before returning true. */
    fun save(snapshot: CapabilityGrantSnapshot): Boolean
    val supportsPersistentGrants: Boolean
}

class InMemoryCapabilityGrantPersistence : CapabilityGrantPersistence {
    private var snapshot = CapabilityGrantSnapshot()
    override val supportsPersistentGrants: Boolean = false

    @Synchronized
    override fun load(): CapabilityGrantSnapshot = snapshot.detachedCopy()

    @Synchronized
    override fun save(snapshot: CapabilityGrantSnapshot): Boolean {
        this.snapshot = snapshot.detachedCopy()
        return true
    }
}

class GrantStoreUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

@Serializable
private data class EncryptedGrantEnvelope(val version: Int, val iv: String, val ciphertext: String)

/**
 * Private app storage encrypted with an Android Keystore AES-GCM key. On Android versions without
 * Android Keystore AES support, persistent grants are unavailable and the ledger fails closed.
 */
class AndroidEncryptedCapabilityGrantPersistence(
    context: Context,
    private val preferencesName: String = "omnilink_capability_grants"
) : CapabilityGrantPersistence {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val aad = "${appContext.packageName}:$preferencesName:$ENVELOPE_VERSION".toByteArray(Charsets.UTF_8)

    override val supportsPersistentGrants: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    override fun load(): CapabilityGrantSnapshot = synchronized(PREFERENCES_LOCK) {
        if (!supportsPersistentGrants) {
            throw GrantStoreUnavailableException("Android Keystore-backed grants require API 23 or newer")
        }
        val encoded = preferences.getString(KEY_SNAPSHOT, null) ?: return@synchronized CapabilityGrantSnapshot()
        try {
            require(encoded.length <= MAX_ENCRYPTED_ENVELOPE_CHARS) { "encrypted grant state exceeds size limit" }
            val envelope = OmniJson.instance.decodeFromString(EncryptedGrantEnvelope.serializer(), encoded)
            require(envelope.version == ENVELOPE_VERSION)
            val iv = Base64.decode(envelope.iv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
            require(iv.size == GCM_IV_BYTES && ciphertext.size >= GCM_TAG_BYTES / 8)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BYTES, iv))
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            OmniJson.instance.decodeFromString(CapabilityGrantSnapshot.serializer(), plaintext)
        } catch (error: Exception) {
            throw GrantStoreUnavailableException("Encrypted capability grant data is unreadable; authorization is disabled", error)
        }
    }

    override fun save(snapshot: CapabilityGrantSnapshot): Boolean = synchronized(PREFERENCES_LOCK) {
        if (!supportsPersistentGrants) return@synchronized false
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(aad)
            val plaintext = OmniJson.instance.encodeToString(CapabilityGrantSnapshot.serializer(), snapshot)
                .toByteArray(Charsets.UTF_8)
            require(plaintext.size <= MAX_GRANT_SNAPSHOT_BYTES) { "capability grant state exceeds size limit" }
            val encrypted = cipher.doFinal(plaintext)
            val envelope = EncryptedGrantEnvelope(
                version = ENVELOPE_VERSION,
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            )
            preferences.edit()
                .putString(KEY_SNAPSHOT, OmniJson.instance.encodeToString(EncryptedGrantEnvelope.serializer(), envelope))
                .commit()
        } catch (error: Exception) {
            throw GrantStoreUnavailableException("Could not durably save capability grants", error)
        }
    }

    /** Call only after host UI has made the user review this unreadable grant store. */
    fun resetAfterUserReview(): Boolean = synchronized(PREFERENCES_LOCK) {
        val cleared = runCatching { preferences.edit().remove(KEY_SNAPSHOT).commit() }.getOrDefault(false)
        if (!cleared) return@synchronized false
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(keyAlias(appContext.packageName, preferencesName))
        }.isSuccess
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = keyAlias(appContext.packageName, preferencesName)
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw GrantStoreUnavailableException("Android Keystore AES keys require API 23 or newer")
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun keyAlias(packageName: String, name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName:$name".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "omnilink_grants_v1_$digest"
    }

    private companion object {
        const val KEY_SNAPSHOT = "encrypted_snapshot_v1"
        const val ENVELOPE_VERSION = 1
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MAX_GRANT_SNAPSHOT_BYTES = 2 * 1024 * 1024
        const val MAX_ENCRYPTED_ENVELOPE_CHARS = 3 * 1024 * 1024
        val PREFERENCES_LOCK = Any()
    }
}

sealed interface CapabilityAuthorization {
    data class Allowed(val grantId: String, val decision: CapabilityGrantDecision) : CapabilityAuthorization
    data class Denied(val reason: String) : CapabilityAuthorization
}

enum class GrantMutationResult {
    RECORDED,
    REQUIRES_DURABLE_STORAGE,
    INVALID_REQUEST,
    LIMIT_REACHED,
    STORAGE_UNAVAILABLE
}

/**
 * Synchronous, exact-scope grant ledger. Call from a worker thread when using Android encrypted
 * persistence because every update is committed before it can authorize an operation.
 */
class CapabilityGrantLedger(
    private val persistence: CapabilityGrantPersistence,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() }
) {
    private val lock = Any()
    private val revocationListeners = CopyOnWriteArraySet<(Set<String>) -> Unit>()
    private var storageHealthy = true
    private var snapshot = try {
        persistence.load().deepCopy().canonical()
    } catch (_: Exception) {
        storageHealthy = false
        CapabilityGrantSnapshot()
    }

    fun addRevocationListener(listener: (Set<String>) -> Unit): Closeable {
        revocationListeners += listener
        return Closeable { revocationListeners -= listener }
    }

    fun recordDecision(
        request: CapabilityGrantRequest,
        decision: CapabilityGrantDecision
    ): GrantMutationResult {
        val normalized = request.canonical()
        var revokedIds = emptySet<String>()
        val result = synchronized(lock) {
            if (!storageHealthy) return@synchronized GrantMutationResult.STORAGE_UNAVAILABLE
            if (decision in setOf(
                    CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE,
                    CapabilityGrantDecision.BLOCK
                ) &&
                !persistence.supportsPersistentGrants
            ) return@synchronized GrantMutationResult.REQUIRES_DURABLE_STORAGE
            if (decision == CapabilityGrantDecision.THIS_ACTION && normalized.actionId == null) {
                return@synchronized GrantMutationResult.INVALID_REQUEST
            }
            if (decision == CapabilityGrantDecision.THIS_CAPABILITY_ONCE && normalized.requestId.isBlank()) {
                return@synchronized GrantMutationResult.INVALID_REQUEST
            }
            if (decision == CapabilityGrantDecision.THIS_SESSION && normalized.sessionId == null) {
                return@synchronized GrantMutationResult.INVALID_REQUEST
            }
            if (decision == CapabilityGrantDecision.DENY && normalized.actionId == null && normalized.requestId.isBlank()) {
                return@synchronized GrantMutationResult.INVALID_REQUEST
            }

            val currentTime = nowEpochMs()
            val previous = snapshot
            val matching = snapshot.grants.filter { it.sameTarget(normalized) }
            val kept = snapshot.grants.filterNot { it.sameTarget(normalized) }
            revokedIds = matching.filter { it.decision.isAllow() }.map { it.grantId }.toSet()
            val newGrantId = idGenerator()
            if (newGrantId.isBlank() || newGrantId.length > CapabilityGrantRequest.MAX_TOKEN_LENGTH ||
                snapshot.grants.any { it.grantId == newGrantId }
            ) return@synchronized GrantMutationResult.INVALID_REQUEST
            val newRecord = CapabilityGrantRecord(
                grantId = newGrantId,
                requester = normalized.requester,
                provider = normalized.provider,
                capability = normalized.capability,
                resourceScope = normalized.resourceScope,
                purpose = normalized.purpose,
                dataScopes = normalized.dataScopes,
                risk = normalized.risk,
                decision = decision,
                requestId = normalized.requestId,
                actionId = normalized.actionId,
                sessionId = normalized.sessionId,
                createdAtEpochMs = currentTime,
                expiresAtEpochMs = expiryFor(decision, currentTime)
            )
            val blocked = snapshot.blockedPairs.toMutableList()
            if (decision == CapabilityGrantDecision.BLOCK) {
                blocked.removeAll { it.samePair(normalized.requester, normalized.provider) }
                blocked += BlockedAppPair(normalized.requester, normalized.provider)
                // A block applies to the complete app pair, so revoke every existing grant for it.
                val pairGrants = snapshot.grants.filter {
                    it.requester.matches(normalized.requester) && it.provider.matches(normalized.provider) && it.decision.isAllow()
                }
                revokedIds = pairGrants.map { it.grantId }.toSet()
                val updated = CapabilityGrantSnapshot(
                    grants = kept.filterNot {
                        it.requester.matches(normalized.requester) && it.provider.matches(normalized.provider)
                    },
                    blockedPairs = blocked
                )
                if (updated.grants.size > CapabilityGrantRequest.MAX_GRANT_RECORDS ||
                    updated.blockedPairs.size > CapabilityGrantRequest.MAX_BLOCKED_PAIRS
                ) return@synchronized GrantMutationResult.LIMIT_REACHED
                persistOrRollback(previous, updated) ?: return@synchronized GrantMutationResult.STORAGE_UNAVAILABLE
                return@synchronized GrantMutationResult.RECORDED
            }
            val updated = CapabilityGrantSnapshot(
                grants = kept + newRecord,
                blockedPairs = blocked
            )
            if (updated.grants.size > CapabilityGrantRequest.MAX_GRANT_RECORDS ||
                updated.blockedPairs.size > CapabilityGrantRequest.MAX_BLOCKED_PAIRS
            ) return@synchronized GrantMutationResult.LIMIT_REACHED
            persistOrRollback(previous, updated) ?: return@synchronized GrantMutationResult.STORAGE_UNAVAILABLE
            GrantMutationResult.RECORDED
        }
        if (result != GrantMutationResult.RECORDED && storageHealthy) {
            revokedIds = emptySet()
        }
        notifyRevoked(revokedIds)
        return result
    }

    fun authorize(request: CapabilityGrantRequest): CapabilityAuthorization {
        val normalized = request.canonical()
        var revokedIds = emptySet<String>()
        val result = synchronized(lock) {
            if (!storageHealthy) return@synchronized CapabilityAuthorization.Denied("grant_store_unavailable")
            if (snapshot.blockedPairs.any { it.samePair(normalized.requester, normalized.provider) }) {
                return@synchronized CapabilityAuthorization.Denied("app_pair_blocked")
            }
            val currentTime = nowEpochMs()
            val expired = snapshot.grants.filter { it.expiresAtEpochMs?.let { expiry -> expiry <= currentTime } == true }
            if (expired.isNotEmpty()) {
                revokedIds = expired.filter { it.decision.isAllow() }.map { it.grantId }.toSet()
                val previous = snapshot
                val updated = snapshot.copy(grants = snapshot.grants - expired.toSet())
                if (persistOrRollback(previous, updated) == null) {
                    return@synchronized CapabilityAuthorization.Denied("grant_store_unavailable")
                }
            }

            val targetRecords = snapshot.grants.filter { it.sameTarget(normalized) }
            val denial = targetRecords.lastOrNull {
                it.decision == CapabilityGrantDecision.DENY &&
                    it.matchesConsentDetails(normalized) && it.matchesAction(normalized)
            }
            if (denial != null) return@synchronized CapabilityAuthorization.Denied("user_denied")

            val grant = targetRecords.lastOrNull {
                it.matchesConsentDetails(normalized) && it.matchesGrant(normalized)
            }
                ?: return@synchronized CapabilityAuthorization.Denied("no_matching_grant")

            if (grant.decision == CapabilityGrantDecision.THIS_ACTION ||
                grant.decision == CapabilityGrantDecision.THIS_CAPABILITY_ONCE
            ) {
                val previous = snapshot
                val updated = snapshot.copy(grants = snapshot.grants - grant)
                if (persistOrRollback(previous, updated) == null) {
                    return@synchronized CapabilityAuthorization.Denied("grant_store_unavailable")
                }
            }
            CapabilityAuthorization.Allowed(grant.grantId, grant.decision)
        }
        notifyRevoked(revokedIds)
        return result
    }

    fun revoke(grantId: String): Boolean {
        var revokedIds = emptySet<String>()
        val success = synchronized(lock) {
            if (!storageHealthy) return@synchronized false
            val match = snapshot.grants.firstOrNull { it.grantId == grantId } ?: return@synchronized false
            revokedIds = if (match.decision.isAllow()) setOf(grantId) else emptySet()
            val previous = snapshot
            val updated = snapshot.copy(grants = snapshot.grants.filterNot { it.grantId == grantId })
            if (persistOrRollback(previous, updated) == null) return@synchronized false
            true
        }
        notifyRevoked(revokedIds)
        return success
    }

    fun revokeIdentity(identity: GrantPrincipal): Int {
        val principal = identity.canonical()
        var revokedIds = emptySet<String>()
        val count = synchronized(lock) {
            if (!storageHealthy) return@synchronized 0
            val removed = snapshot.grants.filter {
                it.requester.matches(principal) || it.provider.matches(principal)
            }
            val blocked = snapshot.blockedPairs.filterNot {
                it.requester.matches(principal) || it.provider.matches(principal)
            }
            if (removed.isEmpty() && blocked.size == snapshot.blockedPairs.size) return@synchronized 0
            revokedIds = removed.filter { it.decision.isAllow() }.map { it.grantId }.toSet()
            val previous = snapshot
            val updated = snapshot.copy(grants = snapshot.grants - removed.toSet(), blockedPairs = blocked)
            if (persistOrRollback(previous, updated) == null) return@synchronized 0
            removed.size
        }
        notifyRevoked(revokedIds)
        return count
    }

    fun revokeCapability(
        requester: GrantPrincipal,
        provider: GrantPrincipal,
        capability: String,
        resourceScope: CapabilityResourceScope? = null
    ): Int {
        val requesterKey = requester.canonical()
        val providerKey = provider.canonical()
        var revokedIds = emptySet<String>()
        val count = synchronized(lock) {
            if (!storageHealthy) return@synchronized 0
            val removed = snapshot.grants.filter {
                it.decision.isAllow() &&
                    it.requester.matches(requesterKey) &&
                    it.provider.matches(providerKey) &&
                    it.capability == capability &&
                    (resourceScope == null || it.resourceScope == resourceScope)
            }
            if (removed.isEmpty()) return@synchronized 0
            revokedIds = removed.map { it.grantId }.toSet()
            val previous = snapshot
            val updated = snapshot.copy(grants = snapshot.grants - removed.toSet())
            if (persistOrRollback(previous, updated) == null) return@synchronized 0
            removed.size
        }
        notifyRevoked(revokedIds)
        return count
    }

    fun endSession(sessionId: String): Int {
        if (sessionId.isBlank()) return 0
        var revokedIds = emptySet<String>()
        val count = synchronized(lock) {
            if (!storageHealthy) return@synchronized 0
            val removed = snapshot.grants.filter {
                it.decision == CapabilityGrantDecision.THIS_SESSION && it.sessionId == sessionId
            }
            if (removed.isEmpty()) return@synchronized 0
            revokedIds = removed.map { it.grantId }.toSet()
            val previous = snapshot
            val updated = snapshot.copy(grants = snapshot.grants - removed.toSet())
            if (persistOrRollback(previous, updated) == null) return@synchronized 0
            removed.size
        }
        notifyRevoked(revokedIds)
        return count
    }

    fun unblockPair(requester: GrantPrincipal, provider: GrantPrincipal): Boolean {
        val requesterKey = requester.canonical()
        val providerKey = provider.canonical()
        return synchronized(lock) {
            if (!storageHealthy) return@synchronized false
            val updatedBlocks = snapshot.blockedPairs.filterNot {
                it.samePair(requesterKey, providerKey)
            }
            if (updatedBlocks.size == snapshot.blockedPairs.size) return@synchronized false
            val previous = snapshot
            val updated = snapshot.copy(blockedPairs = updatedBlocks)
            persistOrRollback(previous, updated) != null
        }
    }

    /** Returns a detached snapshot for host review UI; modifying it cannot change the ledger. */
    fun currentSnapshot(): CapabilityGrantSnapshot = synchronized(lock) { snapshot.deepCopy() }

    private fun persistOrRollback(previous: CapabilityGrantSnapshot, updated: CapabilityGrantSnapshot): Boolean? {
        snapshot = updated
        val stored = runCatching { persistence.save(updated.deepCopy()) }.getOrDefault(false)
        if (!stored) {
            snapshot = previous
            storageHealthy = false
            return null
        }
        return true
    }

    private fun expiryFor(decision: CapabilityGrantDecision, now: Long): Long? = when (decision) {
        CapabilityGrantDecision.THIS_ACTION,
        CapabilityGrantDecision.THIS_CAPABILITY_ONCE,
        CapabilityGrantDecision.DENY -> now + ONE_TIME_TTL_MS
        CapabilityGrantDecision.THIS_SESSION -> now + SESSION_MAX_TTL_MS
        CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE,
        CapabilityGrantDecision.BLOCK -> null
    }

    private fun notifyRevoked(ids: Set<String>) {
        if (ids.isEmpty()) return
        revocationListeners.forEach { listener -> runCatching { listener(ids) } }
    }

    private fun CapabilityGrantSnapshot.canonical() = copy(
        grants = grants.map {
            it.copy(
                requester = it.requester.canonical(),
                provider = it.provider.canonical(),
                resourceScope = it.resourceScope.copy(resourceIds = it.resourceScope.resourceIds.toSet()),
                dataScopes = it.dataScopes.toSet()
            )
        },
        blockedPairs = blockedPairs.map {
            BlockedAppPair(it.requester.canonical(), it.provider.canonical())
        }
    )

    private fun CapabilityGrantSnapshot.deepCopy() = detachedCopy()

    private fun CapabilityGrantRecord.sameTarget(request: CapabilityGrantRequest): Boolean =
        requester.matches(request.requester) && provider.matches(request.provider) &&
            capability == request.capability && resourceScope == request.resourceScope

    private fun CapabilityGrantRecord.matchesConsentDetails(request: CapabilityGrantRequest): Boolean =
        purpose == request.purpose && dataScopes == request.dataScopes && risk == request.risk

    private fun CapabilityGrantRecord.matchesAction(request: CapabilityGrantRequest): Boolean =
        actionId != null && actionId == request.actionId ||
            actionId == null && requestId == request.requestId

    private fun CapabilityGrantRecord.matchesGrant(request: CapabilityGrantRequest): Boolean = when (decision) {
        CapabilityGrantDecision.THIS_ACTION -> actionId != null && actionId == request.actionId
        CapabilityGrantDecision.THIS_CAPABILITY_ONCE -> requestId == request.requestId
        CapabilityGrantDecision.THIS_SESSION -> sessionId != null && sessionId == request.sessionId
        CapabilityGrantDecision.ALWAYS_FOR_THIS_CAPABILITY_AND_SCOPE -> true
        CapabilityGrantDecision.DENY,
        CapabilityGrantDecision.BLOCK -> false
    }

    private fun CapabilityGrantDecision.isAllow(): Boolean = this != CapabilityGrantDecision.DENY && this != CapabilityGrantDecision.BLOCK

    private fun BlockedAppPair.samePair(requester: GrantPrincipal, provider: GrantPrincipal): Boolean =
        this.requester.matches(requester) && this.provider.matches(provider)

    private companion object {
        const val ONE_TIME_TTL_MS = 10 * 60 * 1000L
        const val SESSION_MAX_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

private fun normalizeFingerprint(value: String): String? {
    val normalized = value.replace(":", "").replace(" ", "").lowercase()
    return normalized.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
}

private fun CapabilityGrantSnapshot.detachedCopy(): CapabilityGrantSnapshot = copy(
    grants = grants.map {
        it.copy(
            requester = it.requester.copy(signerSha256 = it.requester.signerSha256.toSet()),
            provider = it.provider.copy(signerSha256 = it.provider.signerSha256.toSet()),
            resourceScope = it.resourceScope.copy(resourceIds = it.resourceScope.resourceIds.toSet()),
            dataScopes = it.dataScopes.toSet()
        )
    },
    blockedPairs = blockedPairs.map {
        BlockedAppPair(
            it.requester.copy(signerSha256 = it.requester.signerSha256.toSet()),
            it.provider.copy(signerSha256 = it.provider.signerSha256.toSet())
        )
    }
)
