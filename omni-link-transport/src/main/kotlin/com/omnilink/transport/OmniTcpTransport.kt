package com.omnilink.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.math.abs

object OmniTcpClient {
    fun connect(
        host: String,
        port: Int = OmniTransportConstants.DEFAULT_PORT,
        identity: SigningIdentity,
        trustStore: PeerTrustStore,
        admissionHandler: PeerAdmissionHandler = StrictPeerAdmission,
        config: TransportConfig = TransportConfig()
    ): SecureTransportSession {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), config.connectTimeoutMillis)
            configureSocket(socket, config)
            socket.soTimeout = config.handshakeTimeoutMillis
            return performClientHandshake(
                socket,
                identity,
                trustStore,
                admissionHandler,
                config
            ).also {
                socket.soTimeout = 0
            }
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            if (e is OmniTransportException) throw e
            throw OmniTransportException("Failed to connect to $host:$port", e)
        }
    }
}

class OmniTcpServer(
    private val identity: SigningIdentity,
    private val trustStore: PeerTrustStore,
    private val admissionHandler: PeerAdmissionHandler = StrictPeerAdmission,
    private val config: TransportConfig = TransportConfig(),
    private val bindAddress: InetAddress? = null,
    private val port: Int = OmniTransportConstants.DEFAULT_PORT
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serverSocket: ServerSocket? = null

    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(
        onSession: suspend (SecureTransportSession) -> Unit,
        onError: (Throwable) -> Unit = {}
    ): Job {
        check(serverSocket == null) { "Server already started" }

        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindAddress, port))
        serverSocket = server

        return scope.launch {
            while (isActive && !server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    if (server.isClosed) break
                    onError(e)
                    continue
                } catch (e: Exception) {
                    onError(e)
                    continue
                }

                launch {
                    try {
                        configureSocket(socket, config)
                        socket.soTimeout = config.handshakeTimeoutMillis
                        val session = performServerHandshake(
                            socket,
                            identity,
                            trustStore,
                            admissionHandler,
                            config
                        )
                        socket.soTimeout = 0
                        onSession(session)
                    } catch (e: Exception) {
                        try { socket.close() } catch (_: Exception) {}
                        onError(e)
                    }
                }
            }
        }
    }

    override fun close() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        scope.cancel()
    }
}

private fun configureSocket(socket: Socket, config: TransportConfig) {
    socket.tcpNoDelay = config.tcpNoDelay
    socket.keepAlive = config.keepAlive
}

private fun performClientHandshake(
    socket: Socket,
    identity: SigningIdentity,
    trustStore: PeerTrustStore,
    admissionHandler: PeerAdmissionHandler,
    config: TransportConfig
): SecureTransportSession {
    val input = DataInputStream(socket.getInputStream().buffered())
    val output = DataOutputStream(socket.getOutputStream().buffered())
    val ephemeral = TransportCrypto.generateEphemeral()
    val clientNonce = TransportCrypto.randomBytes(32)

    val clientHello = ClientHello(
        peerId = identity.peerId,
        role = identity.role,
        identityPublicKeyHex = identity.publicKeyEncoded.toHex(),
        ephemeralPublicKeyHex = ephemeral.pair.public.encoded.toHex(),
        nonceHex = clientNonce.toHex(),
        timestampEpochMs = System.currentTimeMillis(),
        platformSignerSha256 = identity.platformSignerSha256.map(::normalizeFingerprint).toSet()
    )
    val clientHelloBytes = encode(clientHello)
    writeHandshakeFrame(output, clientHelloBytes)

    val response = decode<ServerHandshakeResponse>(readHandshakeFrame(input))
    if (!response.accepted || response.hello == null || response.signatureHex == null) {
        throw HandshakeRejectedException(
            response.rejectionCode ?: "server_rejected",
            response.rejectionMessage ?: "Server rejected OmniLink handshake"
        )
    }

    val serverHello = response.hello
    validateHello(
        protocolVersion = serverHello.protocolVersion,
        timestampEpochMs = serverHello.timestampEpochMs,
        maxClockSkewMillis = config.maxClockSkewMillis
    )
    val serverHelloBytes = encode(serverHello)
    val serverIdentityKey = serverHello.identityPublicKeyHex.hexToBytes()
    val serverFingerprint = sha256Hex(serverIdentityKey)
    val serverCandidate = PeerCandidate(
        peerId = serverHello.peerId,
        role = serverHello.role,
        publicKeySha256 = serverFingerprint,
        platformSignerSha256 = serverHello.platformSignerSha256,
        pairingCode = TransportCrypto.pairingCode(
            identity.publicKeySha256(),
            serverFingerprint
        )
    )

    val serverSignature = response.signatureHex.hexToBytes()
    val serverTranscript = TransportCrypto.transcriptBytes(
        clientHelloBytes,
        serverHelloBytes
    )
    if (!verifySignature(serverIdentityKey, serverTranscript, serverSignature)) {
        throw PeerAuthenticationException("Server handshake signature is invalid")
    }

    val serverTrustResolution = resolveTrustProvisional(
        trustStore,
        admissionHandler,
        serverCandidate
    )
    if (serverTrustResolution.shouldPersist) {
        trustStore.put(serverTrustResolution.record)
    }
    val serverTrust = serverTrustResolution.record

    val proofTranscript = TransportCrypto.transcriptBytes(
        clientHelloBytes,
        serverHelloBytes,
        serverSignature
    )
    val clientProof = ClientProof(identity.sign(proofTranscript).toHex())
    writeHandshakeFrame(output, encode(clientProof))

    val finished = decode<HandshakeFinished>(readHandshakeFrame(input))
    if (!finished.accepted) {
        throw HandshakeRejectedException(
            finished.rejectionCode ?: "server_rejected",
            finished.rejectionMessage ?: "Server rejected client proof"
        )
    }
    val confirmation = finished.confirmationSignatureHex
        ?: throw PeerAuthenticationException("Server omitted key confirmation")
    val confirmationBytes = TransportCrypto.serverConfirmationBytes(
        proofTranscript,
        clientProof.signatureHex.hexToBytes()
    )
    if (!verifySignature(
            serverIdentityKey,
            confirmationBytes,
            confirmation.hexToBytes()
        )
    ) {
        throw PeerAuthenticationException("Server key confirmation signature is invalid")
    }

    val keys = TransportCrypto.deriveSessionKeys(
        localEphemeral = ephemeral,
        remoteEphemeralPublic = serverHello.ephemeralPublicKeyHex.hexToBytes(),
        clientNonce = clientNonce,
        serverNonce = serverHello.nonceHex.hexToBytes(),
        transcript = proofTranscript
    )

    return SecureTransportSession(
        socket = socket,
        input = input,
        output = output,
        sessionId = keys.sessionId,
        remotePeerId = serverHello.peerId,
        remoteRole = serverHello.role,
        peerTrust = trustStore.get(serverHello.peerId) ?: serverTrust,
        trustStore = trustStore,
        txKey = keys.clientToServerKey,
        rxKey = keys.serverToClientKey,
        txNoncePrefix = keys.clientNoncePrefix,
        rxNoncePrefix = keys.serverNoncePrefix,
        txDirectionLabel = "client-to-server",
        rxDirectionLabel = "server-to-client",
        maxEncryptedFrameBytes = config.maxEncryptedFrameBytes
    )
}

