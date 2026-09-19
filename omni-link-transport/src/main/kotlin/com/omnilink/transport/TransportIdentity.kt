package com.omnilink.transport

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

interface SigningIdentity {
    val peerId: String
    val role: PeerRole
    val publicKeyEncoded: ByteArray
    val platformSignerSha256: Set<String>

    fun sign(data: ByteArray): ByteArray

    fun publicKeySha256(): String = sha256Hex(publicKeyEncoded)
}

class JvmEcSigningIdentity private constructor(
    override val peerId: String,
    override val role: PeerRole,
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey,
    override val platformSignerSha256: Set<String>
) : SigningIdentity {

    override val publicKeyEncoded: ByteArray
        get() = publicKey.encoded.clone()

    override fun sign(data: ByteArray): ByteArray {
        val signature = Signature.getInstance(OmniTransportConstants.SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    fun export(): ExportedJvmIdentity = ExportedJvmIdentity(
        peerId = peerId,
        role = role,
        privateKeyPkcs8Hex = privateKey.encoded.toHex(),
        publicKeyX509Hex = publicKey.encoded.toHex(),
        platformSignerSha256 = platformSignerSha256
    )

    companion object {
        fun generate(
            peerId: String,
            role: PeerRole,
            platformSignerSha256: Set<String> = emptySet()
        ): JvmEcSigningIdentity {
            require(peerId.isNotBlank()) { "peerId must not be blank" }

            val generator = KeyPairGenerator.getInstance(OmniTransportConstants.KEY_ALGORITHM)
            generator.initialize(ECGenParameterSpec(OmniTransportConstants.KEY_CURVE))
            val pair = generator.generateKeyPair()

            return fromKeyPair(peerId, role, pair, platformSignerSha256)
        }

        fun import(exported: ExportedJvmIdentity): JvmEcSigningIdentity {
            val factory = KeyFactory.getInstance(OmniTransportConstants.KEY_ALGORITHM)
            val privateKey = factory.generatePrivate(
                PKCS8EncodedKeySpec(exported.privateKeyPkcs8Hex.hexToBytes())
            )
            val publicKey = factory.generatePublic(
                X509EncodedKeySpec(exported.publicKeyX509Hex.hexToBytes())
            )
            return JvmEcSigningIdentity(
                exported.peerId,
                exported.role,
                privateKey,
                publicKey,
                exported.platformSignerSha256
            )
        }

        private fun fromKeyPair(
            peerId: String,
            role: PeerRole,
            pair: KeyPair,
            platformSignerSha256: Set<String>
        ) = JvmEcSigningIdentity(
            peerId = peerId,
            role = role,
            privateKey = pair.private,
            publicKey = pair.public,
            platformSignerSha256 = platformSignerSha256.map(::normalizeFingerprint).toSet()
        )
    }
}

data class ExportedJvmIdentity(
    val peerId: String,
    val role: PeerRole,
    val privateKeyPkcs8Hex: String,
    val publicKeyX509Hex: String,
    val platformSignerSha256: Set<String> = emptySet()
)

internal fun decodeEcPublicKey(encoded: ByteArray): PublicKey =
    KeyFactory.getInstance(OmniTransportConstants.KEY_ALGORITHM)
        .generatePublic(X509EncodedKeySpec(encoded))

internal fun verifySignature(
    publicKeyEncoded: ByteArray,
    data: ByteArray,
    signatureBytes: ByteArray
): Boolean {
    return try {
        val signature = Signature.getInstance(OmniTransportConstants.SIGNATURE_ALGORITHM)
        signature.initVerify(decodeEcPublicKey(publicKeyEncoded))
        signature.update(data)
        signature.verify(signatureBytes)
    } catch (_: Exception) {
        false
    }
}
