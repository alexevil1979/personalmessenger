package ru.pm.messenger.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun personalMessengerApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Personal Messenger API")
                .version("v1")
                .description(
                    "Versioned API /api/v1. Stage 1 exposes health only. " +
                        "Messages are not end-to-end encrypted yet. " +
                        "Clients must not send user_id or role as an authority; the server derives identity from the session.",
                ),
        )
}
