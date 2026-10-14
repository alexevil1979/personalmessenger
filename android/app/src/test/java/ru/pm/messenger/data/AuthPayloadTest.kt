package ru.pm.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthPayloadTest {
    @Test
    fun parsesAccountAndOmitsNothingSecretFromProfileFields() {
        val account = parseAccount(
            """
            {"accessToken":"access","refreshToken":"refresh","sessionId":"session",
            "user":{"id":"user-1","username":"alice","displayName":"Alice","role":null}}
            """.trimIndent(),
            "https://pm.1tlt.ru/",
        )
        assertEquals("alice", account.username)
        assertEquals("Alice", account.displayName)
        assertNull(account.role)
        assertEquals("access", account.accessToken)
        assertEquals("https://pm.1tlt.ru", account.baseUrl)
    }

    @Test
    fun parsesErrorCodeWithoutRequiringAPasswordField() {
        val error = parseAuthError(
            """{"error":{"code":"invalid_credentials","message":"Username or password is wrong."}}""",
            401,
        )
        assertEquals(401, error.status)
        assertEquals("invalid_credentials", error.code)
    }
}
