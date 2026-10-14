package ru.pm.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthSnapshotTest {
    @Test
    fun parsesHealthPayload() {
        val snapshot = parseHealthSnapshot(
            """
            {"status":"UP","version":"0.1.0","components":{"postgres":{"status":"UP"},"redis":{"status":"UP"}}}
            """.trimIndent(),
        )
        assertEquals("UP", snapshot.status)
        assertEquals("0.1.0", snapshot.version)
        assertEquals("UP", snapshot.postgres)
        assertEquals("UP", snapshot.redis)
    }
}
