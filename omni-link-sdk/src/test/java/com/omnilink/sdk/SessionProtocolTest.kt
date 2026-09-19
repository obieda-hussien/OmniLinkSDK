package com.omnilink.sdk

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProtocolTest {

    @Test
    fun `session hello round trips negotiated transport features`() {
        val hello = OmniSessionHello(
            supportedCodecs = setOf(WireCodec.JSON, WireCodec.PROTOBUF),
            supportedPayloadTransports = setOf(
                PayloadTransport.INLINE,
                PayloadTransport.FILE_DESCRIPTOR
            ),
            initialEventCredits = 32
        )

        val encoded = OmniJson.instance.encodeToString(hello)
        val decoded = OmniJson.instance.decodeFromString<OmniSessionHello>(encoded)

        assertEquals(32, decoded.initialEventCredits)
        assertTrue(WireCodec.PROTOBUF in decoded.supportedCodecs)
        assertTrue(PayloadTransport.FILE_DESCRIPTOR in decoded.supportedPayloadTransports)
    }

    @Test
    fun `multiplexed request frame preserves stream identity`() {
        val frame: OmniFrame = OmniFrame.Request(
            sessionId = "session-1",
            streamId = "build-42",
            sequence = 7,
            request = ActionRequest(
                name = "gradle.build",
                payload = buildJsonObject { put("variant", "debug") },
                correlationId = "corr-1"
            )
        )

        val encoded = OmniJson.instance.encodeToString<OmniFrame>(frame)
        val decoded = OmniJson.instance.decodeFromString<OmniFrame>(encoded)

        assertTrue(decoded is OmniFrame.Request)
        decoded as OmniFrame.Request
        assertEquals("build-42", decoded.streamId)
        assertEquals(7, decoded.sequence)
        assertEquals("corr-1", decoded.request.correlationId)
    }
}
