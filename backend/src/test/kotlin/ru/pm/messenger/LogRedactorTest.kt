package ru.pm.messenger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.pm.messenger.web.LogRedactor

class LogRedactorTest {
    @Test
    fun redactsSecretsAndBearerTokens() {
        val raw = "login password=hunter2-example Authorization: Bearer abc.def.ghi token:secret ciphertext=aabb"
        val redacted = LogRedactor.redact(raw)
        assertEquals(false, redacted.contains("hunter2-example"))
        assertEquals(false, redacted.contains("abc.def.ghi"))
        assertEquals(false, redacted.contains("aabb"))
        assertEquals(true, redacted.contains("password=[redacted]"))
        assertEquals(true, redacted.contains("Bearer [redacted]"))
    }
}
