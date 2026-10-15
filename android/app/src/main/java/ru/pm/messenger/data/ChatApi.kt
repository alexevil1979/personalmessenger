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
    val unreadCount: Int,
    val peerLastReadSeq: Long,
    val peerLastSeenAt: String,
    val peerOnline: Boolean,
    val peerIdentityKey: String,
    val lastEncrypted: Boolean,
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
    val replyToSeq: Long,
    val replyToUsername: String,
    val replyToText: String,
    val edited: Boolean,
    val encryption: String,
    val ciphertext: String,
)

data class RemoteIce(
    val urls: List<String>,
    val username: String,
    val credential: String,
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
    text = json.clean("text"),
    createdAt = json.optString("createdAt"),
    kind = json.optString("kind", "text"),
    attachmentId = json.clean("attachmentId"),
    attachmentName = json.clean("attachmentName"),
    replyToSeq = if (!json.has("replyToSeq") || json.isNull("replyToSeq")) 0L else json.optLong("replyToSeq"),
    replyToUsername = json.clean("replyToUsername"),
    replyToText = json.clean("replyToText"),
    edited = json.optBoolean("edited", false),
    encryption = json.optString("encryption", "none"),
    ciphertext = json.clean("ciphertext"),
)

private fun JSONObject.clean(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return optString(key).takeUnless { it == "null" }.orEmpty()
}

fun parseConversation(item: JSONObject) = RemoteConversation(
    id = item.getString("id"),
    type = item.optString("type", "direct"),
    myRole = item.optString("myRole", "member"),
    peerUsername = item.optString("peerUsername"),
    peerDisplayName = item.optString("peerDisplayName"),
    preview = item.optString("preview").takeUnless { it == "null" }.orEmpty(),
    updatedAt = item.optString("updatedAt"),
    unreadCount = item.optInt("unreadCount"),
    peerLastReadSeq = item.optLong("peerLastReadSeq"),
    peerLastSeenAt = item.clean("peerLastSeenAt"),
    peerOnline = item.optBoolean("peerOnline"),
    peerIdentityKey = item.clean("peerIdentityKey"),
    lastEncrypted = item.optBoolean("lastEncrypted"),
)

fun parseSocketEvent(body: String): SocketEvent? {
    val json = JSONObject(body)
    return when (json.optString("type")) {
        "message" -> SocketEvent.Incoming(parseRemoteMessage(json.getJSONObject("message")))
        "deleted" -> SocketEvent.Deleted(
            conversationId = json.optString("conversationId"),
            seq = json.optLong("seq"),
            clientMessageId = json.optString("clientMessageId"),
        )
        "read" -> SocketEvent.Read(
            conversationId = json.optString("conversationId"),
            username = json.clean("username"),
            lastReadSeq = json.optLong("lastReadSeq"),
        )
        "presence" -> SocketEvent.Presence(
            username = json.clean("username"),
            online = json.optBoolean("online"),
        )
        "call-invite", "call-answer", "call-ice", "call-hangup" -> SocketEvent.Call(
            type = json.optString("type"),
            callId = json.optString("callId"),
            conversationId = json.optString("conversationId"),
            kind = json.optString("kind", "audio"),
            sdp = json.optString("sdp"),
            candidate = json.optString("candidate"),
            sdpMid = json.optString("sdpMid"),
            sdpMLineIndex = json.optInt("sdpMLineIndex"),
            from = json.clean("from"),
        )
        else -> null
    }
}

sealed interface SocketEvent {
    data class Incoming(val message: RemoteMessage) : SocketEvent
    data class Deleted(val conversationId: String, val seq: Long, val clientMessageId: String) : SocketEvent
    data class Read(val conversationId: String, val username: String, val lastReadSeq: Long) : SocketEvent
    data class Presence(val username: String, val online: Boolean) : SocketEvent
    data class Call(
        val type: String,
        val callId: String,
        val conversationId: String,
        val kind: String,
        val sdp: String,
        val candidate: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val from: String,
    ) : SocketEvent
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

    fun deleteMessage(conversationId: String, seq: Long) = authorized { account ->
        call(account, "DELETE", "/api/v1/conversations/$conversationId/messages/$seq", null)
        Unit
    }

    fun accountUsername(): String = sessionStore.read()?.username.orEmpty()

    fun accountUserId(): String = sessionStore.read()?.userId.orEmpty()

    fun publishIdentity(publicKey: String) = authorized { account ->
        call(account, "PUT", "/api/v1/devices/identity", JSONObject().put("publicKey", publicKey))
        Unit
    }

    fun ice(): List<RemoteIce> = authorized { account ->
        val body = JSONObject(call(account, "GET", "/api/v1/calls/ice", null))
        val array = body.getJSONArray("iceServers")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val urls = item.getJSONArray("urls")
                add(
                    RemoteIce(
                        urls = buildList { for (url in 0 until urls.length()) add(urls.getString(url)) },
                        username = item.optString("username"),
                        credential = item.optString("credential"),
                    ),
                )
            }
        }
    }

    fun send(
        conversationId: String,
        text: String,
        replyToSeq: Long? = null,
        encryption: String = "none",
        ciphertext: String = "",
    ): RemoteMessage = authorized { account ->
        val payload = JSONObject().put("clientMessageId", UUID.randomUUID().toString()).put("encryption", encryption)
        if (encryption == "none") payload.put("text", text) else payload.put("ciphertext", ciphertext)
        if (replyToSeq != null && replyToSeq > 0) payload.put("replyToSeq", replyToSeq)
        parseRemoteMessage(JSONObject(call(account, "POST", "/api/v1/conversations/$conversationId/messages", payload)))
    }

    fun editMessage(conversationId: String, seq: Long, text: String, ciphertext: String = ""): RemoteMessage = authorized { account ->
        val payload = if (ciphertext.isBlank()) {
            JSONObject().put("text", text)
        } else {
            JSONObject().put("ciphertext", ciphertext)
        }
        parseRemoteMessage(JSONObject(call(account, "POST", "/api/v1/conversations/$conversationId/messages/$seq/edit", payload)))
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
        } else if (method == "PUT") {
            builder.put((json ?: JSONObject()).toString().toRequestBody(JSON))
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
