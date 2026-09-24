package com.omnilink.transport

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class SecureTransportSession internal constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    val sessionId: String,
    val remotePeerId: String,
    val remoteRole: PeerRole,
    val peerTrust: PeerTrustRecord,
    private val trustStore: PeerTrustStore,
    private val txKey: ByteArray,
    private val rxKey: ByteArray,
    private val txNoncePrefix: ByteArray,
    private val rxNoncePrefix: ByteArray,
    private val txDirectionLabel: String,
    private val rxDirectionLabel: String,
    private val maxEncryptedFrameBytes: Int
) : Closeable {

    private val txSequence = AtomicLong(0)
    private val expectedRxSequence = AtomicLong(1)
    private val sendLock = Any()
    private val receiveLock = Any()
    private val trustSubscription: Closeable? =
        (trustStore as? SessionTrustStore)?.onPeerChanged(remotePeerId) { close() }

    init {
        // Register first, then check: a removal racing with session construction is observed.
        currentTrust()
    }

    val isOpen: Boolean
        get() = !socket.isClosed

    fun send(message: TransportMessage) {
        if (!currentTrust().permitsOutbound(message.capability)) {
            throw TransportAuthorizationException(
                "Peer '$remotePeerId' is not authorized to receive capability '${message.capability}'"
            )
        }

        val plaintext = BinaryMessageCodec.encode(message)

        // Sequence allocation and socket write must be one ordered critical section. If two
        // concurrent callers obtain sequence 1/2 before the lock and acquire the write lock in the
        // opposite order, the receiver correctly interprets that as a replay/out-of-order attack.
        synchronized(sendLock) {
            if (!currentTrust().permitsOutbound(message.capability)) {
                throw TransportAuthorizationException("Peer authority changed before encrypted frame write")
            }
            val sequence = txSequence.get() + 1
            if (sequence <= 0) {
                close()
                throw OmniTransportException("Encrypted frame sequence exhausted")
            }
            val ciphertext = TransportCrypto.encrypt(
                key = txKey,
                noncePrefix = txNoncePrefix,
                sequence = sequence,
                aad = aad(sessionId, txDirectionLabel, sequence),
                plaintext = plaintext
            )

            val frameLength = Long.SIZE_BYTES + ciphertext.size
            if (frameLength > maxEncryptedFrameBytes) {
                throw TransportFrameTooLargeException(
                    "Encrypted frame $frameLength exceeds $maxEncryptedFrameBytes bytes"
                )
            }

            txSequence.set(sequence)
            try {
                output.writeInt(frameLength)
                output.writeLong(sequence)
                output.write(ciphertext)
                output.flush()
            } catch (error: Exception) {
                close() // The peer may have seen part of the frame; this session cannot retry it.
                throw error
            }
        }
    }

    fun receive(): TransportMessage = synchronized(receiveLock) {
        val frameLength = input.readInt()
        if (frameLength < Long.SIZE_BYTES || frameLength > maxEncryptedFrameBytes) {
            close()
            throw TransportFrameTooLargeException("Invalid encrypted frame length $frameLength")
        }

        val sequence = input.readLong()
        val expected = expectedRxSequence.get()
        if (sequence != expected) {
            close()
            throw ReplayDetectedException(
                "Expected encrypted sequence $expected but received $sequence"
            )
        }

        val ciphertext = ByteArray(frameLength - Long.SIZE_BYTES)
        input.readFully(ciphertext)

        val plaintext = try {
            TransportCrypto.decrypt(
                key = rxKey,
                noncePrefix = rxNoncePrefix,
                sequence = sequence,
                aad = aad(sessionId, rxDirectionLabel, sequence),
                ciphertext = ciphertext
            )
        } catch (e: Exception) {
            close()
            throw PeerAuthenticationException("Encrypted frame authentication failed: ${e.message}")
        }

        expectedRxSequence.incrementAndGet()
        val message = try {
            BinaryMessageCodec.decode(plaintext)
        } catch (e: Exception) {
            close()
            throw OmniTransportException("Malformed decrypted transport message", e)
        }

        if (!currentTrust().permitsInbound(message.capability)) {
            close()
            throw TransportAuthorizationException(
                "Peer '$remotePeerId' is not authorized to send capability '${message.capability}'"
            )
        }

        message
    }

    fun sendUtf8(
        capability: String,
        text: String,
        type: TransportMessageType = TransportMessageType.REQUEST,
        correlationId: String? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        send(
            TransportMessage(
                type = type,
                capability = capability,
                correlationId = correlationId,
                metadata = metadata,
                payload = text.toByteArray(Charsets.UTF_8)
            )
        )
    }

    override fun close() {
        trustSubscription?.close()
        try {
            socket.close()
        } catch (_: Exception) {
        }
        txKey.fill(0)
        rxKey.fill(0)
    }

    private fun currentTrust(): PeerTrustRecord {
        val current = trustStore.get(remotePeerId)
        if (socket.isClosed || current == null || current != peerTrust || current.isExpired()) {
            close()
            throw TransportAuthorizationException("Trust for peer '$remotePeerId' has been revoked or changed")
        }
        return current
    }

    private fun aad(sessionId: String, direction: String, sequence: Long): ByteArray =
        "OMNILINK|$sessionId|$direction|$sequence".toByteArray(Charsets.UTF_8)
}
