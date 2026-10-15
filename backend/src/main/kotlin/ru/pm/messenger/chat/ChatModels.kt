package ru.pm.messenger.chat

import java.time.OffsetDateTime
import java.util.UUID

data class OpenDirectRequest(val username: String)

data class CreateGroupRequest(val title: String)

data class AddMemberRequest(val username: String)

data class SendMessageRequest(
    val clientMessageId: UUID,
    val text: String = "",
    val attachmentId: UUID? = null,
)

data class ConversationView(
    val id: UUID,
    val type: String,
    val myRole: String,
    val peerUsername: String,
    val peerDisplayName: String,
    val lastSeq: Long?,
    val preview: String?,
    val updatedAt: OffsetDateTime,
    val unreadCount: Int = 0,
)

data class DeletedMessage(
    val conversationId: UUID,
    val seq: Long,
    val clientMessageId: UUID,
)

data class MessageView(
    val seq: Long,
    val id: UUID,
    val conversationId: UUID,
    val senderUserId: UUID,
    val senderUsername: String,
    val clientMessageId: UUID,
    val text: String,
    val encryption: String,
    val createdAt: OffsetDateTime,
    val kind: String,
    val attachmentId: UUID?,
    val attachmentName: String?,
    val mimeType: String?,
)
