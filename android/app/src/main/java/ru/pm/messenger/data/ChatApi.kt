package ru.pm.messenger.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class RemoteConversation(
    val id: String,
    val type: String,
    val myRole: String,
    val peerUsername: String,
    val peerDisplayName: String,
    val preview: String,
    val updatedAt: String,
)

data class RemoteMessage(
    val seq: Long,
    val conversationId: String,
    val senderUsername: String,
    val clientMessageId: String,
    val text: String,
    val createdAt: String,
    val kind: String,
    val attachmentId: String,
    val attachmentName: String,
)

data class RemoteAttachment(
    val id: String,
    val kind: String,
    val originalName: String,
)

fun parseRemoteMessage(json: JSONObject): RemoteMessage = RemoteMessage(
    seq = json.getLong("seq"),
    conversationId = json.getString("conversationId"),
    senderUsername = json.getString("senderUsername"),
    clientMessageId = json.getString("clientMessageId"),
    text = json.optString("text"),
    createdAt = json.optString("createdAt"),
    kind = json.optString("kind", "text"),
    attachmentId = json.optString("attachmentId"),
    attachmentName = json.optString("attachmentName"),
)

fun parseConversation(item: JSONObject) = RemoteConversation(
    id = item.getString("id"),
    type = item.optString("type", "direct"),
    myRole = item.optString("myRole", "member"),
    peerUsername = item.optString("peerUsername"),
    peerDisplayName = item.optString("peerDisplayName"),
    preview = item.optString("preview").takeUnless { it == "null" }.orEmpty(),
    updatedAt = item.optString("updatedAt"),
)

fun parseSocketMessage(body: String): RemoteMessage? {
    val json = JSONObject(body)
    if (json.optString("type") != "message") return null
    return parseRemoteMessage(json.getJSONObject("message"))
}

@Singleton
class ChatApi @Inject constructor(
    private val client: OkHttpClient,
    private val sessionStore: SessionStore,
    private val authApi: AuthApi,
) {
    fun conversations(): List<RemoteConversation> = authorized { account ->
        val body = call(account, "GET", "/api/v1/conversations", null)
        val array = JSONArray(body)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(parseConversation(item))
            }
        }
    }

    fun openDirect(username: String): RemoteConversation = authorized { account ->
        val body = call(
            account,
            "POST",
            "/api/v1/conversations/direct",
            JSONObject().put("username", username),
        )
        parseConversation(JSONObject(body))
    }

    fun createGroup(title: String): RemoteConversation = authorized { account ->
        val body = call(
            account,
            "POST",
            "/api/v1/conversations/group",
            JSONObject().put("title", title),
        )
        parseConversation(JSONObject(body))
    }

    fun addMember(conversationId: String, username: String): RemoteConversation = authorized { account ->
        val body = call(
            account,
            "POST",
            "/api/v1/conversations/$conversationId/members",
            JSONObject().put("username", username),
        )
        parseConversation(JSONObject(body))
    }

    fun messages(conversationId: String, afterSeq: Long): List<RemoteMessage> = authorized { account ->
        val body = call(account, "GET", "/api/v1/conversations/$conversationId/messages?afterSeq=$afterSeq", null)
        val array = JSONArray(body)
        buildList {
            for (index in 0 until array.length()) {
                add(parseRemoteMessage(array.getJSONObject(index)))
            }
        }
    }

    fun upload(conversationId: String, fileName: String, bytes: ByteArray): RemoteAttachment = authorized { account ->
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(OCTET))
            .build()
        val raw = call(account, "POST", "/api/v1/conversations/$conversationId/attachments", null, body)
        val json = JSONObject(raw)
        RemoteAttachment(json.getString("id"), json.getString("kind"), json.optString("originalName"))
    }

    fun sendFile(conversationId: String, attachmentId: String): RemoteMessage = authorized { account ->
        val body = call(
            account,
            "POST",
            "/api/v1/conversations/$conversationId/messages",
            JSONObject()
                .put("clientMessageId", UUID.randomUUID().toString())
                .put("text", "")
                .put("attachmentId", attachmentId),
        )
        parseRemoteMessage(JSONObject(body))
    }

    fun attachmentLink(attachmentId: String): String = authorized { account ->
        val body = call(account, "POST", "/api/v1/attachments/$attachmentId/link", JSONObject())
        JSONObject(body).getString("url")
    }

    fun blocks(): List<String> = authorized { account ->
        val body = call(account, "GET", "/api/v1/blocks", null)
        val array = JSONArray(if (body.isBlank()) "[]" else body)
        buildList {
            for (index in 0 until array.length()) {
                add(array.getJSONObject(index).optString("username"))
            }
        }
    }

    fun block(username: String) = authorized { account ->
        call(account, "POST", "/api/v1/blocks", JSONObject().put("username", username))
        Unit
    }

    fun unblock(username: String) = authorized { account ->
        call(account, "DELETE", "/api/v1/blocks/$username", null)
        Unit
    }

    fun leave(conversationId: String) = authorized { account ->
        call(account, "POST", "/api/v1/conversations/$conversationId/leave", JSONObject())
        Unit
    }

    fun removeMember(conversationId: String, username: String) = authorized { account ->
        call(account, "DELETE", "/api/v1/conversations/$conversationId/members/$username", null)
        Unit
    }

    fun markRead(conversationId: String, seq: Long) = authorized { account ->
        call(account, "POST", "/api/v1/conversations/$conversationId/read", JSONObject().put("lastReadSeq", seq))
        Unit
    }

    fun send(conversationId: String, text: String): RemoteMessage = authorized { account ->
        val body = call(
            account,
            "POST",
            "/api/v1/conversations/$conversationId/messages",
            JSONObject().put("clientMessageId", UUID.randomUUID().toString()).put("text", text),
        )
        parseRemoteMessage(JSONObject(body))
    }

    fun connect(onMessage: (String) -> Unit): WebSocket? {
        val account = sessionStore.read() ?: return null
        val url = account.baseUrl.trimEnd('/').replace("https://", "wss://").replace("http://", "ws://") + "/api/v1/ws"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${account.accessToken}")
            .build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }
        })
    }

    private fun <T> authorized(block: (Account) -> T): T {
        val account = sessionStore.read() ?: throw AuthCallException(401, "unauthorized", "")
        return try {
            block(account)
        } catch (error: AuthCallException) {
            if (error.status != 401) throw error
            val rotated = authApi.refresh(account)
            sessionStore.write(rotated)
            block(rotated)
        }
    }

    private fun call(
        account: Account,
        method: String,
        path: String,
        json: JSONObject?,
        rawBody: okhttp3.RequestBody? = null,
    ): String {
        val builder = Request.Builder()
            .url(account.baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${account.accessToken}")
        if (method == "GET") {
            builder.get()
        } else if (method == "DELETE") {
            builder.delete()
        } else if (rawBody != null) {
            builder.post(rawBody)
        } else {
            builder.post((json ?: JSONObject()).toString().toRequestBody(JSON))
        }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw parseAuthError(body, response.code)
            return body
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val OCTET = "application/octet-stream".toMediaType()
    }
}