private fun performServerHandshake(
    socket: Socket,
    identity: SigningIdentity,
    trustStore: PeerTrustStore,
    admissionHandler: PeerAdmissionHandler,
    config: TransportConfig
): SecureTransportSession {
    val input = DataInputStream(socket.getInputStream().buffered())
    val output = DataOutputStream(socket.getOutputStream().buffered())
    val clientHelloBytes = readHandshakeFrame(input)
    val clientHello = decode<ClientHello>(clientHelloBytes)

    try {
        validateHello(
            protocolVersion = clientHello.protocolVersion,
            timestampEpochMs = clientHello.timestampEpochMs,
            maxClockSkewMillis = config.maxClockSkewMillis
        )
    } catch (e: Exception) {
        writeHandshakeFrame(
            output,
            encode(
                ServerHandshakeResponse(
                    accepted = false,
                    rejectionCode = "invalid_hello",
                    rejectionMessage = e.message ?: "Invalid client hello"
                )
            )
        )
        throw e
    }

    val ephemeral = TransportCrypto.generateEphemeral()
    val serverNonce = TransportCrypto.randomBytes(32)
    val serverHello = ServerHelloUnsigned(
        peerId = identity.peerId,
        role = identity.role,
        identityPublicKeyHex = identity.publicKeyEncoded.toHex(),
        ephemeralPublicKeyHex = ephemeral.pair.public.encoded.toHex(),
        nonceHex = serverNonce.toHex(),
        timestampEpochMs = System.currentTimeMillis(),
        platformSignerSha256 = identity.platformSignerSha256.map(::normalizeFingerprint).toSet()
    )
    val serverHelloBytes = encode(serverHello)
    val serverTranscript = TransportCrypto.transcriptBytes(
        clientHelloBytes,
        serverHelloBytes
    )
    val serverSignature = identity.sign(serverTranscript)

    writeHandshakeFrame(
        output,
        encode(
            ServerHandshakeResponse(
                accepted = true,
                hello = serverHello,
                signatureHex = serverSignature.toHex()
            )
        )
    )

    val clientIdentityKey = clientHello.identityPublicKeyHex.hexToBytes()
    val clientFingerprint = sha256Hex(clientIdentityKey)
    val clientCandidate = PeerCandidate(
        peerId = clientHello.peerId,
        role = clientHello.role,
        publicKeySha256 = clientFingerprint,
        platformSignerSha256 = clientHello.platformSignerSha256,
        pairingCode = TransportCrypto.pairingCode(
            clientFingerprint,
            identity.publicKeySha256()
        )
    )

    val clientTrustResolution = try {
        resolveTrustProvisional(trustStore, admissionHandler, clientCandidate)
    } catch (e: Exception) {
        writeHandshakeFrame(
            output,
            encode(
                HandshakeFinished(
                    accepted = false,
                    rejectionCode = "untrusted_peer",
                    rejectionMessage = e.message ?: "Client is not trusted"
                )
            )
        )
        throw e
    }

    val proof = decode<ClientProof>(readHandshakeFrame(input))
    val proofTranscript = TransportCrypto.transcriptBytes(
        clientHelloBytes,
        serverHelloBytes,
        serverSignature
    )
    if (!verifySignature(clientIdentityKey, proofTranscript, proof.signatureHex.hexToBytes())) {
        writeHandshakeFrame(
            output,
            encode(
                HandshakeFinished(
                    accepted = false,
                    rejectionCode = "bad_signature",
                    rejectionMessage = "Client handshake signature is invalid"
                )
            )
        )
        throw PeerAuthenticationException("Client handshake signature is invalid")
    }

    if (clientTrustResolution.shouldPersist) {
        trustStore.put(clientTrustResolution.record)
    }
    val clientTrust = clientTrustResolution.record

    val confirmationBytes = TransportCrypto.serverConfirmationBytes(
        proofTranscript,
        proof.signatureHex.hexToBytes()
    )
    writeHandshakeFrame(
        output,
        encode(
            HandshakeFinished(
                accepted = true,
                confirmationSignatureHex = identity.sign(confirmationBytes).toHex()
            )
        )
    )

    val keys = TransportCrypto.deriveSessionKeys(
        localEphemeral = ephemeral,
        remoteEphemeralPublic = clientHello.ephemeralPublicKeyHex.hexToBytes(),
        clientNonce = clientHello.nonceHex.hexToBytes(),
        serverNonce = serverNonce,
        transcript = proofTranscript
    )

    return SecureTransportSession(
        socket = socket,
        input = input,
        output = output,
        sessionId = keys.sessionId,
        remotePeerId = clientHello.peerId,
        remoteRole = clientHello.role,
        peerTrust = trustStore.get(clientHello.peerId) ?: clientTrust,
        trustStore = trustStore,
        txKey = keys.serverToClientKey,
        rxKey = keys.clientToServerKey,
        txNoncePrefix = keys.serverNoncePrefix,
        rxNoncePrefix = keys.clientNoncePrefix,
        txDirectionLabel = "server-to-client",
        rxDirectionLabel = "client-to-server",
        maxEncryptedFrameBytes = config.maxEncryptedFrameBytes
    )
}

