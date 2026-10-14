package ru.pm.messenger.identity

import java.time.OffsetDateTime
import java.util.UUID

data class ClientMeta(val ip: String?, val userAgent: String?)

data class RegisterRequest(
    val username: String,
    val displayName: String,
    val password: String,
    val invitationCode: String,
    val deviceName: String,
    val platform: String,
    val privacyPolicyAccepted: Boolean,
)

data class LoginRequest(
    val username: String,
    val password: String,
    val deviceName: String,
    val platform: String,
)

data class RefreshRequest(val refreshToken: String)

data class UserView(
    val id: UUID,
    val username: String,
    val displayName: String,
    val role: String?,
)

data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: OffsetDateTime,
    val refreshExpiresAt: OffsetDateTime,
    val user: UserView,
    val deviceId: UUID,
    val sessionId: UUID,
)

data class SessionView(
    val id: UUID,
    val deviceName: String,
    val platform: String,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val current: Boolean,
)

data class InvitationCreated(
    val id: UUID,
    val code: String,
    val expiresAt: OffsetDateTime,
    val maxUses: Int,
)

data class InvitationView(
    val id: UUID,
    val expiresAt: OffsetDateTime,
    val maxUses: Int,
    val useCount: Int,
    val revokedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val note: String?,
    val createdBy: String?,
)

data class CreateInvitationRequest(
    val expiresInHours: Int,
    val maxUses: Int,
    val note: String?,
)

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

data class BlockRequest(val username: String)

data class BlockView(val username: String, val displayName: String)

data class AdminUserView(
    val id: UUID,
    val username: String,
    val displayName: String,
    val status: String,
    val role: String?,
    val createdAt: OffsetDateTime,
)

data class SetStatusRequest(val status: String)

data class ReadCursor(val lastReadSeq: Long)

data class MessengerPrincipal(
    val userId: UUID,
    val sessionId: UUID,
    val deviceId: UUID,
    val adminRole: String?,
)
