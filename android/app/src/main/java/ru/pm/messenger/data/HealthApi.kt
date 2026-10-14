package ru.pm.messenger.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

data class HealthSnapshot(
    val status: String,
    val version: String,
    val postgres: String,
    val redis: String,
)

fun parseHealthSnapshot(body: String): HealthSnapshot {
    val json = JSONObject(body)
    val components = json.getJSONObject("components")
    return HealthSnapshot(
        status = json.getString("status"),
        version = json.getString("version"),
        postgres = components.getJSONObject("postgres").getString("status"),
        redis = components.getJSONObject("redis").getString("status"),
    )
}

interface HealthApi {
    fun fetch(baseUrl: String): HealthSnapshot
}

class OkHttpHealthApi @Inject constructor(
    private val client: OkHttpClient,
) : HealthApi {
    override fun fetch(baseUrl: String): HealthSnapshot {
        val url = baseUrl.trimEnd('/') + "/api/v1/health"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw IOException("Empty health response (${response.code})")
            }
            return parseHealthSnapshot(body)
        }
    }
}
