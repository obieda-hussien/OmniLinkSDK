package com.omnilink.transport

import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class EphemeralKeyPair(val pair: KeyPair)

internal data class DerivedSessionKeys(
    val clientToServerKey: ByteArray,
    val serverToClientKey: ByteArray,
    val clientNoncePrefix: ByteArray,
    val serverNoncePrefix: ByteArray,
    val sessionId: String
)

internal object TransportCrypto {
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun generateEphemeral(): EphemeralKeyPair {
        val generator = KeyPairGenerator.getInstance(OmniTransportConstants.KEY_ALGORITHM)
        generator.initialize(ECGenParameterSpec(OmniTransportConstants.KEY_CURVE))
        return EphemeralKeyPair(generator.generateKeyPair())
    }

    fun transcriptBytes(
        clientHelloBytes: ByteArray,
        serverHelloBytes: ByteArray,
        serverSignature: ByteArray? = null
    ): ByteArray {
        val domain = "OMNILINK-TRANSPORT-HANDSHAKE-v1".toByteArray(Charsets.UTF_8)
        val signature = serverSignature ?: byteArrayOf()
        return concat(
            intBytes(domain.size), domain,
            intBytes(clientHelloBytes.size), clientHelloBytes,
            intBytes(serverHelloBytes.size), serverHelloBytes,
            intBytes(signature.size), signature
        )
    }

    fun pairingCode(clientFingerprint: String, serverFingerprint: String): String {
        val combined = listOf(
            normalizeFingerprint(clientFingerprint),
            normalizeFingerprint(serverFingerprint)
        ).sorted().joinToString("|")
        val digest = sha256(combined.toByteArray(Charsets.UTF_8))
        val value = ByteBuffer.wrap(digest.copyOfRange(0, 4)).int.toLong() and 0xffffffffL
        return "%06d".format(value % 1_000_000L)
    }

    fun deriveSessionKeys(
        localEphemeral: EphemeralKeyPair,
        remoteEphemeralPublic: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        transcript: ByteArray
    ): DerivedSessionKeys {
        val agreement = KeyAgreement.getInstance(OmniTransportConstants.KEY_AGREEMENT_ALGORITHM)
        agreement.init(localEphemeral.pair.private)
        agreement.doPhase(decodeEcPublicKey(remoteEphemeralPublic), true)
        val sharedSecret = agreement.generateSecret()

        val salt = sha256(concat(clientNonce, serverNonce, sha256(transcript)))
        val prk = hmacSha256(salt, sharedSecret)
        val okm = hkdfExpand(prk, "omnilink-session-v1".toByteArray(Charsets.UTF_8), 72)

        return DerivedSessionKeys(
            clientToServerKey = okm.copyOfRange(0, 32),
            serverToClientKey = okm.copyOfRange(32, 64),
            clientNoncePrefix = okm.copyOfRange(64, 68),
            serverNoncePrefix = okm.copyOfRange(68, 72),
            sessionId = sha256(transcript).copyOfRange(0, 16).toHex()
        )
    }

    fun encrypt(
        key: ByteArray,
        noncePrefix: ByteArray,
        sequence: Long,
        aad: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(OmniTransportConstants.CIPHER_ALGORITHM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce(noncePrefix, sequence))
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun decrypt(
        key: ByteArray,
        noncePrefix: ByteArray,
        sequence: Long,
        aad: ByteArray,
        ciphertext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(OmniTransportConstants.CIPHER_ALGORITHM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce(noncePrefix, sequence))
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun nonce(prefix: ByteArray, sequence: Long): ByteArray {
        require(prefix.size == 4) { "GCM nonce prefix must be four bytes" }
        return ByteBuffer.allocate(12).put(prefix).putLong(sequence).array()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        var previous = byteArrayOf()
        var written = 0
        var counter = 1

        while (written < length) {
            previous = hmacSha256(prk, concat(previous, info, byteArrayOf(counter.toByte())))
            val copy = minOf(previous.size, length - written)
            previous.copyInto(output, written, 0, copy)
            written += copy
            counter++
        }
        return output
    }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArray(arrays.sumOf { it.size })
        var offset = 0
        arrays.forEach {
            it.copyInto(out, offset)
            offset += it.size
        }
        return out
    }
}
