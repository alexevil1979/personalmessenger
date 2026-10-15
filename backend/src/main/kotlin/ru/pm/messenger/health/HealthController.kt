package ru.pm.messenger.health

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

data class ComponentStatus(
    val status: String,
    val detail: String? = null,
)

data class HealthReport(
    val status: String,
    val version: String,
    val components: Map<String, ComponentStatus>,
)

object HealthProbes {
    fun postgres(dataSource: DataSource): ComponentStatus =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT 1").use { statement ->
                    statement.execute()
                }
            }
            ComponentStatus("UP")
        } catch (exception: SQLException) {
            ComponentStatus("DOWN", exception.sqlState ?: "database_unreachable")
        }

    fun redis(factory: RedisConnectionFactory): ComponentStatus =
        try {
            factory.connection.use { connection ->
                val pong = connection.ping()
                if (pong.equals("PONG", ignoreCase = true)) {
                    ComponentStatus("UP")
                } else {
                    ComponentStatus("DOWN", "unexpected_ping")
                }
            }
        } catch (_: Exception) {
            ComponentStatus("DOWN", "redis_unreachable")
        }

    fun minio(endpoint: String?, client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()): ComponentStatus {
        if (endpoint.isNullOrBlank()) {
            return ComponentStatus("NOT_CONFIGURED", "Object storage endpoint is not set")
        }
        return try {
            val request = HttpRequest.newBuilder(URI.create(endpoint.trimEnd('/') + "/minio/health/live"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() in 200..299) ComponentStatus("UP") else ComponentStatus("DOWN", "http_${response.statusCode()}")
        } catch (_: Exception) {
            ComponentStatus("DOWN", "object_storage_unreachable")
        }
    }

    fun report(
        version: String,
        postgres: ComponentStatus,
        redis: ComponentStatus,
        minio: ComponentStatus,
    ): HealthReport {
        val components = mapOf(
            "postgres" to postgres,
            "redis" to redis,
            "minio" to minio,
            "websocket" to ComponentStatus("UP", "Direct text is encrypted on the devices. Groups, files and voice are plaintext. Call signaling is relayed here."),
            "e2ee" to ComponentStatus(
                "PARTIAL",
                "Direct text uses X25519 and AES-GCM on the devices. The server stores the encrypted bytes and cannot read them. Groups, files and voice remain readable by the server. This is not the Signal protocol.",
            ),
        )
        val down = listOf(postgres, redis, minio).any { it.status == "DOWN" }
        return HealthReport(status = if (down) "DOWN" else "UP", version = version, components = components)
    }
}

@RestController
@RequestMapping("/api/v1")
class HealthController(
    private val dataSource: DataSource,
    private val redis: RedisConnectionFactory,
    @Value("\${app.version}") private val version: String,
    @Value("\${app.storage.endpoint:}") private val storageEndpoint: String,
) {
    @GetMapping("/health")
    fun health(): ResponseEntity<HealthReport> {
        val report = HealthProbes.report(
            version = version,
            postgres = HealthProbes.postgres(dataSource),
            redis = HealthProbes.redis(redis),
            minio = HealthProbes.minio(storageEndpoint),
        )
        val status = if (report.status == "UP") 200 else 503
        return ResponseEntity.status(status).body(report)
    }
}
