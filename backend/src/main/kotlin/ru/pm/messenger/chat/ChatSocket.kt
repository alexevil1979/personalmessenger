package ru.pm.messenger.chat

import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import ru.pm.messenger.identity.AuthService
import ru.pm.messenger.identity.MessengerPrincipal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class LiveSessions {
    private val sessions = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    fun add(userId: UUID, session: WebSocketSession) {
        sessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun remove(userId: UUID, session: WebSocketSession) {
        sessions[userId]?.remove(session)
    }

    fun publish(message: MessageView, userIds: Collection<UUID>) {
        val payload = messageEvent(message)
        userIds.forEach { userId -> deliver(userId, payload) }
    }

    private fun deliver(userId: UUID, payload: String) {
        val targets = sessions[userId] ?: return
        val stale = mutableListOf<WebSocketSession>()
        for (session in targets) {
            if (!session.isOpen) {
                stale.add(session)
                continue
            }
            try {
                synchronized(session) {
                    session.sendMessage(TextMessage(payload))
                }
            } catch (_: Exception) {
                stale.add(session)
            }
        }
        stale.forEach { targets.remove(it) }
    }
}

@Component
class ChatSocketHandler(
    private val live: LiveSessions,
) : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) {
        val principal = session.attributes["principal"] as? MessengerPrincipal
        if (principal == null) {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        session.attributes["userId"] = principal.userId
        live.add(principal.userId, session)
        synchronized(session) {
            session.sendMessage(TextMessage("""{"type":"ready"}"""))
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val userId = session.attributes["userId"] as? UUID ?: return
        live.remove(userId, session)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) = Unit
}

@Component
class ChatHandshakeInterceptor(
    private val auth: AuthService,
) : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val servletRequest = (request as? ServletServerHttpRequest)?.servletRequest ?: return false
        val header = servletRequest.getHeader("Authorization") ?: return false
        if (!header.startsWith("Bearer ")) return false
        val principal = auth.authenticate(header.removePrefix("Bearer ").trim()) ?: return false
        attributes["principal"] = principal
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit
}

internal fun messageEvent(message: MessageView): String = buildString {
    append("""{"type":"message","message":{""")
    append(""""seq":${message.seq},""")
    append(""""id":${json(message.id.toString())},""")
    append(""""conversationId":${json(message.conversationId.toString())},""")
    append(""""senderUserId":${json(message.senderUserId.toString())},""")
    append(""""senderUsername":${json(message.senderUsername)},""")
    append(""""clientMessageId":${json(message.clientMessageId.toString())},""")
    append(""""text":${json(message.text)},""")
    append(""""encryption":${json(message.encryption)},""")
    append(""""createdAt":${json(message.createdAt.toString())},""")
    append(""""kind":${json(message.kind)},""")
    append(""""attachmentId":${message.attachmentId?.let { json(it.toString()) } ?: "null"},""")
    append(""""attachmentName":${message.attachmentName?.let { json(it) } ?: "null"},""")
    append(""""mimeType":${message.mimeType?.let { json(it) } ?: "null"}""")
    append("}}")
}

private fun json(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
