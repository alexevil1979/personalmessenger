package ru.pm.messenger.identity

import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class AuthService(
    private val jdbc: JdbcClient,
    private val passwords: PasswordEncoder,
    private val properties: AuthProperties,
    private val attempts: AttemptLimiter,
    private val transactionManager: PlatformTransactionManager,
) {
    @Transactional
    fun register(request: RegisterRequest, meta: ClientMeta): AuthResult {
        val username = normalizeUsername(request.username)
        val displayName = request.displayName.trim()
        val platform = normalizePlatform(request.platform)
        val deviceName = request.deviceName.trim()
        if (!request.privacyPolicyAccepted) {
            throw ApiException(HttpStatus.BAD_REQUEST, "privacy_required", "Privacy policy must be accepted.")
        }
        if (displayName.isEmpty() || displayName.length > 80 || deviceName.isEmpty() || deviceName.length > 80) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Name fields are invalid.")
        }
        validatePassword(username, request.password)
        val ipKey = "auth:register:${meta.ip ?: "unknown"}"
        if (attempts.blocked(ipKey, 10)) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many attempts.")
        }
        attempts.record(ipKey, properties.loginWindow)
        val updated = jdbc.sql(
            """
            UPDATE invitations
            SET use_count = use_count + 1
            WHERE code_hash = :hash
              AND revoked_at IS NULL
              AND expires_at > now()
              AND use_count < max_uses
            """.trimIndent(),
        ).param("hash", TokenCodec.sha256(request.invitationCode.trim())).update()
        if (updated == 0) {
            throw ApiException(HttpStatus.BAD_REQUEST, "invitation_invalid", "Invitation is invalid.")
        }
        val userId = UUID.randomUUID()
        try {
            jdbc.sql(
                """
                INSERT INTO users (id, username, display_name, password_hash, privacy_policy_accepted_at)
                VALUES (:id, :username, :displayName, :passwordHash, now())
                """.trimIndent(),
            ).param("id", userId)
                .param("username", username)
                .param("displayName", displayName)
                .param("passwordHash", passwords.encode(request.password))
                .update()
        } catch (_: DuplicateKeyException) {
            throw ApiException(HttpStatus.CONFLICT, "username_taken", "Username is already taken.")
        }
        val invitationId = jdbc.sql("SELECT id FROM invitations WHERE code_hash = :hash")
            .param("hash", TokenCodec.sha256(request.invitationCode.trim()))
            .query(UUID::class.java)
            .single()
        jdbc.sql(
            """
            INSERT INTO invitation_uses (invitation_id, user_id) VALUES (:invitationId, :userId)
            """.trimIndent(),
        ).param("invitationId", invitationId).param("userId", userId).update()
        val deviceId = insertDevice(userId, deviceName, platform)
        audit(userId, "user.registered", "user", userId.toString(), meta.ip)
        return issue(userId, username, displayName, null, deviceId, meta)
    }

    @Transactional
    fun login(request: LoginRequest, meta: ClientMeta): AuthResult {
        val username = normalizeUsername(request.username)
        val platform = normalizePlatform(request.platform)
        val deviceName = request.deviceName.trim()
        if (deviceName.isEmpty() || deviceName.length > 80) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Device name is invalid.")
        }
        val key = "auth:login:${meta.ip ?: "unknown"}"
        if (attempts.blocked(key, properties.loginFailures)) {
            audit(null, "auth.rate_limited", "user", username, meta.ip)
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many attempts.")
        }
        val user = findUser(username)
        if (user == null || !passwords.matches(request.password, user.passwordHash)) {
            attempts.record(key, properties.loginWindow)
            audit(null, "auth.login_failed", "user", username, meta.ip)
            throw ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Username or password is wrong.")
        }
        if (user.status != "active") {
            throw ApiException(HttpStatus.FORBIDDEN, "account_blocked", "Account is blocked.")
        }
        attempts.clear(key)
        val deviceId = insertDevice(user.id, deviceName, platform)
        audit(user.id, "auth.login", "session", null, meta.ip)
        return issue(user.id, user.username, user.displayName, user.role, deviceId, meta)
    }

    @Transactional
    fun refresh(refreshToken: String, meta: ClientMeta): AuthResult {
        val hash = TokenCodec.sha256(refreshToken.trim())
        val session = findSessionByRefresh(hash)
        if (session == null) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh", "Refresh token is invalid.")
        }
        if (session.revokedAt != null) {
            val committed = TransactionTemplate(transactionManager)
            committed.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            committed.executeWithoutResult {
                revokeAll(session.userId)
                audit(session.userId, "auth.refresh_reuse", "session", session.id.toString(), meta.ip)
            }
            throw ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh", "Refresh token is invalid.")
        }
        if (session.expiresAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh", "Refresh token is invalid.")
        }
        val user = findUserById(session.userId)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh", "Refresh token is invalid.")
        if (user.status != "active") {
            throw ApiException(HttpStatus.FORBIDDEN, "account_blocked", "Account is blocked.")
        }
        val issued = issue(user.id, user.username, user.displayName, user.role, session.deviceId, meta)
        val revoked = jdbc.sql(
            """
            UPDATE sessions
            SET revoked_at = now(), replaced_by = :replacement
            WHERE refresh_token_hash = :hash AND revoked_at IS NULL
            """.trimIndent(),
        ).param("replacement", issued.sessionId)
            .param("hash", hash)
            .update()
        if (revoked != 1) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh", "Refresh token is invalid.")
        }
        return issued
    }

    fun logout(principal: MessengerPrincipal) {
        jdbc.sql("UPDATE sessions SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
            .param("id", principal.sessionId)
            .update()
        audit(principal.userId, "auth.logout", "session", principal.sessionId.toString(), null)
    }

    fun logoutAll(principal: MessengerPrincipal) {
        revokeAll(principal.userId)
        audit(principal.userId, "auth.logout_all", "user", principal.userId.toString(), null)
    }

    fun sessions(principal: MessengerPrincipal): List<SessionView> =
        jdbc.sql(
            """
            SELECT s.id, d.name, d.platform, s.created_at, s.expires_at
            FROM sessions s
            JOIN devices d ON d.id = s.device_id
            WHERE s.user_id = :userId AND s.revoked_at IS NULL
            ORDER BY s.created_at DESC
            """.trimIndent(),
        ).param("userId", principal.userId)
            .query { rs, _ ->
                SessionView(
                    id = rs.uuid("id"),
                    deviceName = rs.getString("name"),
                    platform = rs.getString("platform"),
                    createdAt = rs.time("created_at"),
                    expiresAt = rs.time("expires_at"),
                    current = rs.uuid("id") == principal.sessionId,
                )
            }.list()

    fun revokeSession(principal: MessengerPrincipal, sessionId: UUID) {
        val updated = jdbc.sql(
            """
            UPDATE sessions SET revoked_at = now()
            WHERE id = :id AND user_id = :userId AND revoked_at IS NULL
            """.trimIndent(),
        ).param("id", sessionId).param("userId", principal.userId).update()
        if (updated == 0) {
            throw ApiException(HttpStatus.NOT_FOUND, "session_not_found", "Session was not found.")
        }
        audit(principal.userId, "auth.session_revoked", "session", sessionId.toString(), null)
    }

    @Transactional
    fun changePassword(principal: MessengerPrincipal, request: ChangePasswordRequest) {
        val user = findUserById(principal.userId)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication is required.")
        if (!passwords.matches(request.currentPassword, user.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Username or password is wrong.")
        }
        validatePassword(user.username, request.newPassword)
        if (passwords.matches(request.newPassword, user.passwordHash)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Choose a different password.")
        }
        jdbc.sql("UPDATE users SET password_hash = :hash, updated_at = now() WHERE id = :id")
            .param("hash", passwords.encode(request.newPassword))
            .param("id", user.id)
            .update()
        jdbc.sql(
            """
            UPDATE sessions SET revoked_at = now()
            WHERE user_id = :userId AND id <> :current AND revoked_at IS NULL
            """.trimIndent(),
        ).param("userId", user.id).param("current", principal.sessionId).update()
        audit(user.id, "auth.password_changed", "user", user.id.toString(), null)
    }

    fun adminUsers(principal: MessengerPrincipal): List<AdminUserView> {
        requireAdmin(principal)
        return jdbc.sql(
            """
            SELECT u.id, u.username, u.display_name, u.status, ar.role, u.created_at
            FROM users u
            LEFT JOIN admin_roles ar ON ar.user_id = u.id
            WHERE u.deleted_at IS NULL
            ORDER BY u.created_at ASC
            LIMIT 200
            """.trimIndent(),
        ).query { rs, _ ->
            AdminUserView(
                id = rs.uuid("id"),
                username = rs.getString("username"),
                displayName = rs.getString("display_name"),
                status = rs.getString("status"),
                role = rs.getString("role"),
                createdAt = rs.time("created_at"),
            )
        }.list()
    }

    @Transactional
    fun setUserStatus(principal: MessengerPrincipal, username: String, status: String) {
        requireAdmin(principal)
        if (status != "active" && status != "blocked") {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Status is invalid.")
        }
        val target = findUser(normalizeUsername(username))
            ?: throw ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User was not found.")
        if (target.id == principal.userId) {
            throw ApiException(HttpStatus.BAD_REQUEST, "cannot_change_self", "Choose another person.")
        }
        if (target.role == "owner") {
            throw ApiException(HttpStatus.FORBIDDEN, "forbidden", "Access is denied.")
        }
        jdbc.sql("UPDATE users SET status = :status, updated_at = now() WHERE id = :id")
            .param("status", status)
            .param("id", target.id)
            .update()
        if (status == "blocked") {
            revokeAll(target.id)
        }
        audit(principal.userId, "admin.user_status", "user", target.id.toString(), null)
    }

    fun me(principal: MessengerPrincipal): UserView {
        val user = findUserById(principal.userId)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication is required.")
        return UserView(user.id, user.username, user.displayName, user.role)
    }

    fun authenticate(accessToken: String): MessengerPrincipal? {
        val hash = TokenCodec.sha256(accessToken.trim())
        return jdbc.sql(
            """
            SELECT s.id, s.user_id, s.device_id, ar.role
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            LEFT JOIN admin_roles ar ON ar.user_id = u.id
            WHERE s.access_token_hash = :hash
              AND s.revoked_at IS NULL
              AND s.access_expires_at > now()
              AND u.status = 'active'
              AND u.deleted_at IS NULL
            """.trimIndent(),
        ).param("hash", hash)
            .query { rs, _ ->
                MessengerPrincipal(
                    userId = rs.uuid("user_id"),
                    sessionId = rs.uuid("id"),
                    deviceId = rs.uuid("device_id"),
                    adminRole = rs.getString("role"),
                )
            }.optional().orElse(null)
    }

    @Transactional
    fun createInvitation(principal: MessengerPrincipal, request: CreateInvitationRequest): InvitationCreated {
        requireAdmin(principal)
        if (request.expiresInHours !in 1..24 * 30 || request.maxUses !in 1..100) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Invitation limits are invalid.")
        }
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > 200) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Note is too long.")
        }
        val code = TokenCodec.randomToken()
        val id = UUID.randomUUID()
        val expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(request.expiresInHours.toLong())
        jdbc.sql(
            """
            INSERT INTO invitations (id, code_hash, created_by, expires_at, max_uses, note)
            VALUES (:id, :hash, :createdBy, :expiresAt, :maxUses, :note)
            """.trimIndent(),
        ).param("id", id)
            .param("hash", TokenCodec.sha256(code))
            .param("createdBy", principal.userId)
            .param("expiresAt", expiresAt)
            .param("maxUses", request.maxUses)
            .param("note", note)
            .update()
        audit(principal.userId, "invitation.created", "invitation", id.toString(), null)
        return InvitationCreated(id, code, expiresAt, request.maxUses)
    }

    fun invitations(principal: MessengerPrincipal): List<InvitationView> {
        requireAdmin(principal)
        return jdbc.sql(
            """
            SELECT i.id, i.expires_at, i.max_uses, i.use_count, i.revoked_at, i.created_at, i.note, u.username
            FROM invitations i
            LEFT JOIN users u ON u.id = i.created_by
            ORDER BY i.created_at DESC
            LIMIT 200
            """.trimIndent(),
        ).query { rs, _ ->
            InvitationView(
                id = rs.uuid("id"),
                expiresAt = rs.time("expires_at"),
                maxUses = rs.getInt("max_uses"),
                useCount = rs.getInt("use_count"),
                revokedAt = rs.getObject("revoked_at", OffsetDateTime::class.java),
                createdAt = rs.time("created_at"),
                note = rs.getString("note"),
                createdBy = rs.getString("username"),
            )
        }.list()
    }

    fun revokeInvitation(principal: MessengerPrincipal, id: UUID) {
        requireAdmin(principal)
        val updated = jdbc.sql(
            "UPDATE invitations SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL",
        ).param("id", id).update()
        if (updated == 0 && !invitationExists(id)) {
            throw ApiException(HttpStatus.NOT_FOUND, "invitation_not_found", "Invitation was not found.")
        }
        audit(principal.userId, "invitation.revoked", "invitation", id.toString(), null)
    }

    private fun issue(
        userId: UUID,
        username: String,
        displayName: String,
        role: String?,
        deviceId: UUID,
        meta: ClientMeta,
    ): AuthResult {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val accessExpires = now.plus(properties.accessTtl)
        val refreshExpires = now.plus(properties.refreshTtl)
        val access = TokenCodec.randomToken()
        val refresh = TokenCodec.randomToken()
        val sessionId = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO sessions (
                id, user_id, device_id, refresh_token_hash, access_token_hash,
                expires_at, access_expires_at, ip, user_agent
            ) VALUES (
                :id, :userId, :deviceId, :refreshHash, :accessHash,
                :expiresAt, :accessExpiresAt, CAST(:ip AS inet), :userAgent
            )
            """.trimIndent(),
        ).param("id", sessionId)
            .param("userId", userId)
            .param("deviceId", deviceId)
            .param("refreshHash", TokenCodec.sha256(refresh))
            .param("accessHash", TokenCodec.sha256(access))
            .param("expiresAt", refreshExpires)
            .param("accessExpiresAt", accessExpires)
            .param("ip", meta.ip)
            .param("userAgent", meta.userAgent)
            .update()
        jdbc.sql("UPDATE devices SET last_seen_at = now() WHERE id = :id").param("id", deviceId).update()
        jdbc.sql("UPDATE users SET last_seen_at = now() WHERE id = :id").param("id", userId).update()
        return AuthResult(
            accessToken = access,
            refreshToken = refresh,
            accessExpiresAt = accessExpires,
            refreshExpiresAt = refreshExpires,
            user = UserView(userId, username, displayName, role),
            deviceId = deviceId,
            sessionId = sessionId,
        )
    }

    private fun insertDevice(userId: UUID, name: String, platform: String): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            "INSERT INTO devices (id, user_id, name, platform) VALUES (:id, :userId, :name, :platform)",
        ).param("id", id).param("userId", userId).param("name", name).param("platform", platform).update()
        return id
    }

    private fun findUser(username: String): StoredUser? =
        jdbc.sql(
            """
            SELECT u.id, u.username, u.display_name, u.password_hash, u.status, ar.role
            FROM users u
            LEFT JOIN admin_roles ar ON ar.user_id = u.id
            WHERE u.username = :username AND u.deleted_at IS NULL
            """.trimIndent(),
        ).param("username", username).query { rs, _ -> rs.user() }.optional().orElse(null)

    private fun findUserById(id: UUID): StoredUser? =
        jdbc.sql(
            """
            SELECT u.id, u.username, u.display_name, u.password_hash, u.status, ar.role
            FROM users u
            LEFT JOIN admin_roles ar ON ar.user_id = u.id
            WHERE u.id = :id AND u.deleted_at IS NULL
            """.trimIndent(),
        ).param("id", id).query { rs, _ -> rs.user() }.optional().orElse(null)

    private fun findSessionByRefresh(hash: String): StoredSession? =
        jdbc.sql(
            """
            SELECT id, user_id, device_id, expires_at, revoked_at
            FROM sessions WHERE refresh_token_hash = :hash
            """.trimIndent(),
        ).param("hash", hash)
            .query { rs, _ ->
                StoredSession(
                    id = rs.uuid("id"),
                    userId = rs.uuid("user_id"),
                    deviceId = rs.uuid("device_id"),
                    expiresAt = rs.time("expires_at"),
                    revokedAt = rs.getObject("revoked_at", OffsetDateTime::class.java),
                )
            }.optional().orElse(null)

    private fun revokeAll(userId: UUID) {
        jdbc.sql("UPDATE sessions SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL")
            .param("userId", userId)
            .update()
    }

    private fun invitationExists(id: UUID): Boolean =
        jdbc.sql("SELECT count(*) FROM invitations WHERE id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single() > 0

    private fun audit(actor: UUID?, action: String, targetType: String?, targetId: String?, ip: String?) {
        jdbc.sql(
            """
            INSERT INTO audit_events (actor_user_id, action, target_type, target_id, ip)
            VALUES (:actor, :action, :targetType, :targetId, CAST(:ip AS inet))
            """.trimIndent(),
        ).param("actor", actor)
            .param("action", action)
            .param("targetType", targetType)
            .param("targetId", targetId)
            .param("ip", ip)
            .update()
    }

    private fun requireAdmin(principal: MessengerPrincipal) {
        if (principal.adminRole != "owner" && principal.adminRole != "admin") {
            throw ApiException(HttpStatus.FORBIDDEN, "forbidden", "Access is denied.")
        }
    }

    private fun normalizeUsername(value: String): String {
        val username = value.trim().lowercase()
        if (!username.matches(Regex("^[a-z0-9_]{3,32}$"))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Username is invalid.")
        }
        return username
    }

    private fun normalizePlatform(value: String): String {
        val platform = value.trim().lowercase()
        if (platform !in setOf("android", "web", "admin")) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Platform is invalid.")
        }
        return platform
    }

    private fun validatePassword(username: String, password: String) {
        if (password.length < 12 || password.length > 200 || password.equals(username, ignoreCase = true)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "weak_password", "Password does not meet the policy.")
        }
    }

    private fun ResultSet.user() = StoredUser(
        id = uuid("id"),
        username = getString("username"),
        displayName = getString("display_name"),
        passwordHash = getString("password_hash"),
        status = getString("status"),
        role = getString("role"),
    )

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    private fun ResultSet.time(column: String): OffsetDateTime = getObject(column, OffsetDateTime::class.java)

    private data class StoredUser(
        val id: UUID,
        val username: String,
        val displayName: String,
        val passwordHash: String,
        val status: String,
        val role: String?,
    )

    private data class StoredSession(
        val id: UUID,
        val userId: UUID,
        val deviceId: UUID,
        val expiresAt: OffsetDateTime,
        val revokedAt: OffsetDateTime?,
    )
}

@Configuration
class PasswordConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
}
