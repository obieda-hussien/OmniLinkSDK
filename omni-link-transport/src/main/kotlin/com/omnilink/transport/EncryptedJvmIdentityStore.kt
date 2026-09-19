package com.omnilink.transport

import kotlinx.serialization.Serializable
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted persistence for desktop/JVM transport identities.
 *
 * Android should use AndroidKeystoreSigningIdentity instead. This format is deliberately simple and
 * self-contained so a desktop companion can persist the same long-lived identity without storing the
 * private key as plaintext JSON.
 */
class EncryptedJvmIdentityStore(
    private val file: File,
    private val iterations: Int = 210_000
) {
    @Serializable
    private data class Envelope(
        val version: Int = 1,
        val iterations: Int,
        val saltHex: String,
        val nonceHex: String,
        val ciphertextHex: String
    )

    private val random = SecureRandom()

    fun save(identity: JvmEcSigningIdentity, password: CharArray) {
        require(password.isNotEmpty()) { "Identity password must not be empty" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(password, salt, iterations)

        val plaintext = TransportJson.instance
            .encodeToString(ExportedJvmIdentity.serializer(), identity.export())
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(OmniTransportConstants.CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("OMNILINK-JVM-IDENTITY-v1".toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)

        key.fill(0)
        plaintext.fill(0)

        val envelope = Envelope(
            iterations = iterations,
            saltHex = salt.toHex(),
            nonceHex = nonce.toHex(),
            ciphertextHex = ciphertext.toHex()
        )

        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: File("."), file.name + ".tmp")
        temp.writeText(TransportJson.instance.encodeToString(Envelope.serializer(), envelope))
        restrictPermissions(temp)
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            restrictPermissions(file)
            temp.delete()
        } else {
            restrictPermissions(file)
        }
    }

    fun load(password: CharArray): JvmEcSigningIdentity {
        require(password.isNotEmpty()) { "Identity password must not be empty" }
        val envelope = TransportJson.instance.decodeFromString(
            Envelope.serializer(),
            file.readText()
        )
        require(envelope.version == 1) { "Unsupported identity store version ${envelope.version}" }

        val salt = envelope.saltHex.hexToBytes()
        val nonce = envelope.nonceHex.hexToBytes()
        val ciphertext = envelope.ciphertextHex.hexToBytes()
        val key = deriveKey(password, salt, envelope.iterations)

        return try {
            val cipher = Cipher.getInstance(OmniTransportConstants.CIPHER_ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce)
            )
            cipher.updateAAD("OMNILINK-JVM-IDENTITY-v1".toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                val exported = TransportJson.instance.decodeFromString(
                    ExportedJvmIdentity.serializer(),
                    plaintext.toString(Charsets.UTF_8)
                )
                JvmEcSigningIdentity.import(exported)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun restrictPermissions(target: File) {
        // Best effort across operating systems. POSIX-specific ACLs can be layered by the desktop app.
        target.setReadable(false, false)
        target.setWritable(false, false)
        target.setExecutable(false, false)
        target.setReadable(true, true)
        target.setWritable(true, true)
    }
}
