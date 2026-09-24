package com.omnilink.sdk

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidPayloadBrokerTest {

    @Test
    fun `broker rejects file URIs and unapproved authorities before opening`() {
        val context = RuntimeEnvironment.getApplication()
        val fileUri = PayloadDescriptor("file", PayloadTransport.CONTENT_URI, 3, uri = "file:///tmp/secret")
        val wrongProvider = PayloadDescriptor(
            "wrong", PayloadTransport.CONTENT_URI, 3, uri = "content://other.provider/file"
        )
        assertTrue(runCatching { AndroidPayloadBroker.openReadOnly(context, fileUri) }.exceptionOrNull()
            is IllegalArgumentException)
        assertTrue(runCatching {
            AndroidPayloadBroker.openReadOnly(context, wrongProvider, "approved.provider")
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `failed copy preserves the old destination and cleans temporary files`() = runBlocking {
        val directory = Files.createTempDirectory("omnilink-payload").toFile()
        try {
            val destination = directory.resolve("existing.bin").apply { writeText("original") }
            val descriptor = PayloadDescriptor(
                "unavailable", PayloadTransport.CONTENT_URI, 4,
                uri = "content://missing.provider/no-file"
            )
            assertTrue(runCatching {
                AndroidPayloadBroker.copyContentUri(
                    RuntimeEnvironment.getApplication(), descriptor, destination,
                    expectedAuthority = "missing.provider"
                )
            }.isFailure)
            assertEquals("original", destination.readText())
            assertEquals(listOf("existing.bin"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }
}
