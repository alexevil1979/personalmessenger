package ru.pm.messenger.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("!test")
class SecretRequirements(
    @Value("\${DB_PASSWORD:}") private val dbPassword: String,
    @Value("\${REDIS_PASSWORD:}") private val redisPassword: String,
) {
    @PostConstruct
    fun verify() {
        requireStrong("DB_PASSWORD", dbPassword)
        requireStrong("REDIS_PASSWORD", redisPassword)
        log.info("Required runtime secrets are present and not placeholder values")
    }

    private fun requireStrong(name: String, value: String) {
        require(value.length >= 12) { "$name must be at least 12 characters" }
        require(value.lowercase() !in WEAK) { "$name uses a forbidden placeholder" }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SecretRequirements::class.java)
        val WEAK = setOf("change-me", "changeme", "password", "secret", "messenger", "admin", "qwerty123456")
    }
}
