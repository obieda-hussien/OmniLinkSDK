package com.omnilink.transport

import kotlinx.serialization.Serializable

object OmniTransportConstants {
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_PORT = 49371
    const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000
    const val DEFAULT_MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
    const val DEFAULT_MAX_ENCRYPTED_FRAME_BYTES = 4 * 1024 * 1024
    const val MAX_HANDSHAKE_FRAME_BYTES = 64 * 1024
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    const val KEY_ALGORITHM = "EC"
    const val KEY_CURVE = "secp256r1"
    const val KEY_AGREEMENT_ALGORITHM = "ECDH"
    const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
}

@Serializable
enum class PeerRole {
    ANDROID,
    DESKTOP,
    SERVICE,
    TEST
}

@Serializable
enum class TransportTrustLevel(val rank: Int) {
    UNTRUSTED(0),
    PAIRED(1),
    TRUSTED_PARTNER(2),
    FIRST_PARTY(3),
    CORE(4)
}

@Serializable
data class ClientHello(
    val protocolVersion: Int = OmniTransportConstants.PROTOCOL_VERSION,
    val peerId: String,
    val role: PeerRole,
    val identityPublicKeyHex: String,
    val ephemeralPublicKeyHex: String,
    val nonceHex: String,
    val timestampEpochMs: Long,
    val platformSignerSha256: Set<String> = emptySet(),
    val supportedFeatures: Set<String> = setOf(
        "encrypted_frames",
        "capability_policy",
        "replay_protection"
    )
)

@Serializable
data class ServerHelloUnsigned(
    val protocolVersion: Int = OmniTransportConstants.PROTOCOL_VERSION,
    val peerId: String,
    val role: PeerRole,
    val identityPublicKeyHex: String,
    val ephemeralPublicKeyHex: String,
    val nonceHex: String,
    val timestampEpochMs: Long,
    val platformSignerSha256: Set<String> = emptySet(),
    val supportedFeatures: Set<String> = setOf(
        "encrypted_frames",
        "capability_policy",
        "replay_protection"
    )
)

@Serializable
data class ServerHandshakeResponse(
    val accepted: Boolean,
    val hello: ServerHelloUnsigned? = null,
    val signatureHex: String? = null,
    val rejectionCode: String? = null,
    val rejectionMessage: String? = null
)

@Serializable
data class ClientProof(
    val signatureHex: String
)

@Serializable
data class HandshakeFinished(
    val accepted: Boolean,
    val rejectionCode: String? = null,
    val rejectionMessage: String? = null
)

data class TransportConfig(
    val connectTimeoutMillis: Int = OmniTransportConstants.DEFAULT_CONNECT_TIMEOUT_MS,
    val handshakeTimeoutMillis: Int = OmniTransportConstants.DEFAULT_HANDSHAKE_TIMEOUT_MS,
    val maxClockSkewMillis: Long = OmniTransportConstants.DEFAULT_MAX_CLOCK_SKEW_MS,
    val maxEncryptedFrameBytes: Int = OmniTransportConstants.DEFAULT_MAX_ENCRYPTED_FRAME_BYTES,
    val tcpNoDelay: Boolean = true,
    val keepAlive: Boolean = true
)

enum class TransportMessageType {
    REQUEST,
    RESPONSE,
    EVENT,
    STREAM_CHUNK,
    CONTROL
}

data class TransportMessage(
    val type: TransportMessageType,
    val capability: String,
    val correlationId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val payload: ByteArray = byteArrayOf()
)

data class PeerCandidate(
    val peerId: String,
    val role: PeerRole,
    val publicKeySha256: String,
    val platformSignerSha256: Set<String>,
    val pairingCode: String
)

open class OmniTransportException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

class HandshakeRejectedException(
    val code: String,
    message: String
) : OmniTransportException("$code: $message")

class PeerAuthenticationException(message: String) :
    OmniTransportException(message)

class ReplayDetectedException(message: String) :
    OmniTransportException(message)

class TransportAuthorizationException(message: String) :
    OmniTransportException(message)

class TransportFrameTooLargeException(message: String) :
    OmniTransportException(message)
