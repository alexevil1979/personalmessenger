package ru.pm.messenger.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

data class Account(
    val userId: String,
    val username: String,
    val displayName: String,
    val role: String?,
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String,
    val baseUrl: String,
)

data class Profile(
    val userId: String,
    val username: String,
    val displayName: String,
    val role: String?,
)

class AuthCallException(
    val status: Int,
    val code: String,
    val serverMessage: String,
) : IOException(serverMessage)

fun parseAccount(body: String, baseUrl: String): Account {
    val json = JSONObject(body)
    val user = json.getJSONObject("user")
    return Account(
        userId = user.getString("id"),
        username = user.getString("username"),
        displayName = user.getString("displayName"),
        role = user.optionalString("role"),
        accessToken = json.getString("accessToken"),
        refreshToken = json.getString("refreshToken"),
        sessionId = json.getString("sessionId"),
        baseUrl = baseUrl.trimEnd('/'),
    )
}

fun parseProfile(body: String): Profile {
    val json = JSONObject(body)
    return Profile(
        userId = json.getString("id"),
        username = json.getString("username"),
        displayName = json.getString("displayName"),
        role = json.optionalString("role"),
    )
}

fun parseAuthError(body: String, status: Int): AuthCallException {
    val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
    return AuthCallException(
        status = status,
        code = error?.optString("code").orEmpty().ifBlank { "http_$status" },
        serverMessage = error?.optString("message").orEmpty(),
    )
}

private fun JSONObject.optionalString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key).takeIf { it.isNotEmpty() }

interface AuthApi {
    fun register(
        baseUrl: String,
        username: String,
        displayName: String,
        password: String,
        invitationCode: String,
        deviceName: String,
    ): Account

    fun login(baseUrl: String, username: String, password: String, deviceName: String): Account

    fun refresh(account: Account): Account

    fun me(account: Account): Profile

    fun logout(account: Account)
}

class OkHttpAuthApi @Inject constructor(
    private val client: OkHttpClient,
) : AuthApi {
    override fun register(
        baseUrl: String,
        username: String,
        displayName: String,
        password: String,
        invitationCode: String,
        deviceName: String,
    ): Account = post(
        baseUrl,
        "/api/v1/auth/register",
        JSONObject()
            .put("username", username)
            .put("displayName", displayName)
            .put("password", password)
            .put("invitationCode", invitationCode)
            .put("deviceName", deviceName)
            .put("platform", "android")
            .put("privacyPolicyAccepted", true),
        token = null,
    ) { body -> parseAccount(body, baseUrl) }

    override fun login(baseUrl: String, username: String, password: String, deviceName: String): Account = post(
        baseUrl,
        "/api/v1/auth/login",
        JSONObject()
            .put("username", username)
            .put("password", password)
            .put("deviceName", deviceName)
            .put("platform", "android"),
        token = null,
    ) { body -> parseAccount(body, baseUrl) }

    override fun refresh(account: Account): Account = post(
        account.baseUrl,
        "/api/v1/auth/refresh",
        JSONObject().put("refreshToken", account.refreshToken),
        token = null,
    ) { body -> parseAccount(body, account.baseUrl) }

    override fun me(account: Account): Profile = get(account, "/api/v1/me", ::parseProfile)

    override fun logout(account: Account) {
        post(account.baseUrl, "/api/v1/auth/logout", JSONObject(), account.accessToken) { _ -> Unit }
    }

    private fun <T> get(account: Account, path: String, parse: (String) -> T): T {
        val request = Request.Builder()
            .url(account.baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${account.accessToken}")
            .get()
            .build()
        return execute(request, parse)
    }

    private fun <T> post(
        baseUrl: String,
        path: String,
        json: JSONObject,
        token: String?,
        parse: (String) -> T,
    ): T {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .post(json.toString().toRequestBody(JSON))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return execute(builder.build(), parse)
    }

    private fun <T> execute(request: Request, parse: (String) -> T): T {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseAuthError(body, response.code)
            if (response.code == 204 || body.isBlank()) return parse("")
            return parse(body)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
