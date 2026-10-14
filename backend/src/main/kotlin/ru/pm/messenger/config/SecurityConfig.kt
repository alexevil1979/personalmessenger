package ru.pm.messenger.config

import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import ru.pm.messenger.identity.BearerTokenFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @param:Value("\${app.cors.allowed-origins}") private val allowedOrigins: String,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity, bearer: BearerTokenFilter): SecurityFilterChain {
        val cors = UrlBasedCorsConfigurationSource()
        val config = CorsConfiguration()
        config.allowedOrigins = allowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("*")
        config.exposedHeaders = listOf(CorrelationIdFilter.HEADER)
        config.allowCredentials = false
        cors.registerCorsConfiguration("/**", config)
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(cors) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                auth.requestMatchers(HttpMethod.GET, "/api/v1/attachments/*/content").permitAll()
                auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                auth.requestMatchers(
                    HttpMethod.GET,
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                ).permitAll()
                auth.anyRequest().authenticated()
            }
            .httpBasic { it.disable() }
            .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter::class.java)
            .formLogin { it.disable() }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { _, response, _ ->
                    writeError(response, 401, "unauthorized", "Authentication is required.")
                }
                exceptions.accessDeniedHandler { _, response, _ ->
                    writeError(response, 403, "forbidden", "Access is denied.")
                }
            }
        return http.build()
    }

    private fun writeError(response: HttpServletResponse, status: Int, code: String, message: String) {
        val correlationId = MDC.get(CorrelationIdFilter.MDC_KEY).orEmpty()
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(
            """{"error":{"code":"$code","message":"$message","correlationId":"$correlationId"}}""",
        )
    }
}
