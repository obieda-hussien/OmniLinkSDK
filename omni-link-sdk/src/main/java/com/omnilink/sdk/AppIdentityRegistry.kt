package com.omnilink.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Identity categories are host policy labels, never implicit permission bundles. */
enum class AppClassification {
    OMNI_CORE,
    OFFICIAL_OMNI_APP,
    VERIFIED_PARTNER,
    USER_APPROVED_APP,
    RECOGNIZED_APP,
    UNKNOWN_APP,
    BLOCKED_APP
}

enum class OmniApplicationRole {
    CORE_CONTROLLER,
    CAPABILITY_PROVIDER,
    EXTERNAL_APPLICATION,
    DESKTOP_PEER
}

/**
 * Host-owned registry entry. A package name and one of its actual signing certificates must
 * match. Entries received from a remote manifest must never be passed as this registry.
 */
data class HostAppRegistration(
    val packageName: String,
    val signerSha256: Set<String>,
    val classification: AppClassification,
    val roles: Set<OmniApplicationRole> = emptySet(),
    /** A policy ceiling only; per-operation authorization and consent remain separate. */
    val capabilityCeiling: Set<String> = emptySet()
)

/** Explicit host/user policy record bound to the installed signing identity. */
data class UserApprovedApp(
    val packageName: String,
    val signerSha256: Set<String>,
    val roles: Set<OmniApplicationRole> = setOf(OmniApplicationRole.EXTERNAL_APPLICATION),
    val capabilityCeiling: Set<String> = emptySet()
)

/** Facts read locally from PackageManager for one installed package. */
data class InstalledAppIdentityFacts(
    val packageName: String,
    val uid: Int,
    val currentSignerSha256: Set<String>,
    val signerHistorySha256: Set<String>,
    val installerPackageName: String?
)

data class ResolvedAppIdentity(
    val packageName: String,
    val uid: Int?,
    val classification: AppClassification,
    val roles: Set<OmniApplicationRole> = emptySet(),
    /** Maximum capabilities supplied by a host registry entry, not an active grant. */
    val capabilityCeiling: Set<String> = emptySet(),
    val currentSignerSha256: Set<String> = emptySet(),
    val signerHistorySha256: Set<String> = emptySet(),
    val installerPackageName: String? = null,
    val cryptographicIdentityMatched: Boolean = false
)

/**
 * Resolves classification using Android-installed signer facts and policy owned by the host.
 * Store/installer metadata is returned for display/audit only and never changes classification.
 */
