package ru.pm.messenger.chat

import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import ru.pm.messenger.identity.ApiException
import ru.pm.messenger.identity.MessengerPrincipal
import ru.pm.messenger.identity.ReadCursor
import ru.pm.messenger.storage.AttachmentService
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ChatService(
    private val jdbc: JdbcClient,
    private val attachments: AttachmentService,
    transactionManager: PlatformTransactionManager,
) {
    private val write = TransactionTemplate(transactionManager)
    @Transactional
    fun openDirect(principal: MessengerPrincipal, request: OpenDirectRequest): ConversationView {
        val username = normalizeUsername(request.username)
        val peer = findUser(username) ?: throw ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found.")
        if (peer.id == principal.userId) {
            throw ApiException(HttpStatus.BAD_REQUEST, "cannot_message_self", "Choose another person.")
        }
        if (blocked(principal.userId, peer.id)) {
            throw ApiException(HttpStatus.FORBIDDEN, "user_blocked", "This person is not available.")
        }
        val key = directKey(principal.userId, peer.id)
        val conversationId = findDirect(key) ?: insertDirect(principal.userId, key)
        ensureMember(conversationId, principal.userId, "member")
        ensureMember(conversationId, peer.id, "member")
        return requireConversation(principal, conversationId)
    }

    @Transactional
    fun createGroup(principal: MessengerPrincipal, request: CreateGroupRequest): ConversationView {
        val title = request.title.trim()
        if (title.isEmpty() || title.length > 80) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Group title is invalid.")
        }
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO conversations (id, type, title, created_by)
            VALUES (:id, 'group', :title, :createdBy)
            """.trimIndent(),
        ).param("id", id).param("title", title).param("createdBy", principal.userId).update()
        ensureMember(id, principal.userId, "owner")
        return requireConversation(principal, id)
    }

    @Transactional
    fun addMember(principal: MessengerPrincipal, conversationId: UUID, request: AddMemberRequest): ConversationView {
        val membership = membership(principal, conversationId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "conversation_not_found", "Conversation was not found.")
        if (membership.second != "group") {
            throw ApiException(HttpStatus.BAD_REQUEST, "not_a_group", "Members can be added only to a group.")
        }
        if (membership.first != "owner" && membership.first != "admin") {
            throw ApiException(HttpStatus.FORBIDDEN, "forbidden", "Access is denied.")
        }
        val username = normalizeUsername(request.username)
        val peer = findUser(username) ?: throw ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found.")
        if (peer.id == principal.userId) {
            throw ApiException(HttpStatus.BAD_REQUEST, "cannot_add_self", "Choose another person.")
        }
        if (blocked(principal.userId, peer.id)) {
            throw ApiException(HttpStatus.FORBIDDEN, "user_blocked", "This person is not available.")
        }
        ensureMember(conversationId, peer.id, "member")
        return requireConversation(principal, conversationId)
    }

    fun list(principal: MessengerPrincipal): List<ConversationView> =
        jdbc.sql(
            """
            SELECT c.id, c.type, me.role AS my_role,
                   COALESCE(u.username, '') AS username,
                   COALESCE(u.display_name, c.title, '') AS display_name,
                   c.updated_at, m.seq, m.ciphertext, m.encryption
            FROM conversation_members me
            JOIN conversations c ON c.id = me.conversation_id
            LEFT JOIN conversation_members other
              ON other.conversation_id = c.id
             AND other.user_id <> me.user_id
             AND other.left_at IS NULL
             AND c.type = 'direct'
            LEFT JOIN users u ON u.id = other.user_id AND u.deleted_at IS NULL
            LEFT JOIN LATERAL (
                SELECT seq, ciphertext, encryption
                FROM messages
                WHERE conversation_id = c.id AND deleted_at IS NULL
                ORDER BY seq DESC
                LIMIT 1
            ) m ON true
            WHERE me.user_id = :userId AND me.left_at IS NULL
            ORDER BY c.updated_at DESC
            LIMIT 100
            """.trimIndent(),
        ).param("userId", principal.userId)
            .query { rs, _ -> rs.conversation() }
            .list()

    fun messages(principal: MessengerPrincipal, conversationId: UUID, afterSeq: Long): List<MessageView> {
        requireMember(principal, conversationId)
        if (afterSeq < 0) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Cursor is invalid.")
        }
        return jdbc.sql(
            """
            SELECT m.seq, m.id, m.conversation_id, m.sender_user_id, u.username,
                   m.client_message_id, m.ciphertext, m.encryption, m.created_at,
                   m.kind, a.id AS attachment_id, a.original_name, a.mime_type
            FROM messages m
            JOIN users u ON u.id = m.sender_user_id
            LEFT JOIN attachments a ON a.message_id = m.id
            WHERE m.conversation_id = :conversationId
              AND m.seq > :afterSeq
              AND m.deleted_at IS NULL
            ORDER BY m.seq ASC
            LIMIT 200
            """.trimIndent(),
        ).param("conversationId", conversationId)
            .param("afterSeq", afterSeq)
            .query { rs, _ -> rs.message() }
            .list()
    }

    @Transactional
    fun leave(principal: MessengerPrincipal, conversationId: UUID) {
        val membership = membership(principal, conversationId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "conversation_not_found", "Conversation was not found.")
        if (membership.second == "group" && membership.first == "owner") {
            val others = jdbc.sql(
                """
                SELECT count(*) FROM conversation_members
                WHERE conversation_id = :id AND user_id <> :userId AND left_at IS NULL
                """.trimIndent(),
            ).param("id", conversationId).param("userId", principal.userId).query(Long::class.java).single()
            if (others > 0) {
                throw ApiException(HttpStatus.BAD_REQUEST, "owner_must_stay", "The owner stays while other people are in the group.")
            }
        }
        markLeft(conversationId, principal.userId)
    }

    @Transactional
    fun removeMember(principal: MessengerPrincipal, conversationId: UUID, username: String) {
        val membership = membership(principal, conversationId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "conversation_not_found", "Conversation was not found.")
        if (membership.second != "group") {
            throw ApiException(HttpStatus.BAD_REQUEST, "not_a_group", "Members can be removed only from a group.")
        }
        if (membership.first != "owner" && membership.first != "admin") {
            throw ApiException(HttpStatus.FORBIDDEN, "forbidden", "Access is denied.")
        }
        val name = username.trim().lowercase()
        if (!name.matches(Regex("^[a-z0-9_]{3,32}$"))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Username is invalid.")
        }
        val target = jdbc.sql(
            """
            SELECT u.id, me.role
            FROM users u
            JOIN conversation_members me ON me.user_id = u.id AND me.conversation_id = :conversationId AND me.left_at IS NULL
            WHERE u.username = :username AND u.deleted_at IS NULL
            """.trimIndent(),
        ).param("conversationId", conversationId).param("username", name)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("role") }
            .optional()
            .orElse(null)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found.")
        if (target.first == principal.userId) {
            throw ApiException(HttpStatus.BAD_REQUEST, "cannot_remove_self", "Leave the group instead.")
        }
        if (target.second == "owner" || (membership.first == "admin" && target.second == "admin")) {
            throw ApiException(HttpStatus.FORBIDDEN, "forbidden", "Access is denied.")
        }
        markLeft(conversationId, target.first)
    }

    @Transactional
    fun markRead(principal: MessengerPrincipal, conversationId: UUID, seq: Long): ReadCursor {
        requireMember(principal, conversationId)
        if (seq < 0) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Cursor is invalid.")
        }
        val maxSeq = jdbc.sql(
            """
            SELECT COALESCE(MAX(seq), 0) FROM messages
            WHERE conversation_id = :id AND deleted_at IS NULL
            """.trimIndent(),
        ).param("id", conversationId).query(Long::class.java).single()
        val stored = minOf(seq, maxSeq)
        jdbc.sql(
            """
            UPDATE conversation_members
            SET last_read_seq = GREATEST(COALESCE(last_read_seq, 0), :seq)
            WHERE conversation_id = :id AND user_id = :userId AND left_at IS NULL
            """.trimIndent(),
        ).param("seq", stored).param("id", conversationId).param("userId", principal.userId).update()
        return ReadCursor(stored)
    }

    fun send(principal: MessengerPrincipal, conversationId: UUID, request: SendMessageRequest): MessageView {
        requireMember(principal, conversationId)
        rejectBlockedDirect(principal, conversationId)
        val text = request.text.trim()
        val attachment = request.attachmentId?.let { attachments.requireSendable(principal, conversationId, it) }
        if ((text.isEmpty() && attachment == null) || text.length > 4000) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Message text is invalid.")
        }
        val storedText = text.ifEmpty { attachment!!.originalName }
        val bytes = storedText.toByteArray(Charsets.UTF_8)
        return try {
            write.execute<MessageView> {
            val created = jdbc.sql(
                """
                INSERT INTO messages (
                    id, conversation_id, sender_user_id, sender_device_id, client_message_id,
                    kind, encryption, ciphertext
                ) VALUES (
                    :id, :conversationId, :senderUserId, :senderDeviceId, :clientMessageId,
                    :kind, 'none', :ciphertext
                )
                RETURNING seq, id, created_at
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
                .param("conversationId", conversationId)
                .param("senderUserId", principal.userId)
                .param("senderDeviceId", principal.deviceId)
                .param("clientMessageId", request.clientMessageId)
                .param("kind", attachment?.kind ?: "text")
                .param("ciphertext", bytes)
                .query { rs, _ ->
                    Triple(rs.getLong("seq"), rs.getObject("id", UUID::class.java), rs.time("created_at"))
                }
                .single()
            jdbc.sql("UPDATE conversations SET updated_at = now() WHERE id = :id")
                .param("id", conversationId)
                .update()
            if (attachment != null) {
                attachments.bind(principal, conversationId, attachment.id, created.second)
            }
            MessageView(
                seq = created.first,
                id = created.second,
                conversationId = conversationId,
                senderUserId = principal.userId,
                senderUsername = usernameOf(principal.userId),
                clientMessageId = request.clientMessageId,
                text = storedText,
                encryption = "none",
                createdAt = created.third,
                kind = attachment?.kind ?: "text",
                attachmentId = attachment?.id,
                attachmentName = attachment?.originalName,
                mimeType = attachment?.mimeType,
            )
            } ?: throw ApiException(HttpStatus.CONFLICT, "client_message_id", "Message identity is already used.")
        } catch (_: DataIntegrityViolationException) {
            existingMessage(principal, conversationId, request.clientMessageId)
        }
    }

    fun memberIds(conversationId: UUID): List<UUID> =
        jdbc.sql(
            """
            SELECT user_id FROM conversation_members
            WHERE conversation_id = :id AND left_at IS NULL
            """.trimIndent(),
        ).param("id", conversationId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    private fun existingMessage(
        principal: MessengerPrincipal,
        conversationId: UUID,
        clientMessageId: UUID,
    ): MessageView {
        val message = jdbc.sql(
            """
            SELECT m.seq, m.id, m.conversation_id, m.sender_user_id, u.username,
                   m.client_message_id, m.ciphertext, m.encryption, m.created_at,
                   m.kind, a.id AS attachment_id, a.original_name, a.mime_type
            FROM messages m
            JOIN users u ON u.id = m.sender_user_id
            LEFT JOIN attachments a ON a.message_id = m.id
            WHERE m.sender_device_id = :deviceId AND m.client_message_id = :clientMessageId
            """.trimIndent(),
        ).param("deviceId", principal.deviceId)
            .param("clientMessageId", clientMessageId)
            .query { rs, _ -> rs.message() }
            .optional()
            .orElse(null)
            ?: throw ApiException(HttpStatus.CONFLICT, "client_message_id", "Message identity is already used.")
        if (message.conversationId != conversationId) {
            throw ApiException(HttpStatus.CONFLICT, "client_message_id", "Message identity is already used.")
        }
        return message
    }

    private fun requireConversation(principal: MessengerPrincipal, conversationId: UUID): ConversationView =
        list(principal).firstOrNull { it.id == conversationId }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "conversation_not_found", "Conversation was not found.")

    private fun markLeft(conversationId: UUID, userId: UUID) {
        jdbc.sql(
            """
            UPDATE conversation_members SET left_at = now()
            WHERE conversation_id = :conversationId AND user_id = :userId AND left_at IS NULL
            """.trimIndent(),
        ).param("conversationId", conversationId).param("userId", userId).update()
    }

    private fun rejectBlockedDirect(principal: MessengerPrincipal, conversationId: UUID) {
        val type = jdbc.sql("SELECT type FROM conversations WHERE id = :id")
            .param("id", conversationId)
            .query(String::class.java)
            .optional()
            .orElse(null) ?: return
        if (type != "direct") return
        val peer = jdbc.sql(
            """
            SELECT user_id FROM conversation_members
            WHERE conversation_id = :id AND user_id <> :userId AND left_at IS NULL
            """.trimIndent(),
        ).param("id", conversationId).param("userId", principal.userId)
            .query(UUID::class.java)
            .optional()
            .orElse(null) ?: return
        if (blocked(principal.userId, peer)) {
            throw ApiException(HttpStatus.FORBIDDEN, "user_blocked", "This person is not available.")
        }
    }

    private fun requireMember(principal: MessengerPrincipal, conversationId: UUID) {
        val member = jdbc.sql(
            """
            SELECT count(*) FROM conversation_members
            WHERE conversation_id = :conversationId AND user_id = :userId AND left_at IS NULL
            """.trimIndent(),
        ).param("conversationId", conversationId)
            .param("userId", principal.userId)
            .query(Long::class.java)
            .single()
        if (member == 0L) {
            throw ApiException(HttpStatus.NOT_FOUND, "conversation_not_found", "Conversation was not found.")
        }
    }

    private fun findDirect(key: String): UUID? =
        jdbc.sql("SELECT id FROM conversations WHERE direct_key = :key")
            .param("key", key)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    private fun insertDirect(createdBy: UUID, key: String): UUID {
        val id = UUID.randomUUID()
        return try {
            jdbc.sql(
                """
                INSERT INTO conversations (id, type, created_by, direct_key)
                VALUES (:id, 'direct', :createdBy, :key)
                """.trimIndent(),
            ).param("id", id).param("createdBy", createdBy).param("key", key).update()
            id
        } catch (_: DuplicateKeyException) {
            findDirect(key) ?: throw ApiException(HttpStatus.CONFLICT, "conversation_conflict", "Conversation was not created.")
        }
    }

    private fun ensureMember(conversationId: UUID, userId: UUID, role: String) {
        jdbc.sql(
            """
            INSERT INTO conversation_members (conversation_id, user_id, role)
            VALUES (:conversationId, :userId, :role)
            ON CONFLICT (conversation_id, user_id) DO UPDATE
            SET left_at = NULL, role = EXCLUDED.role
            WHERE conversation_members.left_at IS NOT NULL
            """.trimIndent(),
        ).param("conversationId", conversationId).param("userId", userId).param("role", role).update()
    }

    private fun membership(principal: MessengerPrincipal, conversationId: UUID): Pair<String, String>? =
        jdbc.sql(
            """
            SELECT me.role, c.type
            FROM conversation_members me
            JOIN conversations c ON c.id = me.conversation_id
            WHERE me.conversation_id = :conversationId AND me.user_id = :userId AND me.left_at IS NULL
            """.trimIndent(),
        ).param("conversationId", conversationId)
            .param("userId", principal.userId)
            .query { rs, _ -> rs.getString("role") to rs.getString("type") }
            .optional()
            .orElse(null)

    private fun findUser(username: String): StoredPeer? =
        jdbc.sql(
            """
            SELECT id, username, display_name FROM users
            WHERE username = :username AND deleted_at IS NULL AND status = 'active'
            """.trimIndent(),
        ).param("username", username)
            .query { rs, _ ->
                StoredPeer(rs.uuid("id"), rs.getString("username"), rs.getString("display_name"))
            }
            .optional()
            .orElse(null)

    private fun usernameOf(userId: UUID): String =
        jdbc.sql("SELECT username FROM users WHERE id = :id")
            .param("id", userId)
            .query(String::class.java)
            .single()

    private fun blocked(first: UUID, second: UUID): Boolean =
        jdbc.sql(
            """
            SELECT count(*) FROM blocks
            WHERE (blocker_user_id = :first AND blocked_user_id = :second)
               OR (blocker_user_id = :second AND blocked_user_id = :first)
            """.trimIndent(),
        ).param("first", first).param("second", second).query(Long::class.java).single() > 0

    private fun normalizeUsername(value: String): String {
        val username = value.trim().lowercase()
        if (!username.matches(Regex("^[a-z0-9_]{3,32}$"))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Username is invalid.")
        }
        return username
    }

    private fun ResultSet.conversation(): ConversationView {
        val encryption = getString("encryption")
        val preview = if (encryption == null || encryption == "none") {
            getBytes("ciphertext")?.toString(Charsets.UTF_8)?.take(80)
        } else {
            null
        }
        return ConversationView(
            id = uuid("id"),
            type = getString("type"),
            myRole = getString("my_role"),
            peerUsername = getString("username"),
            peerDisplayName = getString("display_name"),
            lastSeq = getObject("seq")?.let { getLong("seq") },
            preview = preview,
            updatedAt = time("updated_at"),
        )
    }

    private fun ResultSet.message(): MessageView {
        val encryption = getString("encryption")
        val text = if (encryption == "none") {
            getBytes("ciphertext")?.toString(Charsets.UTF_8).orEmpty()
        } else {
            ""
        }
        return MessageView(
            seq = getLong("seq"),
            id = uuid("id"),
            conversationId = uuid("conversation_id"),
            senderUserId = uuid("sender_user_id"),
            senderUsername = getString("username"),
            clientMessageId = uuid("client_message_id"),
            text = text,
            encryption = encryption,
            createdAt = time("created_at"),
            kind = getString("kind"),
            attachmentId = getString("attachment_id")?.let(UUID::fromString),
            attachmentName = getString("original_name"),
            mimeType = getString("mime_type"),
        )
    }

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    private fun ResultSet.time(column: String): OffsetDateTime = getObject(column, OffsetDateTime::class.java)

    private data class StoredPeer(val id: UUID, val username: String, val displayName: String)
}

internal fun directKey(first: UUID, second: UUID): String {
    val left = first.toString()
    val right = second.toString()
    return if (left < right) "$left:$right" else "$right:$left"
}
