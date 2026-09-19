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

    val isOpen: Boolean
        get() = !socket.isClosed

    fun send(message: TransportMessage) {
        if (!peerTrust.permitsOutbound(message.capability)) {
            throw TransportAuthorizationException(
                "Peer '$remotePeerId' is not authorized to receive capability '${message.capability}'"
            )
        }

        val plaintext = BinaryMessageCodec.encode(message)
        val sequence = txSequence.incrementAndGet()
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

        synchronized(sendLock) {
            output.writeInt(frameLength)
            output.writeLong(sequence)
            output.write(ciphertext)
            output.flush()
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

        if (!peerTrust.permitsInbound(message.capability)) {
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
        try {
            socket.close()
        } catch (_: Exception) {
        }
        txKey.fill(0)
        rxKey.fill(0)
    }

    private fun aad(sessionId: String, direction: String, sequence: Long): ByteArray =
        "OMNILINK|$sessionId|$direction|$sequence".toByteArray(Charsets.UTF_8)
}
