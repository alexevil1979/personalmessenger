package ru.pm.messenger.identity

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Base64

data class IdentityKeyRequest(val publicKey: String)

@Service
class DeviceKeyService(
    private val jdbc: JdbcClient,
) {
    fun publish(principal: MessengerPrincipal, publicKey: String) {
        val bytes = try {
            Base64.getDecoder().decode(publicKey)
        } catch (_: IllegalArgumentException) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Identity key is invalid.")
        }
        if (bytes.size != 32) {
            throw ApiException(HttpStatus.BAD_REQUEST, "validation_error", "Identity key is invalid.")
        }
        val updated = jdbc.sql(
            """
            UPDATE devices
            SET identity_public_key = :publicKey
            WHERE id = :deviceId AND user_id = :userId AND revoked_at IS NULL
            """.trimIndent(),
        ).param("publicKey", bytes)
            .param("deviceId", principal.deviceId)
            .param("userId", principal.userId)
            .update()
        if (updated == 0) {
            throw ApiException(HttpStatus.NOT_FOUND, "device_not_found", "Device was not found.")
        }
    }
}

@RestController
@RequestMapping("/api/v1/devices")
class DeviceController(
    private val keys: DeviceKeyService,
) {
    @PutMapping("/identity")
    fun publish(@RequestBody body: IdentityKeyRequest) {
        keys.publish(currentPrincipal(), body.publicKey)
    }
}
