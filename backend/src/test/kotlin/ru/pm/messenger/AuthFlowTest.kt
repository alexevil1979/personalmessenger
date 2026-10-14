package ru.pm.messenger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AuthFlowTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var passwords: PasswordEncoder

    private val json = ObjectMapper()
    private val http = HttpClient.newHttpClient()
    private lateinit var adminAccess: String
    private lateinit var invitationCode: String
    private lateinit var userAccess: String
    private lateinit var userRefresh: String
    private lateinit var userSession: String

    @BeforeAll
    fun seedOwner() {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username, display_name, password_hash, privacy_policy_accepted_at) VALUES (?, ?, ?, ?, now())",
            id,
            "owner",
            "Owner",
            passwords.encode("owner-password-1"),
        )
        jdbc.update("INSERT INTO admin_roles (user_id, role) VALUES (?, 'owner')", id)
    }

    @Test
    @Order(1)
    fun ownerCanCreateInvitationAndCodeIsNotListedAgain() {
        val login = post("/api/v1/auth/login", mapOf(
            "username" to "owner",
            "password" to "owner-password-1",
            "deviceName" to "admin",
            "platform" to "admin",
        ))
        assertEquals(200, login.statusCode())
        adminAccess = login.body.path("accessToken").asText()
        val created = post("/api/v1/admin/invitations", mapOf(
            "expiresInHours" to 24,
            "maxUses" to 1,
            "note" to "first",
        ), adminAccess)
        assertEquals(201, created.statusCode())
        invitationCode = created.body.path("code").asText()
        assertTrue(invitationCode.length > 20)
        val listed = get("/api/v1/admin/invitations", adminAccess)
        assertEquals(200, listed.statusCode())
        assertFalse(listed.raw.contains(invitationCode))
        assertFalse(listed.raw.contains("codeHash"))
    }

    @Test
    @Order(2)
    fun invitationRegistersOnceAndSecondUseFails() {
        val first = post("/api/v1/auth/register", mapOf(
            "username" to "alice",
            "displayName" to "Alice",
            "password" to "alice-password-1",
            "invitationCode" to invitationCode,
            "deviceName" to "pixel",
            "platform" to "android",
            "privacyPolicyAccepted" to true,
        ))
        assertEquals(200, first.statusCode())
        userAccess = first.body.path("accessToken").asText()
        userRefresh = first.body.path("refreshToken").asText()
        userSession = first.body.path("sessionId").asText()
        val second = post("/api/v1/auth/register", mapOf(
            "username" to "bob",
            "displayName" to "Bob",
            "password" to "bob-password-12",
            "invitationCode" to invitationCode,
            "deviceName" to "phone",
            "platform" to "android",
            "privacyPolicyAccepted" to true,
        ))
        assertEquals(400, second.statusCode())
        assertEquals("invitation_invalid", second.body.path("error").path("code").asText())
    }

    @Test
    @Order(3)
    fun refreshRotatesAndReuseRevokesTheChain() {
        val refreshed = post("/api/v1/auth/refresh", mapOf("refreshToken" to userRefresh))
        assertEquals(200, refreshed.statusCode())
        val nextRefresh = refreshed.body.path("refreshToken").asText()
        assertNotEquals(userRefresh, nextRefresh)
        userAccess = refreshed.body.path("accessToken").asText()
        val reused = post("/api/v1/auth/refresh", mapOf("refreshToken" to userRefresh))
        assertEquals(401, reused.statusCode())
        val afterReuse = post("/api/v1/auth/refresh", mapOf("refreshToken" to nextRefresh))
        assertEquals(401, afterReuse.statusCode())
    }

    @Test
    @Order(4)
    fun userCannotRevokeSomeoneElsesSessionOrCreateInvitations() {
        val login = post("/api/v1/auth/login", mapOf(
            "username" to "alice",
            "password" to "alice-password-1",
            "deviceName" to "pixel-2",
            "platform" to "android",
        ))
        assertEquals(200, login.statusCode())
        userAccess = login.body.path("accessToken").asText()
        val ownerSession = jdbc.queryForObject(
            "SELECT s.id FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.username = 'owner' AND s.revoked_at IS NULL LIMIT 1",
            UUID::class.java,
        )
        val denied = post("/api/v1/sessions/$ownerSession/revoke", mapOf<String, String>(), userAccess)
        assertEquals(404, denied.statusCode())
        val forbidden = post("/api/v1/admin/invitations", mapOf(
            "expiresInHours" to 1,
            "maxUses" to 1,
            "note" to null,
        ), userAccess)
        assertEquals(403, forbidden.statusCode())
        assertTrue(get("/api/v1/me", userAccess).raw.contains("alice"))
    }

    @Test
    @Order(5)
    fun wrongPasswordDoesNotEchoThePassword() {
        val response = post("/api/v1/auth/login", mapOf(
            "username" to "alice",
            "password" to "not-the-real-password",
            "deviceName" to "pixel",
            "platform" to "android",
        ))
        assertEquals(401, response.statusCode())
        assertFalse(response.raw.contains("not-the-real-password"))
    }

    private fun get(path: String, token: String): Call = send("GET", path, null, token)

    private fun post(path: String, body: Any, token: String? = null): Call = send("POST", path, body, token)

    private fun send(method: String, path: String, body: Any?, token: String?): Call {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .header("X-Correlation-Id", "auth-flow-test")
        if (token != null) builder.header("Authorization", "Bearer $token")
        val publisher = if (body == null || method == "GET") {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
        }
        builder.method(method, publisher)
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val payload = response.body()
        val tree = if (payload.isBlank()) json.createObjectNode() else json.readTree(payload)
        return Call(response.statusCode(), tree, payload)
    }

    private data class Call(val status: Int, val body: JsonNode, val raw: String) {
        fun statusCode() = status
    }

    companion object {
        private val postgres = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start()
        private const val REDIS_PORT = 16380
        private val redisServer: RedisServer = RedisServer.newRedisServer()
            .port(REDIS_PORT)
            .setting("bind 127.0.0.1")
            .setting("daemonize no")
            .build()
            .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.getJdbcUrl("postgres", "postgres") }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "postgres" }
            registry.add("DB_PASSWORD") { "postgres" }
            registry.add("spring.data.redis.host") { "127.0.0.1" }
            registry.add("spring.data.redis.port") { REDIS_PORT.toString() }
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
