package ru.pm.messenger.chat

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.pm.messenger.identity.ReadCursor
import ru.pm.messenger.identity.currentPrincipal
import java.util.UUID

@RestController
@RequestMapping("/api/v1/conversations")
class ChatController(
    private val chat: ChatService,
    private val live: LiveSessions,
) {
    @PostMapping("/direct")
    fun open(@RequestBody body: OpenDirectRequest): ConversationView =
        chat.openDirect(currentPrincipal(), body)

    @PostMapping("/group")
    fun createGroup(@RequestBody body: CreateGroupRequest): ConversationView =
        chat.createGroup(currentPrincipal(), body)

    @PostMapping("/{id}/members")
    fun addMember(@PathVariable id: UUID, @RequestBody body: AddMemberRequest): ConversationView =
        chat.addMember(currentPrincipal(), id, body)

    @DeleteMapping("/{id}/members/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(@PathVariable id: UUID, @PathVariable username: String) =
        chat.removeMember(currentPrincipal(), id, username)

    @PostMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leave(@PathVariable id: UUID) = chat.leave(currentPrincipal(), id)

    @PostMapping("/{id}/read")
    fun read(@PathVariable id: UUID, @RequestBody body: ReadCursor): ReadCursor {
        val principal = currentPrincipal()
        val cursor = chat.markRead(principal, id, body.lastReadSeq)
        live.publishRaw(readEvent(id, chat.username(principal.userId), cursor.lastReadSeq), chat.memberIds(id))
        return cursor
    }

    @GetMapping
    fun list(): List<ConversationView> = chat.list(currentPrincipal())

    @GetMapping("/{id}/messages")
    fun messages(
        @PathVariable id: UUID,
        @RequestParam(name = "afterSeq", defaultValue = "0") afterSeq: Long,
    ): List<MessageView> = chat.messages(currentPrincipal(), id, afterSeq)

    @DeleteMapping("/{id}/messages/{seq}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMessage(@PathVariable id: UUID, @PathVariable seq: Long) {
        val deleted = chat.deleteMessage(currentPrincipal(), id, seq)
        live.publishRaw(deletedEvent(deleted), chat.memberIds(id))
    }

    @PostMapping("/{id}/messages/{seq}/edit")
    fun edit(
        @PathVariable id: UUID,
        @PathVariable seq: Long,
        @RequestBody body: EditMessageRequest,
    ): MessageView {
        val message = chat.editMessage(currentPrincipal(), id, seq, body.text.orEmpty(), body.ciphertext.orEmpty())
        live.publish(message, chat.memberIds(id))
        return message
    }

    @PostMapping("/{id}/messages")
    fun send(@PathVariable id: UUID, @RequestBody body: SendMessageRequest): MessageView {
        val message = chat.send(currentPrincipal(), id, body)
        live.publish(message, chat.memberIds(id))
        return message
    }
}
