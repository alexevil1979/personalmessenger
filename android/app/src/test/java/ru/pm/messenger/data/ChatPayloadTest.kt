package ru.pm.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPayloadTest {
    @Test
    fun parsesSocketMessage() {
        val message = incoming(
            """
            {"type":"message","message":{"seq":4,"conversationId":"chat","senderUsername":"bob","clientMessageId":"client","text":"привет","createdAt":"2026-10-14T12:00:00Z"}}
            """.trimIndent(),
        )
        assertEquals(4L, message?.seq)
        assertEquals("привет", message?.text)
        assertEquals("bob", message?.senderUsername)
        assertEquals("text", message?.kind)
        assertEquals("", message?.attachmentId)
        assertEquals(0L, message?.replyToSeq)
        assertEquals(false, message?.edited)
    }

    @Test
    fun parsesReplyAndEdit() {
        val message = incoming(
            """
            {"type":"message","message":{"seq":6,"conversationId":"chat","senderUsername":"bob","clientMessageId":"client","text":"ответ","createdAt":"2026-10-14T12:00:00Z","replyToSeq":4,"replyToUsername":"alice","replyToText":"основа","edited":true}}
            """.trimIndent(),
        )
        assertEquals(4L, message?.replyToSeq)
        assertEquals("alice", message?.replyToUsername)
        assertEquals("основа", message?.replyToText)
        assertEquals(true, message?.edited)
    }

    @Test
    fun parsesAttachmentFields() {
        val message = incoming(
            """
            {"type":"message","message":{"seq":5,"conversationId":"chat","senderUsername":"bob","clientMessageId":"client","text":"photo.png","createdAt":"2026-10-14T12:00:00Z","kind":"attachment","attachmentId":"file-1","attachmentName":"photo.png","mimeType":"image/png"}}
            """.trimIndent(),
        )
        assertEquals("attachment", message?.kind)
        assertEquals("file-1", message?.attachmentId)
        assertEquals("photo.png", message?.attachmentName)
    }

    @Test
    fun parsesDeletion() {
        val deleted = parseSocketEvent("""{"type":"deleted","conversationId":"chat","seq":4,"clientMessageId":"client"}""")
        assertTrue(deleted is SocketEvent.Deleted)
        assertEquals(4L, (deleted as SocketEvent.Deleted).seq)
    }

    @Test
    fun ignoresOtherEvents() {
        assertNull(parseSocketEvent("""{"type":"typing"}"""))
    }

    @Test
    fun parsesReadAndPresence() {
        val read = parseSocketEvent("""{"type":"read","conversationId":"chat","username":"bob","lastReadSeq":4}""")
        assertTrue(read is SocketEvent.Read)
        assertEquals(4L, (read as SocketEvent.Read).lastReadSeq)
        val presence = parseSocketEvent("""{"type":"presence","username":"bob","online":true}""")
        assertTrue(presence is SocketEvent.Presence)
        assertEquals(true, (presence as SocketEvent.Presence).online)
    }

    private fun incoming(body: String) = (parseSocketEvent(body) as? SocketEvent.Incoming)?.message
}
