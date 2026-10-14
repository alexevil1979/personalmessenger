package ru.pm.messenger.identity

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AdminBootstrap(
    private val jdbc: JdbcClient,
    private val passwords: PasswordEncoder,
    @param:Value("\${app.bootstrap.admin-username:}") private val username: String,
    @param:Value("\${app.bootstrap.admin-password:}") private val password: String,
    @param:Value("\${app.bootstrap.admin-display-name:Administrator}") private val displayName: String,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun ensureOwner() {
        if (username.isBlank() || password.isBlank()) return
        val normalized = username.trim().lowercase()
        if (!normalized.matches(Regex("^[a-z0-9_]{3,32}$")) || password.length < 12) {
            log.error("Bootstrap admin was not created because the username or password does not meet policy")
            return
        }
        val owners = jdbc.sql("SELECT count(*) FROM admin_roles WHERE role = 'owner'")
            .query(Long::class.java)
            .single()
        if (owners > 0) {
            log.info("Owner account already exists, bootstrap skipped")
            return
        }
        val existing = jdbc.sql("SELECT count(*) FROM users WHERE username = :username")
            .param("username", normalized)
            .query(Long::class.java)
            .single()
        if (existing > 0) {
            log.error("Bootstrap admin username is already registered without an owner role, bootstrap skipped")
            return
        }
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO users (id, username, display_name, password_hash, privacy_policy_accepted_at)
            VALUES (:id, :username, :displayName, :passwordHash, now())
            """.trimIndent(),
        ).param("id", id)
            .param("username", normalized)
            .param("displayName", displayName.trim().ifEmpty { "Administrator" })
            .param("passwordHash", passwords.encode(password))
            .update()
        jdbc.sql(
            "INSERT INTO admin_roles (user_id, role) VALUES (:id, 'owner')",
        ).param("id", id).update()
        jdbc.sql(
            """
            INSERT INTO audit_events (actor_user_id, action, target_type, target_id)
            VALUES (:id, 'admin.bootstrapped', 'user', :target)
            """.trimIndent(),
        ).param("id", id).param("target", id.toString()).update()
        log.info("Bootstrapped owner account username={}", normalized)
    }

    private companion object {
        val log = LoggerFactory.getLogger(AdminBootstrap::class.java)
    }
}
