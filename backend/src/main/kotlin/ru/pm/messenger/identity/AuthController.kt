package ru.pm.messenger.identity

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val auth: AuthService) {
    @PostMapping("/register")
    fun register(@RequestBody body: RegisterRequest, request: HttpServletRequest): AuthResult =
        auth.register(body, clientMeta(request))

    @PostMapping("/login")
    fun login(@RequestBody body: LoginRequest, request: HttpServletRequest): AuthResult =
        auth.login(body, clientMeta(request))

    @PostMapping("/refresh")
    fun refresh(@RequestBody body: RefreshRequest, request: HttpServletRequest): AuthResult =
        auth.refresh(body.refreshToken, clientMeta(request))

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout() = auth.logout(currentPrincipal())

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logoutAll() = auth.logoutAll(currentPrincipal())
}

@RestController
@RequestMapping("/api/v1")
class SessionController(private val auth: AuthService) {
    @GetMapping("/me")
    fun me(): UserView = auth.me(currentPrincipal())

    @GetMapping("/sessions")
    fun sessions(): List<SessionView> = auth.sessions(currentPrincipal())

    @PostMapping("/sessions/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@PathVariable id: UUID) = auth.revokeSession(currentPrincipal(), id)
}

@RestController
@RequestMapping("/api/v1/admin/invitations")
class InvitationController(private val auth: AuthService) {
    @GetMapping
    fun list(): List<InvitationView> = auth.invitations(currentPrincipal())

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: CreateInvitationRequest): InvitationCreated =
        auth.createInvitation(currentPrincipal(), body)

    @PostMapping("/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@PathVariable id: UUID) = auth.revokeInvitation(currentPrincipal(), id)
}
