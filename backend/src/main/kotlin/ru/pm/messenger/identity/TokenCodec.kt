package ru.pm.messenger.identity

import org.springframework.http.HttpStatus

class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
) : RuntimeException(message)

object TokenCodec {
    private val random = java.security.SecureRandom()

    fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
