package ru.pm.messenger.identity

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.auth")
data class AuthProperties(
    val accessTtl: Duration,
    val refreshTtl: Duration,
    val loginFailures: Int,
    val loginWindow: Duration,
)
