package com.omnilink.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Transport-independent primitives for OmniLink's next-generation multiplexed session layer.
 *
 * Binder remains the Android transport today. These frames deliberately contain no Android types,
 * allowing the same session contract to be carried over Binder, local sockets, LAN, USB/ADB or a
 * future desktop transport without redesigning agent semantics.
 */
@Serializable
data class OmniSessionHello(
    val sessionProtocolVersion: Int = OmniLinkConstants.SESSION_PROTOCOL_VERSION,
    val supportedCodecs: Set<WireCodec> = setOf(WireCodec.JSON),
    val supportedPayloadTransports: Set<PayloadTransport> = setOf(PayloadTransport.INLINE),
    val maxInlineBytes: Int = OmniLinkConstants.DEFAULT_MAX_INLINE_JSON_BYTES,
    val initialEventCredits: Int = OmniLinkConstants.DEFAULT_EVENT_WINDOW,
    val supportsResume: Boolean = true,
    val supportsMultiplexing: Boolean = true,
    val supportsControlLane: Boolean = true
)

@Serializable
enum class WireCodec {
    JSON,
    CBOR,
    PROTOBUF,
    FLATBUFFERS
}

@Serializable
enum class PayloadTransport {
    INLINE,
    FILE_DESCRIPTOR,
    PIPE,
    SHARED_MEMORY,
    CONTENT_URI
}

@Serializable
data class PayloadDescriptor(
    val payloadId: String,
    val transport: PayloadTransport,
    val lengthBytes: Long,
    val mimeType: String? = null,
    val sha256: String? = null,
    val uri: String? = null
)

@Serializable
enum class FrameLane {
    CONTROL,
    DATA,
    EVENT
}

@Serializable
sealed interface OmniFrame {
    val sessionId: String
    val streamId: String
    val sequence: Long
    val lane: FrameLane

    @Serializable
    @SerialName("request")
    data class Request(
        override val sessionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val lane: FrameLane = FrameLane.DATA,
        val request: ActionRequest,
        val payload: PayloadDescriptor? = null
    ) : OmniFrame

    @Serializable
    @SerialName("result")
    data class Result(
        override val sessionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val lane: FrameLane = FrameLane.DATA,
        val outcome: ActionOutcome,
        val payload: PayloadDescriptor? = null
    ) : OmniFrame

    @Serializable
    @SerialName("event")
    data class Event(
        override val sessionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val lane: FrameLane = FrameLane.EVENT,
        val event: OmniEvent
    ) : OmniFrame

    @Serializable
    @SerialName("ack")
    data class Ack(
        override val sessionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val lane: FrameLane = FrameLane.CONTROL,
        val creditsGranted: Int = 0,
        val acknowledgedThrough: Long = sequence
    ) : OmniFrame

    @Serializable
    @SerialName("cancel")
    data class Cancel(
        override val sessionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val lane: FrameLane = FrameLane.CONTROL,
        val reason: String? = null
    ) : OmniFrame

    @Serializable
    @SerialName("ping")
    data class Ping(
        override val sessionId: String,
        override val streamId: String = "control",
        override val sequence: Long,
        override val lane: FrameLane = FrameLane.CONTROL,
        val timestampEpochMs: Long
    ) : OmniFrame

    @Serializable
    @SerialName("error")
    data class Error(
        override val sessionId: String,
        override val streamId: String,
        override val sequence: Long,
        override val lane: FrameLane = FrameLane.CONTROL,
        val code: String,
        val message: String,
        val details: JsonElement = JsonNull
    ) : OmniFrame
}
