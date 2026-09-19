package com.omnilink.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object BinaryMessageCodec {
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 256 * 1024
    private const val MAX_METADATA_ENTRIES = 256
    private const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

    fun encode(message: TransportMessage): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeByte(VERSION)
            data.writeByte(message.type.ordinal)
            writeString(data, message.capability)
            writeNullableString(data, message.correlationId)
            require(message.metadata.size <= MAX_METADATA_ENTRIES) { "Too many metadata entries" }
            data.writeInt(message.metadata.size)
            message.metadata.toSortedMap().forEach { (key, value) ->
                writeString(data, key)
                writeString(data, value)
            }
            require(message.payload.size <= MAX_PAYLOAD_BYTES) { "Payload too large" }
            data.writeInt(message.payload.size)
            data.write(message.payload)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): TransportMessage {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val version = data.readUnsignedByte()
            require(version == VERSION) { "Unsupported transport message version $version" }

            val typeOrdinal = data.readUnsignedByte()
            val type = TransportMessageType.entries.getOrNull(typeOrdinal)
                ?: throw IllegalArgumentException("Unknown message type $typeOrdinal")

            val capability = readString(data)
            val correlationId = readNullableString(data)
            val metadataCount = data.readInt()
            require(metadataCount in 0..MAX_METADATA_ENTRIES) { "Invalid metadata count" }

            val metadata = LinkedHashMap<String, String>(metadataCount)
            repeat(metadataCount) {
                metadata[readString(data)] = readString(data)
            }

            val payloadLength = data.readInt()
            require(payloadLength in 0..MAX_PAYLOAD_BYTES) { "Invalid payload length" }
            val payload = ByteArray(payloadLength)
            data.readFully(payload)

            require(data.available() == 0) { "Trailing bytes in transport message" }
            return TransportMessage(type, capability, correlationId, metadata, payload)
        }
    }

    private fun writeNullableString(data: DataOutputStream, value: String?) {
        data.writeBoolean(value != null)
        if (value != null) writeString(data, value)
    }

    private fun readNullableString(data: DataInputStream): String? =
        if (data.readBoolean()) readString(data) else null

    private fun writeString(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "String field too large" }
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun readString(data: DataInputStream): String {
        val length = data.readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid string length" }
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