class HostAppIdentityRegistry(
    private val hostPackageName: String,
    hostSignerSha256: Set<String>,
    registrations: Collection<HostAppRegistration> = emptyList(),
    userApprovedApps: Collection<UserApprovedApp> = emptyList(),
    recognizedPackages: Set<String> = emptySet(),
    blockedPackages: Set<String> = emptySet(),
    blockedSignerSha256: Set<String> = emptySet()
) {
    private val hostSigners = hostSignerSha256.mapNotNull(::normalizeFingerprint).toSet()
    private val registrationByPackage = registrations.associateBy { it.packageName }
    private val userApprovalByPackage = userApprovedApps.associateBy { it.packageName }
    private val normalizedBlockedSigners = blockedSignerSha256.mapNotNull(::normalizeFingerprint).toSet()

    init {
        require(hostPackageName.isNotBlank()) { "hostPackageName must not be blank" }
        require(hostSigners.isNotEmpty()) { "hostSignerSha256 must contain a valid SHA-256 fingerprint" }
        require(registrations.map { it.packageName }.distinct().size == registrations.size) {
            "duplicate host app registration package"
        }
        require(userApprovedApps.map { it.packageName }.distinct().size == userApprovedApps.size) {
            "duplicate user-approved package"
        }
        require(registrations.all {
            it.classification == AppClassification.OFFICIAL_OMNI_APP ||
                it.classification == AppClassification.VERIFIED_PARTNER
        }) { "host registrations may only classify official Omni apps or verified partners" }
    }

    fun classify(facts: InstalledAppIdentityFacts): ResolvedAppIdentity {
        val current = facts.currentSignerSha256.mapNotNull(::normalizeFingerprint).toSet()
        val history = facts.signerHistorySha256.mapNotNull(::normalizeFingerprint).toSet()
        val observedSigners = current + history

        fun result(
            classification: AppClassification,
            roles: Set<OmniApplicationRole> = emptySet(),
            ceiling: Set<String> = emptySet(),
            matched: Boolean = false
        ) = ResolvedAppIdentity(
            packageName = facts.packageName,
            uid = facts.uid,
            classification = classification,
            roles = roles,
            capabilityCeiling = ceiling,
            currentSignerSha256 = current,
            signerHistorySha256 = history,
            installerPackageName = facts.installerPackageName,
            cryptographicIdentityMatched = matched
        )

        if (facts.packageName in blockedPackages || observedSigners.any(normalizedBlockedSigners::contains)) {
            return result(AppClassification.BLOCKED_APP)
        }

        // CORE is assigned only to the explicitly configured host package with a matching
        // locally observed signer. Sharing the same certificate alone never promotes another app.
        if (facts.packageName == hostPackageName && current.any(hostSigners::contains)) {
            return result(
                classification = AppClassification.OMNI_CORE,
                roles = setOf(OmniApplicationRole.CORE_CONTROLLER),
                matched = true
            )
        }

        val registration = registrationByPackage[facts.packageName]
        if (registration != null) {
            val expected = registration.signerSha256.mapNotNull(::normalizeFingerprint).toSet()
            if (expected.isNotEmpty() && observedSigners.any(expected::contains)) {
                return result(
                    registration.classification,
                    registration.roles,
                    registration.capabilityCeiling,
                    matched = true
                )
            }
        }

        val approval = userApprovalByPackage[facts.packageName]
        if (approval != null) {
            val expected = approval.signerSha256.mapNotNull(::normalizeFingerprint).toSet()
            if (expected.isNotEmpty() && current.any(expected::contains)) {
                return result(
                    AppClassification.USER_APPROVED_APP,
                    approval.roles,
                    approval.capabilityCeiling,
                    matched = true
                )
            }
        }

        if (facts.packageName in recognizedPackages) {
            return result(AppClassification.RECOGNIZED_APP)
        }
        return result(AppClassification.UNKNOWN_APP)
    }

    private val blockedPackages = blockedPackages.toSet()

    private fun normalizeFingerprint(value: String): String? {
        val normalized = value.replace(":", "").replace(" ", "").lowercase()
        return normalized.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    }
}

/** Loads identity facts from Android, rather than from a remote app manifest or installer claim. */
class AndroidAppIdentityResolver(context: Context) {
    private val appContext = context.applicationContext

    fun readInstalledPackage(packageName: String): InstalledAppIdentityFacts? = runCatching {
        val pm = appContext.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val appInfo = packageInfo.applicationInfo ?: return@runCatching null
        val currentSigners: List<android.content.pm.Signature>
        val signerHistory: List<android.content.pm.Signature>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return@runCatching null
            currentSigners = signingInfo.apkContentsSigners.orEmpty().toList()
            signerHistory = if (signingInfo.hasMultipleSigners()) {
                currentSigners
            } else {
                signingInfo.signingCertificateHistory.orEmpty().toList()
            }
        } else {
            @Suppress("DEPRECATION")
            val legacySigners = packageInfo.signatures.orEmpty().toList()
            currentSigners = legacySigners
            signerHistory = legacySigners
        }
        InstalledAppIdentityFacts(
            packageName = packageName,
            uid = appInfo.uid,
            currentSignerSha256 = currentSigners.mapTo(linkedSetOf(), ::sha256),
            signerHistorySha256 = signerHistory.mapTo(linkedSetOf(), ::sha256),
            installerPackageName = installerPackageName(pm, packageName)
        )
    }.getOrNull()

    private fun installerPackageName(pm: PackageManager, packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    private fun sha256(signature: android.content.pm.Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
