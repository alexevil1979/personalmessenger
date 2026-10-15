package ru.pm.messenger

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import redis.embedded.RedisServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StageOneVerificationTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun healthReportsRealDependenciesAndDoesNotClaimE2ee() {
        val response = http("GET", "/api/v1/health")
        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(body.contains("\"status\":\"UP\"") || body.contains("\"status\" : \"UP\""))
        assertTrue(body.contains("\"postgres\""))
        assertTrue(body.contains("\"redis\""))
        assertTrue(body.contains("NOT_ENABLED"))
        assertTrue(body.contains("0.6.3"))
        assertTrue(response.headers().firstValue("X-Correlation-Id").isPresent)
        assertEquals(false, body.contains("ciphertext"))
    }

    @Test
    fun unknownApiRequiresAuthentication() {
        val response = http("GET", "/api/v1/users")
        assertEquals(401, response.statusCode())
        assertTrue(response.body().contains("unauthorized"))
    }

    @Test
    fun openApiListsHealthEndpoint() {
        val response = http("GET", "/v3/api-docs")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("/api/v1/health"))
    }

    @Test
    fun migrationCreatesCoreTablesAndRejectsDuplicateMessages() {
        val tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String::class.java,
        )
        assertTrue(tables.containsAll(EXPECTED_TABLES))
        val passwordColumns = jdbc.queryForList(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND column_name = 'password'
            """.trimIndent(),
            String::class.java,
        )
        assertTrue(passwordColumns.isEmpty())

        val userA = insertUser("alice")
        val userB = insertUser("bob")
        val device = insertDevice(userA)
        val conversation = insertDirectConversation(userA, userB)
        val clientMessageId = UUID.randomUUID()
        insertMessage(conversation, userA, device, clientMessageId)
        val duplicate = runCatching {
            insertMessage(conversation, userA, device, clientMessageId)
        }
        assertTrue(duplicate.isFailure)
    }

    private fun insertUser(username: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username, display_name, password_hash) VALUES (?, ?, ?, ?)",
            id,
            username,
            username,
            "not-a-real-login-hash",
        )
        return id
    }

    private fun insertDevice(userId: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO devices (id, user_id, name, platform) VALUES (?, ?, ?, 'android')",
            id,
            userId,
            "phone",
        )
        return id
    }

    private fun insertDirectConversation(userA: UUID, userB: UUID): UUID {
        val id = UUID.randomUUID()
        val pair = listOf(userA, userB).sorted().joinToString(":")
        jdbc.update(
            "INSERT INTO conversations (id, type, direct_key, created_by) VALUES (?, 'direct', ?, ?)",
            id,
            pair,
            userA,
        )
        jdbc.update(
            "INSERT INTO conversation_members (conversation_id, user_id, role) VALUES (?, ?, 'member')",
            id,
            userA,
        )
        jdbc.update(
            "INSERT INTO conversation_members (conversation_id, user_id, role) VALUES (?, ?, 'member')",
            id,
            userB,
        )
        return id
    }

    private fun insertMessage(conversationId: UUID, sender: UUID, device: UUID, clientMessageId: UUID) {
        jdbc.update(
            """
            INSERT INTO messages (
                conversation_id, sender_user_id, sender_device_id, client_message_id,
                kind, encryption, ciphertext
            ) VALUES (?, ?, ?, ?, 'text', 'none', ?)
            """.trimIndent(),
            conversationId,
            sender,
            device,
            clientMessageId,
            "stage-1-plaintext".toByteArray(),
        )
    }

    private fun http(method: String, path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .header("X-Correlation-Id", "stage1-test")
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val postgres = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start()
        private val redisPort = 16379
        private val redisServer: RedisServer = RedisServer.newRedisServer()
            .port(redisPort)
            .setting("bind 127.0.0.1")
            .setting("daemonize no")
            .build()
            .also { it.start() }

        private val EXPECTED_TABLES = listOf(
            "users",
            "devices",
            "sessions",
            "invitations",
            "invitation_uses",
            "contacts",
            "blocks",
            "conversations",
            "conversation_members",
            "messages",
            "message_receipts",
            "attachments",
            "calls",
            "push_tokens",
            "admin_roles",
            "audit_events",
            "app_settings",
            "outbox_events",
        )

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.getJdbcUrl("postgres", "postgres") }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "postgres" }
            registry.add("DB_PASSWORD") { "postgres" }
            registry.add("spring.data.redis.host") { "127.0.0.1" }
            registry.add("spring.data.redis.port") { redisPort.toString() }
            registry.add("REDIS_PASSWORD") { "" }
            registry.add("app.storage.endpoint") { "" }
        }

        @JvmStatic
        @AfterAll
        fun stopInfrastructure() {
            redisServer.stop()
            postgres.close()
        }
    }
}
