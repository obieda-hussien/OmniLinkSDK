package com.omnilink.sdk.trusted

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

enum class ProviderIdentityMode {
    SAME_SIGNER,
    PINNED_SIGNER,
    SAME_OR_PINNED
}

data class TrustedServicePolicy(
    val action: String,
    val requiredPermission: String,
    val identityMode: ProviderIdentityMode = ProviderIdentityMode.SAME_SIGNER,
    val allowedSignerSha256: Set<String> = emptySet(),
    val allowedPackages: Set<String> = emptySet()
)

data class VerifiedService(
    val packageName: String,
    val serviceClassName: String,
    val signerSha256: Set<String>,
    val sameSignerAsHost: Boolean,
    val requiredPermission: String
) {
    val component: ComponentName
        get() = ComponentName(packageName, serviceClassName)

    fun explicitIntent(action: String): Intent =
        Intent(action).setComponent(component).setPackage(packageName)
}

data class RejectedService(
    val packageName: String,
    val serviceClassName: String,
    val reason: String
)

data class TrustedServiceQuery(
    val verified: List<VerifiedService>,
    val rejected: List<RejectedService>
)

/**
 * Host-side provider authentication.
 *
 * Discovery is allowed to be implicit, but binding must happen only after this resolver validates
 * the real package/service/signing identity and returns an explicit component.
 */
class TrustedServiceResolver(context: Context) {
    private val appContext = context.applicationContext

    fun query(policy: TrustedServicePolicy): TrustedServiceQuery {
        val pm = appContext.packageManager
        val intent = Intent(policy.action)
        val resolves = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        }.getOrDefault(emptyList())

        val verified = mutableListOf<VerifiedService>()
        val rejected = mutableListOf<RejectedService>()

        resolves.forEach { resolve ->
            val info = resolve.serviceInfo ?: return@forEach
            val pkg = info.packageName ?: return@forEach
            val cls = info.name ?: return@forEach
            val rejection = verifyService(
                packageName = pkg,
                serviceClassName = cls,
                exported = info.exported,
                declaredPermission = info.permission,
                policy = policy
            )
            if (rejection == null) {
                val signerSet = signerSha256(pkg)
                verified += VerifiedService(
                    packageName = pkg,
                    serviceClassName = cls,
                    signerSha256 = signerSet,
                    sameSignerAsHost = pm.checkSignatures(appContext.packageName, pkg) ==
                        PackageManager.SIGNATURE_MATCH,
                    requiredPermission = policy.requiredPermission
                )
            } else {
                rejected += RejectedService(pkg, cls, rejection)
            }
        }

        return TrustedServiceQuery(
            verified = verified.sortedWith(compareBy({ it.packageName }, { it.serviceClassName })),
            rejected = rejected.sortedWith(compareBy({ it.packageName }, { it.serviceClassName }))
        )
    }

    private fun verifyService(
        packageName: String,
        serviceClassName: String,
        exported: Boolean,
        declaredPermission: String?,
        policy: TrustedServicePolicy
    ): String? {
        if (!exported) return "service_not_exported"
        if (declaredPermission != policy.requiredPermission) return "wrong_service_permission"
        if (policy.allowedPackages.isNotEmpty() && packageName !in policy.allowedPackages) {
            return "package_not_allowlisted"
        }

        val sameSigner = appContext.packageManager.checkSignatures(
            appContext.packageName,
            packageName
        ) == PackageManager.SIGNATURE_MATCH

        val expected = policy.allowedSignerSha256.map(::normalizeHash).toSet()
        val actual = signerSha256(packageName).map(::normalizeHash).toSet()
        val pinned = expected.isNotEmpty() && actual.any(expected::contains)

        val identityAccepted = when (policy.identityMode) {
            ProviderIdentityMode.SAME_SIGNER -> sameSigner
            ProviderIdentityMode.PINNED_SIGNER -> pinned
            ProviderIdentityMode.SAME_OR_PINNED -> sameSigner || pinned
        }
        if (!identityAccepted) return "untrusted_signer"

        if (serviceClassName.isBlank()) return "missing_service_class"
        return null
    }

    private fun signerSha256(packageName: String): Set<String> = runCatching {
        val pm = appContext.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return@runCatching emptySet()
            // A certificate in signing history is not the signer of the installed APK. A pinned
            // provider must be approved again after rotation before privileged Binder binding.
            signing.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }.getOrDefault(emptySet())

    private fun normalizeHash(value: String): String =
        value.replace(":", "").replace(" ", "").lowercase()
}
