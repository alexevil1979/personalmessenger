package ru.pm.messenger.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.pm.messenger.identity.ApiException
import ru.pm.messenger.identity.MessengerPrincipal
import ru.pm.messenger.identity.TokenCodec
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

data class AttachmentView(
    val id: UUID,
    val mimeType: String,
    val kind: String,
    val originalName: String,
    val sizeBytes: Long,
)

data class StoredAttachment(
    val id: UUID,
    val originalName: String,
    val mimeType: String,
    val kind: String,
)

data class IssuedLink(
    val ticket: String,
    val expiresAt: OffsetDateTime,
)

data class OpenedFile(
    val bytes: ByteArray,
    val mimeType: String,
    val originalName: String,
)

@Service
class AttachmentService(
    private val jdbc: JdbcClient,
    private val objects: ObjectStore,
    private val redis: StringRedisTemplate,
    @Value("\${app.storage.max-bytes}") private val maxBytes: Int,
    @Value("\${app.storage.link-ttl}") private val linkTtl: Duration,
) {
    fun upload(principal: MessengerPrincipal, conversationId: UUID, fileName: String?, body: InputStream): AttachmentView {
        requireMember(principal, conversationId)
        val bytes = readLimited(body)
        if (bytes.isEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "File is empty.")
        }
        val detected = sniff(bytes)
            ?: throw ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media", "This file type is not allowed.")
        val name = safeFileName(fileName)
        val id = UUID.randomUUID()
        val key = "c/$conversationId/$id"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        jdbc.sql(
            """
            INSERT INTO attachments (
                id, uploader_user_id, conversation_id, object_key, original_name,
                mime_type, size_bytes, sha256, client_encrypted, status, completed_at
            ) VALUES (
                :id, :userId, :conversationId, :objectKey, :originalName,
                :mimeType, :sizeBytes, :sha256, false, 'pending', null
            )
            """.trimIndent(),
        ).param("id", id)
            .param("userId", principal.userId)
            .param("conversationId", conversationId)
            .param("objectKey", key)
            .param("originalName", name)
            .param("mimeType", detected.mimeType)
            .param("sizeBytes", bytes.size.toLong())
            .param("sha256", digest)
            .update()
        try {
            objects.put(key, bytes, detected.mimeType)
        } catch (error: RuntimeException) {
            jdbc.sql("UPDATE attachments SET status = 'failed' WHERE id = :id").param("id", id).update()
            throw error
        }
        jdbc.sql("UPDATE attachments SET status = 'ready', completed_at = now() WHERE id = :id")
            .param("id", id)
            .update()
        return AttachmentView(id, detected.mimeType, detected.kind, name, bytes.size.toLong())
    }

    fun requireSendable(principal: MessengerPrincipal, conversationId: UUID, attachmentId: UUID): StoredAttachment {
        val row = jdbc.sql(
            """
            SELECT original_name, mime_type, status, message_id
            FROM attachments
            WHERE id = :id AND conversation_id = :conversationId AND uploader_user_id = :userId
            """.trimIndent(),
        ).param("id", attachmentId)
            .param("conversationId", conversationId)
            .param("userId", principal.userId)
            .query { rs, _ ->
                listOf(rs.getString("original_name"), rs.getString("mime_type"), rs.getString("status"), rs.getString("message_id"))
            }
            .optional()
            .orElse(null)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "attachment_not_found", "Attachment was not found.")
        if (row[3] != null) {
            throw ApiException(HttpStatus.CONFLICT, "attachment_used", "Attachment is already used.")
        }
        if (row[2] != "ready") {
            throw ApiException(HttpStatus.CONFLICT, "attachment_not_ready", "Attachment is not ready.")
        }
        val kind = when (row[1]) {
            "audio/ogg", "audio/mpeg", "audio/mp4", "audio/amr", "audio/amr-wb" -> "voice"
            else -> "attachment"
        }
        return StoredAttachment(attachmentId, row[0], row[1], kind)
    }

    @Transactional
    fun bind(principal: MessengerPrincipal, conversationId: UUID, attachmentId: UUID, messageId: UUID) {
        val updated = jdbc.sql(
            """
            UPDATE attachments
            SET message_id = :messageId
            WHERE id = :id
              AND conversation_id = :conversationId
              AND uploader_user_id = :userId
              AND message_id IS NULL
              AND status = 'ready'
            """.trimIndent(),
        ).param("messageId", messageId)
            .param("id", attachmentId)
            .param("conversationId", conversationId)
            .param("userId", principal.userId)
            .update()
        if (updated != 1) {
            throw ApiException(HttpStatus.CONFLICT, "attachment_used", "Attachment is already used.")
        }
    }

    fun issueLink(principal: MessengerPrincipal, attachmentId: UUID): IssuedLink {
        val visible = jdbc.sql(
            """
            SELECT a.id
            FROM attachments a
            JOIN conversation_members me
              ON me.conversation_id = a.conversation_id
             AND me.user_id = :userId
             AND me.left_at IS NULL
            WHERE a.id = :id AND a.status = 'ready'
            """.trimIndent(),
        ).param("userId", principal.userId)
            .param("id", attachmentId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "attachment_not_found", "Attachment was not found.")
        val ticket = TokenCodec.randomToken()
        redis.opsForValue().set(ticketKey(ticket), visible.toString(), linkTtl)
        return IssuedLink(ticket, OffsetDateTime.now().plus(linkTtl))
    }

    fun open(attachmentId: UUID, ticket: String?): OpenedFile {
        if (ticket.isNullOrBlank()) {
            throw ApiException(HttpStatus.NOT_FOUND, "attachment_not_found", "Attachment was not found.")
        }
        val stored = redis.opsForValue().get(ticketKey(ticket))
        if (stored != attachmentId.toString()) {
            throw ApiException(HttpStatus.NOT_FOUND, "attachment_not_found", "Attachment was not found.")
        }
        val row = jdbc.sql(
            """
            SELECT object_key, mime_type, original_name
            FROM attachments
            WHERE id = :id AND status = 'ready'
            """.trimIndent(),
        ).param("id", attachmentId)
            .query { rs, _ -> Triple(rs.getString("object_key"), rs.getString("mime_type"), rs.getString("original_name")) }
            .optional()
            .orElse(null)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "attachment_not_found", "Attachment was not found.")
        return OpenedFile(objects.get(row.first), row.second, row.third)
    }

    private fun readLimited(body: InputStream): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = body.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "File is too large.")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
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

    private fun ticketKey(ticket: String) = "attachment-ticket:${TokenCodec.sha256(ticket)}"
}
