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

    fun online(userId: UUID): Boolean = sessions[userId]?.any { it.isOpen } == true

    fun publish(message: MessageView, userIds: Collection<UUID>) {
        publishRaw(messageEvent(message), userIds)
    }

    fun publishRaw(payload: String, userIds: Collection<UUID>) {
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
    private val chat: ChatService,
) : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) {
        val principal = session.attributes["principal"] as? MessengerPrincipal
        if (principal == null) {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        session.attributes["userId"] = principal.userId
        live.add(principal.userId, session)
        chat.notePresence(principal.userId)
        live.publishRaw(presenceEvent(chat.username(principal.userId), true), chat.directPeerIds(principal.userId))
        synchronized(session) {
            session.sendMessage(TextMessage("""{"type":"ready"}"""))
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val userId = session.attributes["userId"] as? UUID ?: return
        live.remove(userId, session)
        if (!live.online(userId)) {
            chat.notePresence(userId)
            live.publishRaw(presenceEvent(chat.username(userId), false), chat.directPeerIds(userId))
        }
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

internal fun readEvent(conversationId: UUID, username: String, seq: Long): String = buildString {
    append("""{"type":"read",""")
    append(""""conversationId":${json(conversationId.toString())},""")
    append(""""username":${json(username)},""")
    append(""""lastReadSeq":$seq""")
    append("}")
}

internal fun presenceEvent(username: String, online: Boolean): String = buildString {
    append("""{"type":"presence",""")
    append(""""username":${json(username)},""")
    append(""""online":$online""")
    append("}")
}

internal fun deletedEvent(deleted: DeletedMessage): String = buildString {
    append("""{"type":"deleted",""")
    append(""""conversationId":${json(deleted.conversationId.toString())},""")
    append(""""seq":${deleted.seq},""")
    append(""""clientMessageId":${json(deleted.clientMessageId.toString())}""")
    append("}")
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
    append(""""mimeType":${message.mimeType?.let { json(it) } ?: "null"},""")
    append(""""replyToSeq":${message.replyToSeq ?: "null"},""")
    append(""""replyToUsername":${message.replyToUsername?.let { json(it) } ?: "null"},""")
    append(""""replyToText":${message.replyToText?.let { json(it) } ?: "null"},""")
    append(""""edited":${message.edited}""")
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
