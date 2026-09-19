package com.omnilink.sdk

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.omnilink.transport.OmniTransportConstants
import com.omnilink.transport.PeerRole
import com.omnilink.transport.SigningIdentity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Long-lived network signing identity stored in AndroidKeyStore.
 *
 * This is deliberately separate from the APK signing private key: Android devices never receive the
 * developer's APK signing private key. The transport key proves continuity of the paired device/app
 * instance, while [platformSignerSha256] binds the signed handshake to the locally-observed APK
 * signer certificate for policy/audit.
 */
class AndroidKeystoreSigningIdentity private constructor(
    override val peerId: String,
    override val role: PeerRole,
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey,
    override val platformSignerSha256: Set<String>
) : SigningIdentity {

    override val publicKeyEncoded: ByteArray
        get() = publicKey.encoded.clone()

    override fun sign(data: ByteArray): ByteArray {
        val signer = Signature.getInstance(OmniTransportConstants.SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    companion object {
        const val DEFAULT_ALIAS_PREFIX = "omnilink.transport.identity."

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

        fun createOrLoad(
            context: Context,
            peerId: String,
            role: PeerRole = PeerRole.ANDROID,
            alias: String = DEFAULT_ALIAS_PREFIX + peerId
        ): AndroidKeystoreSigningIdentity {
            require(peerId.isNotBlank()) { "peerId must not be blank" }
            check(isSupported()) {
                "AndroidKeyStore EC transport identity requires API 23+. " +
                    "Use a custom SigningIdentity on API 21-22."
            }
            return Api23.createOrLoad(context.applicationContext, peerId, role, alias)
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private object Api23 {
        fun createOrLoad(
            context: Context,
            peerId: String,
            role: PeerRole,
            alias: String
        ): AndroidKeystoreSigningIdentity {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

            if (!keyStore.containsAlias(alias)) {
                val generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    "AndroidKeyStore"
                )
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(
                        ECGenParameterSpec(OmniTransportConstants.KEY_CURVE)
                    )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
                generator.initialize(spec)
                generator.generateKeyPair()
            }

            val privateKey = keyStore.getKey(alias, null) as? PrivateKey
                ?: error("AndroidKeyStore alias '$alias' has no private key")
            val publicKey = keyStore.getCertificate(alias)?.publicKey
                ?: error("AndroidKeyStore alias '$alias' has no public key")

            val signerHashes = SigningCertificateUtils
                .sha256ForPackage(context, context.packageName)
                .map { it.lowercase().replace(":", "").replace(" ", "") }
                .toSet()

            return AndroidKeystoreSigningIdentity(
                peerId = peerId,
                role = role,
                privateKey = privateKey,
                publicKey = publicKey,
                platformSignerSha256 = signerHashes
            )
        }
    }
}
