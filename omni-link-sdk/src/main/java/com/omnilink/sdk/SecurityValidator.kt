package com.omnilink.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import java.security.MessageDigest

interface SecurityValidator {
    /**
     * Validates whether the Binder caller may enter this privileged OmniLink surface.
     * Capability-level authorization still happens separately.
     */
    fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean
}

/**
 * Development/testing validator. Package names alone are not an authentication mechanism.
 */
class BasicPackageValidator(private val allowedPackages: Set<String>) : SecurityValidator {
    override fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean {
        val packages = caller.callingPackages.ifEmpty { listOf(caller.callingPackage) }
        return packages.any(allowedPackages::contains)
    }
}

/**
 * Secure first-party default: only callers signed by the same certificate as the host app pass.
 *
 * Same UID is accepted because it is already the same Android security principal. Otherwise
 * PackageManager performs the platform signing-certificate comparison.
 */
class SameSignerSecurityValidator : SecurityValidator {
    override fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean {
        if (caller.callingUid == Process.myUid()) return true
        return context.packageManager.checkSignatures(caller.callingUid, Process.myUid()) ==
            PackageManager.SIGNATURE_MATCH
    }
}

/**
 * Explicit certificate allowlist for trusted partners or signing-key migration.
 *
 * SHA-256 values may contain ':' separators and are normalized internally. On Android P+ the full
 * signing certificate history is considered for single-signer packages, so legitimate key rotation
 * can be represented without weakening package identity.
 */
class SignatureSecurityValidator(
    allowedSignatureHashes: Set<String>
) : SecurityValidator {
    private val allowed = allowedSignatureHashes.map(::normalizeCertificateHash).toSet()

    override fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean {
        val known = caller.signingCertificateSha256
            .map(::normalizeCertificateHash)
            .ifEmpty {
                caller.callingPackages
                    .ifEmpty { listOf(caller.callingPackage) }
                    .flatMap { SigningCertificateUtils.sha256ForPackage(context, it) }
            }
        return known.any(allowed::contains)
    }
}

class AnyOfSecurityValidator(
    private vararg val validators: SecurityValidator
) : SecurityValidator {
    override fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean =
        validators.any { it.isCallerAuthorized(context, caller) }
}

class AllOfSecurityValidator(
    private vararg val validators: SecurityValidator
) : SecurityValidator {
    override fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean =
        validators.all { it.isCallerAuthorized(context, caller) }
}

/**
 * Resolves Binder identity from the UID supplied by the kernel. Package names from request payloads
 * are never trusted.
 */
object CallerIdentityResolver {
    fun resolve(context: Context, callingUid: Int = Binder.getCallingUid()): CallerContext {
        val packageManager = context.packageManager
        val packages = packageManager.getPackagesForUid(callingUid)
            ?.toList()
            ?.sorted()
            .orEmpty()

        val certificateHashes = packages
            .flatMap { SigningCertificateUtils.sha256ForPackage(context, it) }
            .map(::normalizeCertificateHash)
            .distinct()
            .sorted()

        val sameSigner = callingUid == Process.myUid() ||
            packageManager.checkSignatures(callingUid, Process.myUid()) ==
            PackageManager.SIGNATURE_MATCH

        return CallerContext(
            callingUid = callingUid,
            callingPackage = packages.firstOrNull() ?: "uid:$callingUid",
            callingPackages = packages,
            signingCertificateSha256 = certificateHashes,
            sameSignerAsHost = sameSigner
        )
    }
}

internal object SigningCertificateUtils {
    fun sha256ForPackage(context: Context, packageName: String): List<String> {
        return try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }

            signatures.orEmpty().map { signature ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
