package com.omnilink.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class EncryptedJvmIdentityStoreTest {

    @Test
    fun `desktop identity persists encrypted and reloads with same public key`() {
        val directory = Files.createTempDirectory("omnilink-identity-test").toFile()
        val file = directory.resolve("identity.json")
        val store = EncryptedJvmIdentityStore(file, iterations = 10_000)
        val original = JvmEcSigningIdentity.generate("desktop", PeerRole.DESKTOP)
        val password = "correct horse battery staple".toCharArray()

        store.save(original, password)
        val restored = store.load(password)

        assertArrayEquals(original.publicKeyEncoded, restored.publicKeyEncoded)
        assertTrue(!file.readText().contains(original.export().privateKeyPkcs8Hex))

        password.fill('0')
        directory.deleteRecursively()
    }
}
