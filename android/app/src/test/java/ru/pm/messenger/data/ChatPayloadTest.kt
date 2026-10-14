package ru.pm.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPayloadTest {
    @Test
    fun parsesSocketMessage() {
        val message = parseSocketMessage(
            """
            {"type":"message","message":{"seq":4,"conversationId":"chat","senderUsername":"bob","clientMessageId":"client","text":"привет","createdAt":"2026-10-14T12:00:00Z"}}
            """.trimIndent(),
        )
        assertEquals(4L, message?.seq)
        assertEquals("привет", message?.text)
        assertEquals("bob", message?.senderUsername)
        assertEquals("text", message?.kind)
        assertEquals("", message?.attachmentId)
    }

    @Test
    fun parsesAttachmentFields() {
        val message = parseSocketMessage(
            """
            {"type":"message","message":{"seq":5,"conversationId":"chat","senderUsername":"bob","clientMessageId":"client","text":"photo.png","createdAt":"2026-10-14T12:00:00Z","kind":"attachment","attachmentId":"file-1","attachmentName":"photo.png","mimeType":"image/png"}}
            """.trimIndent(),
        )
        assertEquals("attachment", message?.kind)
        assertEquals("file-1", message?.attachmentId)
        assertEquals("photo.png", message?.attachmentName)
    }

    @Test
    fun ignoresOtherEvents() {
        assertNull(parseSocketMessage("""{"type":"presence"}"""))
    }
}
