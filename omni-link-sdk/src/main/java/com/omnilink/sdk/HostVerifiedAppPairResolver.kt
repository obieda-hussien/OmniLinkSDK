package com.omnilink.sdk

import android.content.Context

/** Reads fresh installed-package facts for each authorization attempt. */
fun interface InstalledIdentitySource {
    fun read(packageName: String): InstalledAppIdentityFacts?
}

/**
 * Binds an outgoing operation to the current APK signing identities. The host must configure its
 * own registry; unknown targets still need an explicit exact-scope grant before execution.
 * Package visibility failures fail closed. Only current APK signers can match a grant: signing
 * history is useful for classification, but must not resurrect a grant issued to an old signer.
 */
class HostVerifiedAppPairResolver(
    private val hostPackageName: String,
    private val targetPackageName: String,
    private val identities: InstalledIdentitySource,
    private val registry: HostAppIdentityRegistry,
    private val expectedHostUid: Int? = null
) : VerifiedAppPairResolver {
    override fun resolve(): Pair<GrantPrincipal, GrantPrincipal>? {
        val host = identities.read(hostPackageName) ?: return null
        val target = identities.read(targetPackageName) ?: return null
        if (host.packageName != hostPackageName || target.packageName != targetPackageName ||
            expectedHostUid?.let { host.uid != it } == true
        ) return null
        val hostPolicy = registry.classify(host)
        val targetPolicy = registry.classify(target)
        if (hostPolicy.classification != AppClassification.OMNI_CORE ||
            !hostPolicy.cryptographicIdentityMatched ||
            targetPolicy.classification == AppClassification.BLOCKED_APP
        ) return null
        val hostCurrent = hostPolicy.currentSignerSha256
        val targetCurrent = targetPolicy.currentSignerSha256
        if (hostCurrent.isEmpty() || targetCurrent.isEmpty() ||
            hostCurrent.size > GrantPrincipal.MAX_SIGNER_COUNT ||
            targetCurrent.size > GrantPrincipal.MAX_SIGNER_COUNT
        ) return null
        return GrantPrincipal(hostPackageName, hostCurrent) to
            GrantPrincipal(targetPackageName, targetCurrent)
    }

    companion object {
        /** Android host convenience constructor; never use a peer-supplied package list here. */
        fun android(context: Context, targetPackageName: String, registry: HostAppIdentityRegistry):
            HostVerifiedAppPairResolver {
            val app = context.applicationContext
            val resolver = AndroidAppIdentityResolver(app)
            return HostVerifiedAppPairResolver(
                app.packageName, targetPackageName, resolver::readInstalledPackage,
                registry, app.applicationInfo.uid
            )
        }
    }
}
