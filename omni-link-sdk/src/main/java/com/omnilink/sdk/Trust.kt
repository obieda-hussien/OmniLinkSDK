package com.omnilink.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.serialization.Serializable

/**
 * Trust is identity, not authority. A tier only determines the maximum authority a caller may
 * receive; [AccessController] and capability policy still decide each concrete operation.
 */
@Serializable
enum class TrustTier(val rank: Int) {
    UNTRUSTED(0),
    TRUSTED_PARTNER(1),
    FIRST_PARTY(2),
    CORE(3)
}

@Serializable
enum class InvocationDirection {
    OMNI_TO_APP,
    APP_TO_OMNI,
    APP_TO_APP
}

@Serializable
enum class CommunicationDirection {
    BIDIRECTIONAL,
    OMNI_TO_APP_ONLY,
    APP_TO_OMNI_ONLY;

    fun allows(direction: InvocationDirection): Boolean = when (this) {
        BIDIRECTIONAL -> true
        OMNI_TO_APP_ONLY -> direction == InvocationDirection.OMNI_TO_APP
        APP_TO_OMNI_ONLY -> direction == InvocationDirection.APP_TO_OMNI
    }
}

@Serializable
enum class DataScope {
    PUBLIC,
    OMNI_ECOSYSTEM,
    OMNI_AGENT,
    USER_CONFIRMATION_REQUIRED,
    APP_ONLY
}

@Serializable
data class TrustPrincipal(
    val tier: TrustTier,
    val packageNames: List<String>,
    val signerSha256: List<String> = emptyList(),
    val allowedCapabilities: Set<String> = emptySet(),
    val reason: String = ""
)

@Serializable
data class TrustedPartnerRule(
    val signerSha256: Set<String>,
    val allowedCapabilities: Set<String>
)

interface TrustResolver {
    fun resolve(context: Context, caller: CallerContext): TrustPrincipal
}

/**
 * Default Omni trust model:
 *  - CORE: same signer + explicitly named core package.
 *  - FIRST_PARTY: any same-signer Omni application.
 *  - TRUSTED_PARTNER: explicitly allowlisted signing certificate and capabilities.
 *  - UNTRUSTED: everything else.
 */
class DefaultTrustResolver(
    private val corePackages: Set<String> = emptySet(),
    partnerRules: List<TrustedPartnerRule> = emptyList()
) : TrustResolver {
    private val normalizedPartnerRules = partnerRules.map { rule ->
        rule.copy(signerSha256 = rule.signerSha256.map(::normalizeCertificateHash).toSet())
    }

    override fun resolve(context: Context, caller: CallerContext): TrustPrincipal {
        val packages = caller.callingPackages.ifEmpty { listOf(caller.callingPackage) }
        val sameSigner = caller.callingUid == Process.myUid() ||
            caller.sameSignerAsHost ||
            context.packageManager.checkSignatures(caller.callingUid, Process.myUid()) ==
            PackageManager.SIGNATURE_MATCH

        if (sameSigner) {
            val isCore = packages.any(corePackages::contains)
            return TrustPrincipal(
                tier = if (isCore) TrustTier.CORE else TrustTier.FIRST_PARTY,
                packageNames = packages,
                signerSha256 = caller.signingCertificateSha256,
                reason = if (isCore) "same_signer_core_package" else "same_signer"
            )
        }

        val signerSet = caller.signingCertificateSha256.map(::normalizeCertificateHash).toSet()
        val partner = normalizedPartnerRules.firstOrNull { rule ->
            rule.signerSha256.any(signerSet::contains)
        }
        if (partner != null) {
            return TrustPrincipal(
                tier = TrustTier.TRUSTED_PARTNER,
                packageNames = packages,
                signerSha256 = signerSet.toList(),
                allowedCapabilities = partner.allowedCapabilities,
                reason = "trusted_partner_certificate"
            )
        }

        return TrustPrincipal(
            tier = TrustTier.UNTRUSTED,
            packageNames = packages,
            signerSha256 = signerSet.toList(),
            reason = "untrusted_signer"
        )
    }
}

object CapabilityTrustPolicy {
    fun isAllowed(
        principal: TrustPrincipal,
        capability: CapabilityDescriptor,
        direction: InvocationDirection
    ): Boolean {
        if (!capability.communicationDirection.allows(direction)) return false
        if (principal.tier.rank < capability.requiredTrustTier.rank) return false
        if (principal.tier == TrustTier.TRUSTED_PARTNER &&
            capability.name !in principal.allowedCapabilities
        ) return false
        return principal.tier != TrustTier.UNTRUSTED ||
            capability.requiredTrustTier == TrustTier.UNTRUSTED
    }
}

internal fun normalizeCertificateHash(value: String): String =
    value.replace(":", "").replace(" ", "").lowercase()
