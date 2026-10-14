package ru.pm.messenger.chat

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class ChatSocketConfig(
    private val handler: ChatSocketHandler,
    private val handshake: ChatHandshakeInterceptor,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/api/v1/ws")
            .addInterceptors(handshake)
            .setAllowedOriginPatterns("*")
    }
}
