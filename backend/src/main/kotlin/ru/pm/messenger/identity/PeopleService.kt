package ru.pm.messenger.identity

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Service
class PeopleService(
    private val jdbc: JdbcClient,
) {
    @Transactional
    fun block(principal: MessengerPrincipal, username: String) {
        val peer = findActive(normalize(username))
            ?: throw ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found.")
        if (peer.first == principal.userId) {
            throw ApiException(HttpStatus.BAD_REQUEST, "cannot_block_self", "Choose another person.")
        }
        jdbc.sql(
            """
            INSERT INTO blocks (blocker_user_id, blocked_user_id)
            VALUES (:blocker, :blocked)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).param("blocker", principal.userId).param("blocked", peer.first).update()
    }

    @Transactional
    fun unblock(principal: MessengerPrincipal, username: String) {
        val peer = findActive(normalize(username))
            ?: throw ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found.")
        jdbc.sql(
            """
            DELETE FROM blocks
            WHERE blocker_user_id = :blocker AND blocked_user_id = :blocked
            """.trimIndent(),
        ).param("blocker", principal.userId).param("blocked", peer.first).update()
    }

    fun list(principal: MessengerPrincipal): List<BlockView> =
        jdbc.sql(
            """
            SELECT u.username, u.display_name
            FROM blocks b
            JOIN users u ON u.id = b.blocked_user_id
            WHERE b.blocker_user_id = :userId AND u.deleted_at IS NULL
            ORDER BY u.username
            """.trimIndent(),
        ).param("userId", principal.userId)
            .query { rs, _ -> BlockView(rs.getString("username"), rs.getString("display_name")) }
            .list()

    private fun findActive(username: String) =
        jdbc.sql(
            """
            SELECT id FROM users
            WHERE username = :username AND deleted_at IS NULL AND status = 'active'
            """.trimIndent(),
        ).param("username", username)
            .query { rs, _ -> rs.getObject("id", java.util.UUID::class.java) to username }
            .optional()
            .orElse(null)

    private fun normalize(value: String): String {
        val username = value.trim().lowercase()
        if (!username.matches(Regex("^[a-z0-9_]{3,32}$"))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Username is invalid.")
        }
        return username
    }
}

@RestController
@RequestMapping("/api/v1/blocks")
class BlockController(private val people: PeopleService) {
    @GetMapping
    fun list(): List<BlockView> = people.list(currentPrincipal())

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun block(@RequestBody body: BlockRequest) = people.block(currentPrincipal(), body.username)

    @DeleteMapping("/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unblock(@PathVariable username: String) = people.unblock(currentPrincipal(), username)
}
