package ru.pm.messenger.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectCipherTest {
    @Test
    fun recipientOpensWhatSenderSealed() {
        val alice = DirectCipher.generate()
        val bob = DirectCipher.generate()
        val plaintext = "секрет личного чата".toByteArray()
        val box = DirectCipher.seal(alice, bob.publicKey, plaintext)
        val opened = DirectCipher.open(bob.privateKey, bob.publicKey, alice.publicKey, box)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun wrongSenderKeyDoesNotOpen() {
        val alice = DirectCipher.generate()
        val bob = DirectCipher.generate()
        val carol = DirectCipher.generate()
        val box = DirectCipher.seal(alice, bob.publicKey, "нет".toByteArray())
        assertNull(DirectCipher.open(bob.privateKey, bob.publicKey, carol.publicKey, box))
    }

    @Test
    fun changedByteDoesNotOpen() {
        val alice = DirectCipher.generate()
        val bob = DirectCipher.generate()
        val box = DirectCipher.seal(alice, bob.publicKey, "целое".toByteArray())
        box[box.lastIndex] = (box[box.lastIndex] + 1).toByte()
        assertNull(DirectCipher.open(bob.privateKey, bob.publicKey, alice.publicKey, box))
    }
}
