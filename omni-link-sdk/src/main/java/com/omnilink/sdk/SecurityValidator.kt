package com.omnilink.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

interface SecurityValidator {
    /**
     * Validates if the caller is authorized to bind and call this extension.
     * Return true if valid, false to immediately reject with an access_denied error.
     */
    fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean
}

/**
 * A basic validator that only checks package names, suitable for testing or development.
 * Note: Not safe against package spoofing on rooted devices.
 */
class BasicPackageValidator(private val allowedPackages: Set<String>) : SecurityValidator {
    override fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean {
        return allowedPackages.contains(caller.callingPackage)
    }
}

/**
 * A robust validator that computes the SHA-256 hash of the caller's APK signing certificate
 * and verifies it against an authorized set of hashes.
 */
class SignatureSecurityValidator(
    private val allowedSignatureHashes: Set<String>
) : SecurityValidator {

    override fun isCallerAuthorized(context: Context, caller: CallerContext): Boolean {
        try {
            val pm = context.packageManager

            // Get signatures based on Android version
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(caller.callingPackage, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(caller.callingPackage, PackageManager.GET_SIGNATURES)
                info.signatures
            }

            if (signatures == null || signatures.isEmpty()) {
                return false
            }

            val md = MessageDigest.getInstance("SHA-256")

            for (signature in signatures) {
                val digest = md.digest(signature.toByteArray())
                val hashHex = digest.joinToString("") { "%02x".format(it) }
                if (allowedSignatureHashes.contains(hashHex)) {
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }
}
