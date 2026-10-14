package ru.pm.messenger.identity

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import ru.pm.messenger.config.CorrelationIdFilter

@Component
class BearerTokenFilter(
    private val auth: AuthService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }
        if (!header.startsWith("Bearer ")) {
            writeUnauthorized(response)
            return
        }
        val principal = auth.authenticate(header.removePrefix("Bearer ").trim())
        if (principal == null) {
            writeUnauthorized(response)
            return
        }
        val authorities = mutableListOf(SimpleGrantedAuthority("ROLE_USER"))
        if (principal.adminRole != null) {
            authorities.add(SimpleGrantedAuthority("ROLE_${principal.adminRole.uppercase()}"))
        }
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, authorities)
        try {
            filterChain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun writeUnauthorized(response: HttpServletResponse) {
        val correlationId = MDC.get(CorrelationIdFilter.MDC_KEY).orEmpty()
        response.status = 401
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(
            """{"error":{"code":"unauthorized","message":"Authentication is required.","correlationId":"$correlationId"}}""",
        )
    }
}

fun clientMeta(request: HttpServletRequest): ClientMeta {
    val forwarded = request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
    val candidate = forwarded?.takeIf { it.isNotEmpty() } ?: request.remoteAddr
    val ip = candidate?.takeIf { it.length <= 45 && it.matches(Regex("^[0-9a-fA-F:.]+$")) }
    val agent = request.getHeader("User-Agent")
        ?.replace(Regex("[\\r\\n]"), " ")
        ?.take(300)
    return ClientMeta(ip, agent)
}

fun currentPrincipal(): MessengerPrincipal {
    val principal = SecurityContextHolder.getContext().authentication?.principal
    if (principal !is MessengerPrincipal) {
        throw ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication is required.")
    }
    return principal
}
