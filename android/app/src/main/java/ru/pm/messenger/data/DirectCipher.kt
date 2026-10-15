package ru.pm.messenger.data

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DirectCipher {
    private const val VERSION: Byte = 1
    private const val PUBLIC_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private val random = SecureRandom()

    data class Identity(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generate(): Identity {
        val generator = X25519KeyPairGenerator()
        generator.init(KeyGenerationParameters(random, 255))
        val pair = generator.generateKeyPair()
        return Identity(
            privateKey = (pair.private as X25519PrivateKeyParameters).encoded,
            publicKey = (pair.public as X25519PublicKeyParameters).encoded,
        )
    }

    fun seal(sender: Identity, recipientPublic: ByteArray, plaintext: ByteArray): ByteArray {
        require(recipientPublic.size == PUBLIC_BYTES)
        val ephemeral = generate()
        val key = messageKey(sender.privateKey, ephemeral.privateKey, ephemeral.publicKey, sender.publicKey, recipientPublic)
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val encrypted = aes(Cipher.ENCRYPT_MODE, key, nonce, sender.publicKey + recipientPublic, plaintext)
        return byteArrayOf(VERSION) + ephemeral.publicKey + nonce + encrypted
    }

    fun open(recipientPrivate: ByteArray, recipientPublic: ByteArray, senderPublic: ByteArray, box: ByteArray): ByteArray? {
        if (recipientPrivate.size != PUBLIC_BYTES || recipientPublic.size != PUBLIC_BYTES || senderPublic.size != PUBLIC_BYTES) {
            return null
        }
        if (box.size < 1 + PUBLIC_BYTES + NONCE_BYTES + TAG_BYTES || box[0] != VERSION) return null
        val ephemeralPublic = box.copyOfRange(1, 1 + PUBLIC_BYTES)
        val nonce = box.copyOfRange(1 + PUBLIC_BYTES, 1 + PUBLIC_BYTES + NONCE_BYTES)
        val encrypted = box.copyOfRange(1 + PUBLIC_BYTES + NONCE_BYTES, box.size)
        val key = messageKey(recipientPrivate, recipientPrivate, ephemeralPublic, senderPublic, recipientPublic, recipientSide = true)
        return runCatching {
            aes(Cipher.DECRYPT_MODE, key, nonce, senderPublic + recipientPublic, encrypted)
        }.getOrNull()
    }

    private fun messageKey(
        staticPrivate: ByteArray,
        ephemeralPrivate: ByteArray,
        ephemeralPublic: ByteArray,
        senderPublic: ByteArray,
        recipientPublic: ByteArray,
        recipientSide: Boolean = false,
    ): ByteArray {
        val ephemeralSecret = if (recipientSide) {
            agree(staticPrivate, ephemeralPublic)
        } else {
            agree(ephemeralPrivate, recipientPublic)
        }
        val staticSecret = if (recipientSide) {
            agree(staticPrivate, senderPublic)
        } else {
            agree(staticPrivate, recipientPublic)
        }
        val info = "pm-x25519-v1".toByteArray(Charsets.US_ASCII) + senderPublic + recipientPublic
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ephemeralSecret + staticSecret, ephemeralPublic, info))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, key.size)
        return key
    }

    private fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), shared, 0)
        return shared
    }

    private fun aes(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }
}
