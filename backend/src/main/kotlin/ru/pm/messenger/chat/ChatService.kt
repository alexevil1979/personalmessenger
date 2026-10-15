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
    private val live: LiveSessions,
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
                   c.updated_at, m.seq, m.ciphertext, m.encryption,
                   COALESCE((
                       SELECT count(*)::int
                       FROM messages unread
                       WHERE unread.conversation_id = c.id
                         AND unread.deleted_at IS NULL
                         AND unread.sender_user_id <> me.user_id
                         AND unread.seq > COALESCE(me.last_read_seq, 0)
                   ), 0) AS unread_count,
                   COALESCE(other.last_read_seq, 0) AS peer_last_read_seq,
                   u.last_seen_at AS peer_last_seen_at,
                   other.user_id AS peer_user_id,
                   (
                       SELECT replace(encode(d.identity_public_key, 'base64'), E'\n', '')
                       FROM devices d
                       WHERE d.user_id = other.user_id
                         AND d.revoked_at IS NULL
                         AND d.identity_public_key IS NOT NULL
                       ORDER BY d.last_seen_at DESC NULLS LAST
                       LIMIT 1
                   ) AS peer_identity_key,
                   CASE WHEN m.encryption IS NOT NULL AND m.encryption <> 'none' THEN true ELSE false END AS last_encrypted
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
                   m.client_message_id, m.ciphertext, m.encryption, m.created_at, m.edited_at,
                   m.kind, a.id AS attachment_id, a.original_name, a.mime_type,
                   reply.seq AS reply_seq, reply_user.username AS reply_username,
                   reply.ciphertext AS reply_ciphertext, reply.encryption AS reply_encryption
            FROM messages m
            JOIN users u ON u.id = m.sender_user_id
            LEFT JOIN attachments a ON a.message_id = m.id
            LEFT JOIN messages reply ON reply.id = m.reply_to_id AND reply.deleted_at IS NULL
            LEFT JOIN users reply_user ON reply_user.id = reply.sender_user_id
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
        val previous = jdbc.sql(
            """
            SELECT COALESCE(last_read_seq, 0) FROM conversation_members
            WHERE conversation_id = :id AND user_id = :userId AND left_at IS NULL
            """.trimIndent(),
        ).param("id", conversationId).param("userId", principal.userId).query(Long::class.java).single()
        jdbc.sql(
            """
            UPDATE conversation_members
            SET last_read_seq = GREATEST(COALESCE(last_read_seq, 0), :seq)
            WHERE conversation_id = :id AND user_id = :userId AND left_at IS NULL
            """.trimIndent(),
        ).param("seq", stored).param("id", conversationId).param("userId", principal.userId).update()
        jdbc.sql(
            """
            INSERT INTO message_receipts (message_seq, user_id, status)
            SELECT m.seq, :userId, 'read'
            FROM messages m
            WHERE m.conversation_id = :id
              AND m.seq > :previous
              AND m.seq <= :seq
              AND m.sender_user_id <> :userId
              AND m.deleted_at IS NULL
            ON CONFLICT (message_seq, user_id) DO UPDATE
            SET status = 'read', updated_at = now()
            """.trimIndent(),
        ).param("userId", principal.userId)
            .param("id", conversationId)
            .param("previous", previous)
            .param("seq", stored)
            .update()
        notePresence(principal.userId)
        return ReadCursor(stored)
    }

    fun notePresence(userId: UUID) {
        jdbc.sql("UPDATE users SET last_seen_at = now() WHERE id = :id").param("id", userId).update()
    }

    fun username(userId: UUID): String = usernameOf(userId)

    fun requireDirectPeer(principal: MessengerPrincipal, conversationId: UUID): UUID {
        requireMember(principal, conversationId)
        return jdbc.sql(
            """
            SELECT other.user_id
            FROM conversations c
            JOIN conversation_members other
              ON other.conversation_id = c.id
             AND other.user_id <> :userId
             AND other.left_at IS NULL
            WHERE c.id = :id AND c.type = 'direct'
            """.trimIndent(),
        ).param("id", conversationId).param("userId", principal.userId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "not_a_direct", "Calls are only available in a direct chat.")
    }

    fun directPeerIds(userId: UUID): List<UUID> =
        jdbc.sql(
            """
            SELECT other.user_id
            FROM conversation_members me
            JOIN conversations c ON c.id = me.conversation_id AND c.type = 'direct'
            JOIN conversation_members other
              ON other.conversation_id = c.id
             AND other.user_id <> me.user_id
             AND other.left_at IS NULL
            WHERE me.user_id = :userId AND me.left_at IS NULL
            """.trimIndent(),
        ).param("userId", userId).query(UUID::class.java).list().filterNotNull()

    @Transactional
    fun deleteMessage(principal: MessengerPrincipal, conversationId: UUID, seq: Long): DeletedMessage {
        requireMember(principal, conversationId)
        val row = jdbc.sql(
            """
            SELECT client_message_id, sender_user_id, deleted_at
            FROM messages
            WHERE conversation_id = :conversationId AND seq = :seq
            """.trimIndent(),
        ).param("conversationId", conversationId).param("seq", seq)
            .query { rs, _ ->
                Triple(
                    rs.getObject("client_message_id", UUID::class.java),
                    rs.getObject("sender_user_id", UUID::class.java),
                    rs.getObject("deleted_at"),
                )
            }
            .optional()
            .orElse(null)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "message_not_found", "Message was not found.")
        if (row.second != principal.userId) {
            throw ApiException(HttpStatus.FORBIDDEN, "not_message_author", "Only the author can delete a message.")
        }
        if (row.third == null) {
            jdbc.sql(
                """
                UPDATE messages SET deleted_at = now()
                WHERE conversation_id = :conversationId AND seq = :seq AND deleted_at IS NULL
                """.trimIndent(),
            ).param("conversationId", conversationId).param("seq", seq).update()
        }
        return DeletedMessage(conversationId, seq, row.first)
    }

    @Transactional
    fun editMessage(
        principal: MessengerPrincipal,
        conversationId: UUID,
        seq: Long,
        text: String,
        ciphertext: String,
    ): MessageView {
        requireMember(principal, conversationId)
        val row = jdbc.sql(
            """
            SELECT sender_user_id, kind, deleted_at, encryption
            FROM messages
            WHERE conversation_id = :conversationId AND seq = :seq
            """.trimIndent(),
        ).param("conversationId", conversationId).param("seq", seq)
            .query { rs, _ ->
                listOf(
                    rs.getObject("sender_user_id", UUID::class.java).toString(),
                    rs.getString("kind"),
                    rs.getObject("deleted_at")?.toString().orEmpty(),
                    rs.getString("encryption"),
                )
            }
            .optional()
            .orElse(null)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "message_not_found", "Message was not found.")
        if (UUID.fromString(row[0]) != principal.userId) {
            throw ApiException(HttpStatus.FORBIDDEN, "not_message_author", "Only the author can edit a message.")
        }
        if (row[2].isNotEmpty()) {
            throw ApiException(HttpStatus.NOT_FOUND, "message_not_found", "Message was not found.")
        }
        if (row[1] != "text") {
            throw ApiException(HttpStatus.BAD_REQUEST, "not_editable", "Only a text message can be edited.")
        }
        val bytes = if (row[3] == "x25519_v1") {
            if (text.isNotBlank()) {
                throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Encrypted messages do not include plaintext.")
            }
            decodeCiphertext(ciphertext)
        } else {
            val clean = text.trim()
            if (clean.isEmpty() || clean.length > 4000) {
                throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Message text is invalid.")
            }
            clean.toByteArray(Charsets.UTF_8)
        }
        jdbc.sql(
            """
            UPDATE messages
            SET ciphertext = :ciphertext, edited_at = now()
            WHERE conversation_id = :conversationId AND seq = :seq AND deleted_at IS NULL
            """.trimIndent(),
        ).param("ciphertext", bytes)
            .param("conversationId", conversationId)
            .param("seq", seq)
            .update()
        jdbc.sql("UPDATE conversations SET updated_at = now() WHERE id = :id")
            .param("id", conversationId)
            .update()
        return visibleMessage(conversationId, seq)
    }

    private fun decodeCiphertext(value: String): ByteArray {
        val bytes = try {
            java.util.Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Ciphertext is invalid.")
        }
        if (bytes.size < 61 || bytes.size > 12_000 || bytes[0] != 1.toByte()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Ciphertext is invalid.")
        }
        return bytes
    }

    private fun replyTarget(conversationId: UUID, seq: Long): ReplyTarget {
        if (seq <= 0) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Reply target is invalid.")
        }
        return jdbc.sql(
            """
            SELECT m.id, u.username, m.ciphertext, m.encryption
            FROM messages m
            JOIN users u ON u.id = m.sender_user_id
            WHERE m.conversation_id = :conversationId AND m.seq = :seq AND m.deleted_at IS NULL
            """.trimIndent(),
        ).param("conversationId", conversationId).param("seq", seq)
            .query { rs, _ ->
                val readable = if (rs.getString("encryption") == "none") {
                    rs.getBytes("ciphertext")?.toString(Charsets.UTF_8)?.take(80)
                } else {
                    null
                }
                ReplyTarget(rs.getObject("id", UUID::class.java), seq, rs.getString("username"), readable)
            }
            .optional()
            .orElse(null)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "reply_not_found", "The original message was not found.")
    }

    private fun visibleMessage(conversationId: UUID, seq: Long): MessageView =
        jdbc.sql(
            """
            SELECT m.seq, m.id, m.conversation_id, m.sender_user_id, u.username,
                   m.client_message_id, m.ciphertext, m.encryption, m.created_at, m.edited_at,
                   m.kind, a.id AS attachment_id, a.original_name, a.mime_type,
                   reply.seq AS reply_seq, reply_user.username AS reply_username,
                   reply.ciphertext AS reply_ciphertext, reply.encryption AS reply_encryption
            FROM messages m
            JOIN users u ON u.id = m.sender_user_id
            LEFT JOIN attachments a ON a.message_id = m.id
            LEFT JOIN messages reply ON reply.id = m.reply_to_id AND reply.deleted_at IS NULL
            LEFT JOIN users reply_user ON reply_user.id = reply.sender_user_id
            WHERE m.conversation_id = :conversationId AND m.seq = :seq AND m.deleted_at IS NULL
            """.trimIndent(),
        ).param("conversationId", conversationId).param("seq", seq)
            .query { rs, _ -> rs.message() }
            .single()

    fun send(principal: MessengerPrincipal, conversationId: UUID, request: SendMessageRequest): MessageView {
        requireMember(principal, conversationId)
        rejectBlockedDirect(principal, conversationId)
        val attachment = request.attachmentId?.let { attachments.requireSendable(principal, conversationId, it) }
        val encrypted = (request.encryption ?: "none") == "x25519_v1"
        val text = request.text.orEmpty().trim()
        val bytes = if (encrypted) {
            if (attachment != null || text.isNotEmpty()) {
                throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Encrypted messages do not include plaintext.")
            }
            decodeCiphertext(request.ciphertext.orEmpty())
        } else if (request.encryption != null && request.encryption != "none") {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Encryption is not supported.")
        } else {
            if ((text.isEmpty() && attachment == null) || text.length > 4000) {
                throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Message text is invalid.")
            }
            text.ifEmpty { attachment!!.originalName }.toByteArray(Charsets.UTF_8)
        }
        val reply = request.replyToSeq?.let { replyTarget(conversationId, it) }
        return try {
            write.execute<MessageView> {
            val created = jdbc.sql(
                """
                INSERT INTO messages (
                    id, conversation_id, sender_user_id, sender_device_id, client_message_id,
                    reply_to_id, kind, encryption, ciphertext
                ) VALUES (
                    :id, :conversationId, :senderUserId, :senderDeviceId, :clientMessageId,
                    CASE WHEN :hasReply THEN :replyToId ELSE NULL END, :kind, :encryption, :ciphertext
                )
                RETURNING seq, id, created_at
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
                .param("conversationId", conversationId)
                .param("senderUserId", principal.userId)
                .param("senderDeviceId", principal.deviceId)
                .param("clientMessageId", request.clientMessageId)
                .param("hasReply", reply != null)
                .param("replyToId", reply?.id ?: UUID(0, 0))
                .param("kind", attachment?.kind ?: "text")
                .param("encryption", if (encrypted) "x25519_v1" else "none")
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
                text = if (encrypted) "" else bytes.toString(Charsets.UTF_8),
                encryption = if (encrypted) "x25519_v1" else "none",
                createdAt = created.third,
                kind = attachment?.kind ?: "text",
                attachmentId = attachment?.id,
                attachmentName = attachment?.originalName,
                mimeType = attachment?.mimeType,
                replyToSeq = reply?.seq,
                replyToUsername = reply?.username,
                replyToText = reply?.text,
                ciphertext = if (encrypted) java.util.Base64.getEncoder().encodeToString(bytes) else null,
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
                   m.client_message_id, m.ciphertext, m.encryption, m.created_at, m.edited_at,
                   m.kind, a.id AS attachment_id, a.original_name, a.mime_type,
                   reply.seq AS reply_seq, reply_user.username AS reply_username,
                   reply.ciphertext AS reply_ciphertext, reply.encryption AS reply_encryption
            FROM messages m
            JOIN users u ON u.id = m.sender_user_id
            LEFT JOIN attachments a ON a.message_id = m.id
            LEFT JOIN messages reply ON reply.id = m.reply_to_id AND reply.deleted_at IS NULL
            LEFT JOIN users reply_user ON reply_user.id = reply.sender_user_id
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
            unreadCount = getInt("unread_count"),
            peerLastReadSeq = if (getObject("peer_last_read_seq") == null) 0 else getLong("peer_last_read_seq"),
            peerLastSeenAt = getObject("peer_last_seen_at")?.let { time("peer_last_seen_at") },
            peerOnline = getObject("peer_user_id", UUID::class.java)?.let { live.online(it) } ?: false,
            peerIdentityKey = getString("peer_identity_key").orEmpty(),
            lastEncrypted = getBoolean("last_encrypted"),
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
            replyToSeq = getObject("reply_seq")?.let { getLong("reply_seq") },
            replyToUsername = getString("reply_username"),
            replyToText = if (getString("reply_encryption") == "none") {
                getBytes("reply_ciphertext")?.toString(Charsets.UTF_8)?.take(80)
            } else {
                null
            },
            edited = getObject("edited_at") != null,
            ciphertext = if (encryption == "none") null else java.util.Base64.getEncoder().encodeToString(getBytes("ciphertext")),
        )
    }

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    private fun ResultSet.time(column: String): OffsetDateTime = getObject(column, OffsetDateTime::class.java)

    private data class StoredPeer(val id: UUID, val username: String, val displayName: String)

    private data class ReplyTarget(val id: UUID, val seq: Long, val username: String, val text: String?)
}

internal fun directKey(first: UUID, second: UUID): String {
    val left = first.toString()
    val right = second.toString()
    return if (left < right) "$left:$right" else "$right:$left"
}
