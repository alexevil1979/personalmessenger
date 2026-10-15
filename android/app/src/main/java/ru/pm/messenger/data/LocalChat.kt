package ru.pm.messenger.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val myRole: String,
    val peerUsername: String,
    val peerDisplayName: String,
    val preview: String,
    val updatedAt: String,
    val unreadCount: Int,
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val clientMessageId: String,
    val seq: Long?,
    val conversationId: String,
    val senderUsername: String,
    val text: String,
    val createdAt: String,
    val pending: Boolean,
    val kind: String,
    val attachmentId: String,
    val attachmentName: String,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun conversations(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveConversations(items: List<ConversationEntity>)

    @Query("DELETE FROM conversations")
    fun clearConversations()

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY seq IS NULL, seq ASC")
    fun messages(conversationId: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE conversationId = :conversationId")
    fun maxSeq(conversationId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND seq = :seq")
    fun deleteBySeq(conversationId: String, seq: Long)

    @Query("DELETE FROM messages")
    fun clearMessages()
}
