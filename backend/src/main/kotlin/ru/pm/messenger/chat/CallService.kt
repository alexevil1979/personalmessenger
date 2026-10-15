package ru.pm.messenger.chat

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.pm.messenger.identity.ApiException
import ru.pm.messenger.identity.MessengerPrincipal
import ru.pm.messenger.identity.currentPrincipal
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

data class IceResponse(val iceServers: List<IceServer>)

@Service
class CallService(
    private val jdbc: JdbcClient,
    private val chat: ChatService,
    private val live: LiveSessions,
    @Value("\${app.turn.secret:}") private val turnSecret: String,
    @Value("\${app.turn.host:pm.1tlt.ru}") private val turnHost: String,
) {
    fun ice(): IceResponse {
        val servers = mutableListOf(IceServer(listOf("stun:stun.l.google.com:19302", "stun:$turnHost:3478")))
        if (turnSecret.isNotBlank()) {
            val username = (Instant.now().epochSecond + 3600).toString()
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(turnSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val credential = Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray(Charsets.UTF_8)))
            servers += IceServer(listOf("turn:$turnHost:3478?transport=udp"), username, credential)
        }
        return IceResponse(servers)
    }

    fun relay(principal: MessengerPrincipal, payload: String) {
        if (payload.length > 20_000) return
        val type = jsonStringField(payload, "type") ?: return
        if (type !in CALL_TYPES) return
        val conversationId = runCatching { UUID.fromString(jsonStringField(payload, "conversationId")) }.getOrNull() ?: return
        val callId = jsonStringField(payload, "callId").orEmpty()
        if (callId.length !in 8..80) return
        val peer = try {
            chat.requireDirectPeer(principal, conversationId)
        } catch (_: ApiException) {
            return
        }
        val callUuid = runCatching { UUID.fromString(callId) }.getOrNull() ?: return
        val kind = jsonStringField(payload, "kind").orEmpty().ifBlank { "audio" }
        if (type == "call-invite") {
            if (kind != "audio" && kind != "video") return
            jdbc.sql(
                """
                INSERT INTO calls (id, conversation_id, caller_user_id, callee_user_id, kind, state)
                VALUES (:id, :conversationId, :caller, :callee, :kind, 'ringing')
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
            ).param("id", callUuid)
                .param("conversationId", conversationId)
                .param("caller", principal.userId)
                .param("callee", peer)
                .param("kind", kind)
                .update()
        }
        if (type == "call-answer") {
            jdbc.sql(
                """
                UPDATE calls SET state = 'active', answered_at = now()
                WHERE id = :id AND state = 'ringing'
                """.trimIndent(),
            ).param("id", callUuid).update()
        }
        if (type == "call-hangup") {
            jdbc.sql(
                """
                UPDATE calls
                SET state = CASE WHEN answered_at IS NULL THEN 'missed' ELSE 'ended' END,
                    ended_at = now(),
                    end_reason = 'hangup'
                WHERE id = :id AND ended_at IS NULL
                """.trimIndent(),
            ).param("id", callUuid).update()
        }
        live.publishRaw(
            callEvent(
                type = type,
                callId = callId,
                conversationId = conversationId.toString(),
                kind = kind,
                sdp = jsonStringField(payload, "sdp").orEmpty(),
                candidate = jsonStringField(payload, "candidate").orEmpty(),
                sdpMid = jsonStringField(payload, "sdpMid").orEmpty(),
                sdpMLineIndex = jsonStringField(payload, "sdpMLineIndex")?.toIntOrNull() ?: 0,
                from = chat.username(principal.userId),
            ),
            listOf(peer),
        )
    }
}

private val CALL_TYPES = setOf("call-invite", "call-answer", "call-ice", "call-hangup")

internal fun jsonStringField(json: String, name: String): String? {
    val marker = "\"$name\""
    val key = json.indexOf(marker)
    if (key < 0) return null
    var index = key + marker.length
    while (index < json.length && json[index].isWhitespace()) index++
    if (index >= json.length || json[index] != ':') return null
    index++
    while (index < json.length && json[index].isWhitespace()) index++
    if (index >= json.length) return null
    if (json.startsWith("null", index)) return ""
    if (json[index] != '"') {
        val start = index
        while (index < json.length && (json[index].isDigit() || json[index] == '-')) index++
        return json.substring(start, index)
    }
    index++
    val out = StringBuilder()
    while (index < json.length) {
        val char = json[index]
        if (char == '\\') {
            index++
            if (index >= json.length) return null
            out.append(
                when (json[index]) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    '"' -> '"'
                    '\\' -> '\\'
                    else -> json[index]
                },
            )
        } else if (char == '"') {
            return out.toString()
        } else {
            out.append(char)
        }
        index++
    }
    return null
}

@RestController
@RequestMapping("/api/v1/calls")
class CallController(
    private val calls: CallService,
) {
    @GetMapping("/ice")
    fun ice(): IceResponse = calls.ice()
}
