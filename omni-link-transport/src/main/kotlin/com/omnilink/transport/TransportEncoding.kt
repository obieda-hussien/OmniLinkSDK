package com.omnilink.transport

import java.security.MessageDigest

internal fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun String.hexToBytes(): ByteArray {
    val normalized = lowercase().replace(":", "").replace(" ", "")
    require(normalized.length % 2 == 0) { "Invalid hex length" }
    return ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

internal fun normalizeFingerprint(value: String): String =
    value.lowercase().replace(":", "").replace(" ", "")

internal fun constantTimeEqualsHex(a: String, b: String): Boolean {
    val left = try { normalizeFingerprint(a).hexToBytes() } catch (_: Exception) { return false }
    val right = try { normalizeFingerprint(b).hexToBytes() } catch (_: Exception) { return false }
    return MessageDigest.isEqual(left, right)
}
