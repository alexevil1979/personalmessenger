package ru.pm.messenger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.net.http.WebSocket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ChatFlowTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var passwords: PasswordEncoder

    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()
    private lateinit var alice: String
    private lateinit var bob: String
    private lateinit var carol: String
    private lateinit var conversationId: String
    private lateinit var clientMessageId: String
    private var seq: Long = 0

    @BeforeAll
    fun seed() {
        seedUser("alice")
        seedUser("bob")
        seedUser("carol")
        alice = token("alice")
        bob = token("bob")
        carol = token("carol")
    }

    @Test
    @Order(1)
    fun directChatIsUniqueForThePair() {
        val opened = post("/api/v1/conversations/direct", mapOf("username" to "bob"), alice)
        assertEquals(200, opened.statusCode())
        conversationId = opened.body.path("id").asText()
        val again = post("/api/v1/conversations/direct", mapOf("username" to "alice"), bob)
        assertEquals(200, again.statusCode())
        assertEquals(conversationId, again.body.path("id").asText())
        assertEquals("alice", again.body.path("peerUsername").asText())
    }

    @Test
    @Order(2)
    fun messageIsStoredFetchedAndDeduplicated() {
        clientMessageId = UUID.randomUUID().toString()
        val sent = post(
            "/api/v1/conversations/$conversationId/messages",
            mapOf("clientMessageId" to clientMessageId, "text" to "привет"),
            alice,
        )
        assertEquals(200, sent.statusCode())
        seq = sent.body.path("seq").asLong()
        assertEquals("none", sent.body.path("encryption").asText())
        assertEquals("привет", sent.body.path("text").asText())
        val repeat = post(
            "/api/v1/conversations/$conversationId/messages",
            mapOf("clientMessageId" to clientMessageId, "text" to "привет"),
            alice,
        )
        assertEquals(200, repeat.statusCode())
        assertEquals(seq, repeat.body.path("seq").asLong())
        val fetched = get("/api/v1/conversations/$conversationId/messages?afterSeq=0", bob)
        assertEquals(200, fetched.statusCode())
        assertTrue(fetched.raw.contains("привет"))
        val hidden = get("/api/v1/conversations/$conversationId/messages?afterSeq=0", carol)
        assertEquals(404, hidden.statusCode())
    }

    @Test
    @Order(3)
    fun websocketDeliversTheNextMessage() {
        val received = ConcurrentLinkedQueue<String>()
        val socket = http.newWebSocketBuilder()
            .header("Authorization", "Bearer $bob")
            .buildAsync(URI.create("ws://127.0.0.1:$port/api/v1/ws"), object : WebSocket.Listener {
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                    received.add(data.toString())
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(null)
                }
            })
            .get(5, TimeUnit.SECONDS)
        socket.request(1)
        val readyDeadline = System.currentTimeMillis() + 5000
        while (received.none { it.contains("ready") } && System.currentTimeMillis() < readyDeadline) {
            Thread.sleep(50)
        }
        assertTrue(received.any { it.contains("ready") })
        val nextId = UUID.randomUUID().toString()
        val sent = post(
            "/api/v1/conversations/$conversationId/messages",
            mapOf("clientMessageId" to nextId, "text" to "ещё одно"),
            alice,
        )
        assertEquals(200, sent.statusCode())
        val deadline = System.currentTimeMillis() + 5000
        while (received.none { it.contains("ещё одно") } && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(received.joinToString().contains("ещё одно"))
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS)
    }

    @Test
    @Order(4)
    fun groupOwnerCanAddAMemberAndAMemberCannot() {
        val created = post("/api/v1/conversations/group", mapOf("title" to "семья"), alice)
        assertEquals(200, created.statusCode(), created.raw)
        val groupId = created.body.path("id").asText()
        assertEquals("group", created.body.path("type").asText())
        assertEquals("owner", created.body.path("myRole").asText())
        val added = post("/api/v1/conversations/$groupId/members", mapOf("username" to "bob"), alice)
        assertEquals(200, added.statusCode(), added.raw)
        val sent = post(
            "/api/v1/conversations/$groupId/messages",
            mapOf("clientMessageId" to UUID.randomUUID().toString(), "text" to "в группе"),
            alice,
        )
        assertEquals(200, sent.statusCode(), sent.raw)
        val seen = get("/api/v1/conversations/$groupId/messages?afterSeq=0", bob)
        assertEquals(200, seen.statusCode())
        assertTrue(seen.raw.contains("в группе"))
        val denied = post("/api/v1/conversations/$groupId/members", mapOf("username" to "carol"), bob)
        assertEquals(403, denied.statusCode())
        val hidden = get("/api/v1/conversations/$groupId/messages?afterSeq=0", carol)
        assertEquals(404, hidden.statusCode())
    }

    @Test
    @Order(5)
    fun memberUploadsAFileAndTheLinkExpires() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        val uploaded = uploadFile(conversationId, "photo.png", png, alice)
        assertEquals(200, uploaded.statusCode(), uploaded.raw)
        assertEquals("image/png", uploaded.body.path("mimeType").asText())
        assertEquals("attachment", uploaded.body.path("kind").asText())
        val attachmentId = uploaded.body.path("id").asText()
        val hidden = uploadFile(conversationId, "photo.png", png, carol)
        assertEquals(404, hidden.statusCode())
        val rejected = uploadFile(conversationId, "page.html", "<html>".toByteArray(), alice)
        assertEquals(415, rejected.statusCode())
        val tooBig = uploadFile(conversationId, "big.png", ByteArray(80), alice)
        assertEquals(413, tooBig.statusCode())
        val sent = post(
            "/api/v1/conversations/$conversationId/messages",
            mapOf("clientMessageId" to UUID.randomUUID().toString(), "text" to "", "attachmentId" to attachmentId),
            alice,
        )
        assertEquals(200, sent.statusCode(), sent.raw)
        assertEquals("attachment", sent.body.path("kind").asText())
        assertEquals("photo.png", sent.body.path("attachmentName").asText())
        val reused = post(
            "/api/v1/conversations/$conversationId/messages",
            mapOf("clientMessageId" to UUID.randomUUID().toString(), "text" to "", "attachmentId" to attachmentId),
            alice,
        )
        assertEquals(409, reused.statusCode())
        val voice = uploadFile(conversationId, "note.ogg", "OggSvoice".toByteArray(), alice)
        assertEquals(200, voice.statusCode(), voice.raw)
        assertEquals("voice", voice.body.path("kind").asText())
        val recorded = uploadFile(conversationId, "voice.amr", "#!AMR-WB\nvoice".toByteArray(), alice)
        assertEquals(200, recorded.statusCode(), recorded.raw)
        assertEquals("audio/amr-wb", recorded.body.path("mimeType").asText())
        assertEquals("voice", recorded.body.path("kind").asText())
        val amrSent = post(
            "/api/v1/conversations/$conversationId/messages",
            mapOf(
                "clientMessageId" to UUID.randomUUID().toString(),
                "text" to "",
                "attachmentId" to recorded.body.path("id").asText(),
            ),
            alice,
        )
        assertEquals(200, amrSent.statusCode(), amrSent.raw)
        assertEquals("voice", amrSent.body.path("kind").asText())
        val voiceSent = post(
            "/api/v1/conversations/$conversationId/messages",
            mapOf(
                "clientMessageId" to UUID.randomUUID().toString(),
                "text" to "",
                "attachmentId" to voice.body.path("id").asText(),
            ),
            alice,
        )
        assertEquals(200, voiceSent.statusCode(), voiceSent.raw)
        assertEquals("voice", voiceSent.body.path("kind").asText())
        val link = post("/api/v1/attachments/$attachmentId/link", emptyMap<String, String>(), bob)
        assertEquals(200, link.statusCode(), link.raw)
        val downloaded = fetch(link.body.path("url").asText())
        assertEquals(200, downloaded.first)
        assertTrue(png.contentEquals(downloaded.second))
        val stranger = post("/api/v1/attachments/$attachmentId/link", emptyMap<String, String>(), carol)
        assertEquals(404, stranger.statusCode())
        val deadline = System.currentTimeMillis() + 6000
        var expired = downloaded
        while (expired.first == 200 && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            expired = fetch(link.body.path("url").asText())
        }
        assertEquals(404, expired.first)
    }

    private fun seedUser(username: String) {
        jdbc.update(
            "INSERT INTO users (id, username, display_name, password_hash, privacy_policy_accepted_at) VALUES (?, ?, ?, ?, now())",
            UUID.randomUUID(),
            username,
            username,
            passwords.encode("$username-password-1"),
        )
    }

    private fun token(username: String): String {
        val login = post("/api/v1/auth/login", mapOf(
            "username" to username,
            "password" to "$username-password-1",
            "deviceName" to "phone",
            "platform" to "android",
        ))
        assertEquals(200, login.statusCode(), login.raw)
        return login.body.path("accessToken").asText()
    }

    private fun get(path: String, token: String): Call = send("GET", path, null, token)

    private fun post(path: String, body: Any, token: String? = null): Call = send("POST", path, body, token)

    private fun uploadFile(conversationId: String, fileName: String, bytes: ByteArray, token: String): Call {
        val boundary = "pm-boundary"
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val payload = head + bytes + tail
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/conversations/$conversationId/attachments"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val raw = response.body()
        return Call(response.statusCode(), json.readTree(raw), raw)
    }

    private fun fetch(url: String): Pair<Int, ByteArray> {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        return response.statusCode() to response.body()
    }

    private fun send(method: String, path: String, body: Any?, token: String?): Call {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        val publisher = if (body == null || method == "GET") {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
        }
        builder.method(method, publisher)
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val payload = response.body()
        val tree = if (payload.isBlank()) json.createArrayNode() else json.readTree(payload)
        return Call(response.statusCode(), tree, payload)
    }

    private data class Call(val status: Int, val body: JsonNode, val raw: String) {
        fun statusCode() = status
    }

    companion object {
        private val postgres = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start()
        private const val REDIS_PORT = 16381
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
            registry.add("app.storage.max-bytes") { "64" }
            registry.add("app.storage.link-ttl") { "2s" }
        }

        @JvmStatic
        @AfterAll
        fun stopInfrastructure() {
            redisServer.stop()
            postgres.close()
        }
    }
}
