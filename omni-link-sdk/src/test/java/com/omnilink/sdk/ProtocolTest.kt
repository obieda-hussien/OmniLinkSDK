package com.omnilink.sdk

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun `test ActionRequest serialization round trip`() {
        val payload = buildJsonObject {
            put("key1", "value1")
            putJsonObject("nested") {
                put("nestedKey", 123)
            }
            putJsonArray("list") {
                add(buildJsonObject { put("item1", true) })
                add(buildJsonObject { put("item2", false) })
            }
        }

        val request = ActionRequest(
            name = "test_action",
            payload = payload
        )

        val jsonString = Json.encodeToString(request)
        val deserializedRequest = Json.decodeFromString<ActionRequest>(jsonString)

        assertEquals(request, deserializedRequest)
        assertEquals("test_action", deserializedRequest.name)
        assertEquals(payload, deserializedRequest.payload)
    }
}