private data class TrustResolution(
    val record: PeerTrustRecord,
    val shouldPersist: Boolean
)

private fun resolveTrustProvisional(
    trustStore: PeerTrustStore,
    admissionHandler: PeerAdmissionHandler,
    candidate: PeerCandidate
): TrustResolution {
    val existing = trustStore.get(candidate.peerId)
    if (existing != null) {
        if (!existing.verifyCandidate(candidate)) {
            throw PeerAuthenticationException(
                "Pinned identity/platform signer mismatch for peer '${candidate.peerId}'"
            )
        }
        return TrustResolution(existing, shouldPersist = false)
    }

    val admitted = admissionHandler.admit(candidate)
        ?: throw PeerAuthenticationException(
            "Unknown peer '${candidate.peerId}' rejected. Pairing code: ${candidate.pairingCode}"
        )

    if (!admitted.verifyCandidate(candidate)) {
        throw PeerAuthenticationException(
            "Admission record does not match peer '${candidate.peerId}'"
        )
    }

    return TrustResolution(admitted, shouldPersist = true)
}

private fun validateHello(
    protocolVersion: Int,
    timestampEpochMs: Long,
    maxClockSkewMillis: Long
) {
    require(protocolVersion == OmniTransportConstants.PROTOCOL_VERSION) {
        "Unsupported transport protocol $protocolVersion"
    }
    val skew = abs(System.currentTimeMillis() - timestampEpochMs)
    require(skew <= maxClockSkewMillis) {
        "Handshake clock skew $skew ms exceeds $maxClockSkewMillis ms"
    }
}

private inline fun <reified T> encode(value: T): ByteArray =
    TransportJson.instance.encodeToString(value).toByteArray(Charsets.UTF_8)

private inline fun <reified T> decode(bytes: ByteArray): T =
    TransportJson.instance.decodeFromString(bytes.toString(Charsets.UTF_8))

private fun writeHandshakeFrame(output: DataOutputStream, bytes: ByteArray) {
    if (bytes.size > OmniTransportConstants.MAX_HANDSHAKE_FRAME_BYTES) {
        throw TransportFrameTooLargeException("Handshake frame too large: ${bytes.size}")
    }
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
}

private fun readHandshakeFrame(input: DataInputStream): ByteArray {
    val length = input.readInt()
    if (length !in 1..OmniTransportConstants.MAX_HANDSHAKE_FRAME_BYTES) {
        throw TransportFrameTooLargeException("Invalid handshake frame length $length")
    }
    return ByteArray(length).also { input.readFully(it) }
}
