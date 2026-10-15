package ru.pm.messenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import org.json.JSONObject
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.pm.messenger.data.AuthCallException
import ru.pm.messenger.data.ChatApi
import ru.pm.messenger.data.ChatDao
import ru.pm.messenger.data.ConversationEntity
import ru.pm.messenger.data.MessageEntity
import ru.pm.messenger.data.DirectCipher
import ru.pm.messenger.data.IdentityStore
import ru.pm.messenger.data.SocketEvent
import ru.pm.messenger.data.decodeKey
import ru.pm.messenger.data.encodeKey
import ru.pm.messenger.data.fingerprintOf
import ru.pm.messenger.data.parseSocketEvent
import java.util.UUID
import javax.inject.Inject

data class ChatUi(
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val openId: String? = null,
    val openType: String = "direct",
    val openRole: String = "member",
    val peerName: String = "",
    val peerUsername: String = "",
    val peerBlocked: Boolean = false,
    val peerLastReadSeq: Long = 0,
    val peerLastSeenAt: String = "",
    val peerOnline: Boolean = false,
    val peerIdentityKey: String = "",
    val callPhase: String = "idle",
    val callId: String = "",
    val callKind: String = "audio",
    val callPeer: String = "",
    val callConversationId: String = "",
    val errorCode: String? = null,
    val working: Boolean = false,
    val recording: Boolean = false,
    val downloadUrl: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatApi: ChatApi,
    private val chatDao: ChatDao,
    private val identityStore: IdentityStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUi())
    val state: StateFlow<ChatUi> = _state.asStateFlow()
    private var socket: WebSocket? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private val recordLock = Any()
    private var engine: CallEngine? = null
    private var pendingOffer: String = ""

    fun start() {
        refresh()
        socket?.close(1000, null)
        socket = chatApi.connect { payload ->
            val event = runCatching { parseSocketEvent(payload) }.getOrNull() ?: return@connect
            viewModelScope.launch(Dispatchers.IO) {
                when (event) {
                    is SocketEvent.Incoming -> {
                        val incoming = event.message
                        chatDao.saveMessage(storeRemote(incoming))
                        if (_state.value.openId == incoming.conversationId) {
                            runCatching { chatApi.markRead(incoming.conversationId, incoming.seq) }
                            reloadMessages(incoming.conversationId)
                        }
                    }
                    is SocketEvent.Deleted -> {
                        if (event.seq > 0) chatDao.deleteBySeq(event.conversationId, event.seq)
                        if (_state.value.openId == event.conversationId) reloadMessages(event.conversationId)
                    }
                    is SocketEvent.Read -> {
                        val mine = event.username.equals(chatApi.accountUsername(), ignoreCase = true)
                        if (!mine && _state.value.openId == event.conversationId && _state.value.peerUsername.equals(event.username, ignoreCase = true)) {
                            _state.value = _state.value.copy(
                                peerLastReadSeq = maxOf(_state.value.peerLastReadSeq, event.lastReadSeq),
                            )
                        }
                    }
                    is SocketEvent.Presence -> {
                        val mine = event.username.equals(chatApi.accountUsername(), ignoreCase = true)
                        if (!mine && _state.value.peerUsername.equals(event.username, ignoreCase = true)) {
                            _state.value = _state.value.copy(peerOnline = event.online)
                        }
                    }
                    is SocketEvent.Call -> handleCall(event)
                }
                refresh()
            }
        }
    }

    fun stop() {
        socket?.close(1000, null)
        socket = null
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.clearConversations()
            chatDao.clearMessages()
            _state.value = ChatUi()
        }
    }

    fun openPeer(username: String) {
        val name = username.trim()
        if (name.isEmpty() || _state.value.working) return
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversation = chatApi.openDirect(name)
                chatDao.saveConversations(listOf(conversation.toEntity()))
                _state.value = _state.value.copy(
                    working = false,
                    openId = conversation.id,
                    openType = conversation.type,
                    openRole = conversation.myRole,
                    peerName = conversation.peerDisplayName,
                    peerUsername = conversation.peerUsername,
                    peerBlocked = false,
                    peerLastReadSeq = conversation.peerLastReadSeq,
                    peerLastSeenAt = conversation.peerLastSeenAt,
                    peerOnline = conversation.peerOnline,
                    peerIdentityKey = conversation.peerIdentityKey,
                )
                sync(conversation.id)
                refreshBlock(conversation.id, conversation.peerUsername)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    fun openConversation(id: String, peerName: String, type: String, role: String, peerUsername: String) {
        val known = _state.value.conversations.firstOrNull { it.id == id }
        _state.value = _state.value.copy(
            openId = id,
            openType = type,
            openRole = role,
            peerName = peerName,
            peerUsername = peerUsername,
            peerBlocked = false,
            peerLastReadSeq = known?.peerLastReadSeq ?: 0,
            peerLastSeenAt = known?.peerLastSeenAt.orEmpty(),
            peerOnline = known?.peerOnline ?: false,
            peerIdentityKey = known?.peerIdentityKey.orEmpty(),
            errorCode = null,
        )
        viewModelScope.launch(Dispatchers.IO) { sync(id) }
        refreshBlock(id, peerUsername)
    }

    fun createGroup(title: String) {
        val name = title.trim()
        if (name.isEmpty() || _state.value.working) return
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversation = chatApi.createGroup(name)
                chatDao.saveConversations(listOf(conversation.toEntity()))
                _state.value = _state.value.copy(
                    working = false,
                    openId = conversation.id,
                    openType = conversation.type,
                    openRole = conversation.myRole,
                    peerName = conversation.peerDisplayName,
                    peerUsername = conversation.peerUsername,
                    peerBlocked = false,
                    peerLastReadSeq = conversation.peerLastReadSeq,
                    peerLastSeenAt = conversation.peerLastSeenAt,
                    peerOnline = conversation.peerOnline,
                    peerIdentityKey = conversation.peerIdentityKey,
                )
                sync(conversation.id)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    fun addMember(username: String) {
        val conversationId = _state.value.openId ?: return
        val name = username.trim()
        if (name.isEmpty() || _state.value.working) return
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatApi.addMember(conversationId, name)
                _state.value = _state.value.copy(working = false)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    fun closeConversation() {
        _state.value = _state.value.copy(openId = null, messages = emptyList(), errorCode = null)
        refresh()
    }

    fun showError(code: String) {
        _state.value = _state.value.copy(errorCode = code)
    }

    fun startRecording() {
        val conversationId = _state.value.openId ?: return
        if (_state.value.working || _state.value.recording) return
        val file = File(context.cacheDir, "voice-$conversationId.amr")
        if (file.exists()) file.delete()
        val next = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.AMR_WB)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB)
            next.setOutputFile(file.absolutePath)
            next.setMaxDuration(60_000)
            next.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishRecording()
            }
            next.prepare()
            next.start()
        } catch (_: Exception) {
            next.release()
            file.delete()
            _state.value = _state.value.copy(recording = false, errorCode = "mic_failed")
            return
        }
        synchronized(recordLock) {
            recorder = next
            recordingFile = file
        }
        _state.value = _state.value.copy(recording = true, errorCode = null)
    }

    fun finishRecording() {
        val rec: MediaRecorder
        val file: File
        synchronized(recordLock) {
            rec = recorder ?: return
            file = recordingFile ?: return
            recorder = null
            recordingFile = null
        }
        val conversationId = _state.value.openId
        _state.value = _state.value.copy(recording = false, working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                try {
                    rec.stop()
                } finally {
                    rec.release()
                }
                val bytes = if (file.exists()) file.readBytes() else ByteArray(0)
                file.delete()
                if (conversationId == null || bytes.isEmpty()) {
                    _state.value = _state.value.copy(working = false, errorCode = "mic_failed")
                    return@launch
                }
                val uploaded = chatApi.upload(conversationId, "voice.amr", bytes)
                val saved = chatApi.sendFile(conversationId, uploaded.id)
                chatDao.saveMessage(saved.toEntity())
                reloadMessages(conversationId)
                refresh()
                _state.value = _state.value.copy(working = false)
            } catch (error: AuthCallException) {
                file.delete()
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                file.delete()
                _state.value = _state.value.copy(working = false, errorCode = "mic_failed")
            }
        }
    }

    fun sendPicked(uri: Uri) {
        val conversationId = _state.value.openId ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("empty")
                if (bytes.size > 8_388_608) {
                    _state.value = _state.value.copy(working = false, errorCode = "payload_too_large")
                    return@launch
                }
                val uploaded = chatApi.upload(conversationId, displayName(uri), bytes)
                val saved = chatApi.sendFile(conversationId, uploaded.id)
                chatDao.saveMessage(saved.toEntity())
                reloadMessages(conversationId)
                refresh()
                _state.value = _state.value.copy(working = false)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    fun openAttachment(attachmentId: String) {
        if (attachmentId.isBlank() || _state.value.working) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = chatApi.attachmentLink(attachmentId)
                _state.value = _state.value.copy(downloadUrl = url, errorCode = null)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(errorCode = "network")
            }
        }
    }

    fun consumeDownload() {
        _state.value = _state.value.copy(downloadUrl = null)
    }

    fun send(text: String, replyToSeq: Long? = null) {
        val conversationId = _state.value.openId ?: return
        val body = text.trim()
        if (body.isEmpty() || _state.value.working) return
        val direct = _state.value.openType == "direct"
        if (direct && _state.value.peerIdentityKey.isBlank()) {
            _state.value = _state.value.copy(errorCode = "peer_key_missing")
            return
        }
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = if (direct) {
                    val box = seal(body)
                    chatApi.send(conversationId, "", replyToSeq, "x25519_v1", box)
                } else {
                    chatApi.send(conversationId, body, replyToSeq)
                }
                chatDao.saveMessage(storeRemote(saved, body))
                reloadMessages(conversationId)
                refresh()
                _state.value = _state.value.copy(working = false)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    fun editMessage(seq: Long, text: String) {
        val conversationId = _state.value.openId ?: return
        val body = text.trim()
        if (body.isEmpty() || seq <= 0 || _state.value.working) return
        val direct = _state.value.openType == "direct"
        if (direct && _state.value.peerIdentityKey.isBlank()) {
            _state.value = _state.value.copy(errorCode = "peer_key_missing")
            return
        }
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = if (direct) {
                    chatApi.editMessage(conversationId, seq, "", seal(body))
                } else {
                    chatApi.editMessage(conversationId, seq, body)
                }
                chatDao.saveMessage(storeRemote(saved, body))
                reloadMessages(conversationId)
                refresh()
                _state.value = _state.value.copy(working = false)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val userId = chatApi.accountUserId()
                if (userId.isNotBlank()) {
                    chatApi.publishIdentity(encodeKey(identityStore.ensure(userId).publicKey))
                }
            }
            try {
                val remote = chatApi.conversations()
                chatDao.clearConversations()
                chatDao.saveConversations(remote.map { it.toEntity() })
                val items = chatDao.conversations()
                val open = items.firstOrNull { it.id == _state.value.openId }
                _state.value = _state.value.copy(
                    conversations = items,
                    errorCode = null,
                    peerLastReadSeq = open?.peerLastReadSeq ?: _state.value.peerLastReadSeq,
                    peerLastSeenAt = open?.peerLastSeenAt ?: _state.value.peerLastSeenAt,
                    peerOnline = open?.peerOnline ?: _state.value.peerOnline,
                    peerIdentityKey = open?.peerIdentityKey ?: _state.value.peerIdentityKey,
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(conversations = chatDao.conversations())
            }
        }
    }

    fun blockOpenPeer() {
        val name = _state.value.peerUsername
        if (_state.value.openType != "direct" || name.isBlank() || _state.value.working) return
        val blocked = _state.value.peerBlocked
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (blocked) chatApi.unblock(name) else chatApi.block(name)
                _state.value = _state.value.copy(working = false, peerBlocked = !blocked)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    private fun refreshBlock(conversationId: String, peerUsername: String) {
        if (peerUsername.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val blocked = runCatching {
                chatApi.blocks().any { it.equals(peerUsername, ignoreCase = true) }
            }.getOrDefault(false)
            if (_state.value.openId == conversationId) {
                _state.value = _state.value.copy(peerBlocked = blocked)
            }
        }
    }

    fun leaveOpenGroup() {
        val conversationId = _state.value.openId ?: return
        if (_state.value.openType != "group" || _state.value.working) return
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatApi.leave(conversationId)
                _state.value = _state.value.copy(working = false, openId = null, messages = emptyList())
                refresh()
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    fun removeMember(username: String) {
        val conversationId = _state.value.openId ?: return
        val name = username.trim()
        if (name.isEmpty() || _state.value.working) return
        act { chatApi.removeMember(conversationId, name) }
    }

    private fun act(block: () -> Unit) {
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                _state.value = _state.value.copy(working = false)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    private fun sync(conversationId: String) {
        var after = (chatDao.maxSeq(conversationId) - 200).coerceAtLeast(0)
        repeat(3) {
            val page = chatApi.messages(conversationId, after)
            if (page.isEmpty()) return@repeat
            page.forEach { chatDao.saveMessage(storeRemote(it)) }
            val last = page.last().seq
            if (page.size < 200 || last <= after) return@repeat
            after = last
        }
        val cursor = chatDao.maxSeq(conversationId)
        if (cursor > 0) {
            runCatching { chatApi.markRead(conversationId, cursor) }
        }
        reloadMessages(conversationId)
        refresh()
    }

    fun deleteMessage(seq: Long) {
        val conversationId = _state.value.openId ?: return
        if (seq <= 0 || _state.value.working) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatApi.deleteMessage(conversationId, seq)
                chatDao.deleteBySeq(conversationId, seq)
                reloadMessages(conversationId)
                refresh()
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(errorCode = "network")
            }
        }
    }

    override fun onCleared() {
        val rec = synchronized(recordLock) {
            recorder.also {
                recorder = null
                recordingFile = null
            }
        }
        rec?.runCatching { release() }
        engine?.runCatching { stop() }
        engine = null
        super.onCleared()
    }

    fun bindCallRenderers(local: org.webrtc.SurfaceViewRenderer, remote: org.webrtc.SurfaceViewRenderer) {
        engine?.attach(local, remote)
    }

    fun myFingerprint(): String {
        val userId = chatApi.accountUserId()
        if (userId.isBlank()) return ""
        return identityStore.fingerprint(userId)
    }

    fun peerFingerprint(): String {
        val key = _state.value.peerIdentityKey
        if (key.isBlank()) return ""
        return fingerprintOf(decodeKey(key))
    }

    fun beginCall(kind: String) {
        val conversationId = _state.value.openId ?: return
        if (_state.value.openType != "direct" || _state.value.callPhase != "idle") return
        val callId = UUID.randomUUID().toString()
        _state.value = _state.value.copy(
            callPhase = "outgoing",
            callId = callId,
            callKind = kind,
            callPeer = _state.value.peerName,
            callConversationId = conversationId,
        )
        viewModelScope.launch(Dispatchers.Main) {
            val ice = runCatching { withContext(Dispatchers.IO) { chatApi.ice() } }.getOrDefault(emptyList())
            val next = CallEngine(context)
            engine = next
            next.start(kind, ice, onIce = { candidate ->
                signal("call-ice", callId, conversationId, kind, candidate = candidate.sdp, sdpMid = candidate.sdpMid, line = candidate.sdpMLineIndex)
            }, onReady = { description ->
                signal("call-invite", callId, conversationId, kind, sdp = description.description)
            }, offer = true)
        }
    }

    fun acceptCall() {
        val state = _state.value
        if (state.callPhase != "incoming") return
        val offer = pendingOffer
        viewModelScope.launch(Dispatchers.Main) {
            val ice = runCatching { withContext(Dispatchers.IO) { chatApi.ice() } }.getOrDefault(emptyList())
            val next = CallEngine(context)
            engine = next
            next.start(state.callKind, ice, onIce = { candidate ->
                signal("call-ice", state.callId, state.callConversationId, state.callKind, candidate = candidate.sdp, sdpMid = candidate.sdpMid, line = candidate.sdpMLineIndex)
            }, onReady = {}, offer = false)
            next.acceptOffer(offer) { answer ->
                signal("call-answer", state.callId, state.callConversationId, state.callKind, sdp = answer)
                _state.value = _state.value.copy(callPhase = "active")
            }
        }
    }

    fun hangup() {
        val state = _state.value
        if (state.callPhase != "idle") {
            signal("call-hangup", state.callId, state.callConversationId, state.callKind)
        }
        finishCall()
    }

    private fun handleCall(event: SocketEvent.Call) {
        when (event.type) {
            "call-invite" -> {
                if (_state.value.callPhase != "idle") {
                    signal("call-hangup", event.callId, event.conversationId, event.kind)
                    return
                }
                pendingOffer = event.sdp
                _state.value = _state.value.copy(
                    callPhase = "incoming",
                    callId = event.callId,
                    callKind = event.kind.ifBlank { "audio" },
                    callPeer = event.from,
                    callConversationId = event.conversationId,
                )
            }
            "call-answer" -> {
                if (event.callId != _state.value.callId) return
                engine?.acceptAnswer(event.sdp)
                _state.value = _state.value.copy(callPhase = "active")
            }
            "call-ice" -> {
                if (event.callId == _state.value.callId) engine?.addIce(event.candidate, event.sdpMid, event.sdpMLineIndex)
            }
            "call-hangup" -> if (event.callId == _state.value.callId) finishCall()
        }
    }

    private fun finishCall() {
        engine?.runCatching { stop() }
        engine = null
        pendingOffer = ""
        _state.value = _state.value.copy(callPhase = "idle", callId = "", callConversationId = "")
    }

    private fun signal(
        type: String,
        callId: String,
        conversationId: String,
        kind: String,
        sdp: String = "",
        candidate: String = "",
        sdpMid: String = "",
        line: Int = 0,
    ) {
        if (callId.isBlank() || conversationId.isBlank()) return
        socket?.send(
            JSONObject()
                .put("type", type)
                .put("callId", callId)
                .put("conversationId", conversationId)
                .put("kind", kind)
                .put("sdp", sdp)
                .put("candidate", candidate)
                .put("sdpMid", sdpMid)
                .put("sdpMLineIndex", line)
                .toString(),
        )
    }

    private fun seal(text: String): String {
        val identity = identityStore.ensure(chatApi.accountUserId())
        val box = DirectCipher.seal(identity, decodeKey(_state.value.peerIdentityKey), text.toByteArray(Charsets.UTF_8))
        return encodeKey(box)
    }

    private fun storeRemote(remote: ru.pm.messenger.data.RemoteMessage, knownPlaintext: String? = null): MessageEntity {
        val existing = chatDao.message(remote.clientMessageId)
        val opened = if (remote.encryption == "x25519_v1") openCiphertext(remote.ciphertext) else null
        val text = when {
            !knownPlaintext.isNullOrBlank() -> knownPlaintext
            remote.encryption != "x25519_v1" -> remote.text
            opened != null -> opened
            !existing?.text.isNullOrBlank() && existing?.text != LOCKED -> existing?.text.orEmpty()
            else -> LOCKED
        }
        val reply = when {
            remote.replyToText.isNotBlank() -> remote.replyToText
            remote.replyToSeq > 0 -> chatDao.messages(remote.conversationId).firstOrNull { it.seq == remote.replyToSeq }?.text.orEmpty()
            else -> ""
        }
        return remote.toEntity().copy(text = text, replyToText = reply.ifBlank { remote.replyToText })
    }

    private fun openCiphertext(ciphertext: String): String? {
        if (ciphertext.isBlank() || _state.value.peerIdentityKey.isBlank()) return null
        val identity = identityStore.ensure(chatApi.accountUserId())
        return runCatching {
            DirectCipher.open(
                identity.privateKey,
                identity.publicKey,
                decodeKey(_state.value.peerIdentityKey),
                decodeKey(ciphertext),
            )?.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun displayName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val name = it.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return "file"
    }

    private fun reloadMessages(conversationId: String) {
        if (_state.value.openId != conversationId) return
        _state.value = _state.value.copy(messages = chatDao.messages(conversationId))
    }
}

private fun ru.pm.messenger.data.RemoteConversation.toEntity() = ConversationEntity(
    id = id,
    type = type,
    myRole = myRole,
    peerUsername = peerUsername,
    peerDisplayName = peerDisplayName,
    preview = preview,
    updatedAt = updatedAt,
    unreadCount = unreadCount,
    peerLastReadSeq = peerLastReadSeq,
    peerLastSeenAt = peerLastSeenAt,
    peerOnline = peerOnline,
    peerIdentityKey = peerIdentityKey,
    lastEncrypted = lastEncrypted,
)

private fun ru.pm.messenger.data.RemoteMessage.toEntity() = MessageEntity(
    clientMessageId = clientMessageId,
    seq = seq,
    conversationId = conversationId,
    senderUsername = senderUsername,
    text = text,
    createdAt = createdAt,
    pending = false,
    kind = kind,
    attachmentId = attachmentId,
    attachmentName = attachmentName,
    replyToSeq = replyToSeq,
    replyToUsername = replyToUsername,
    replyToText = replyToText,
    edited = edited,
)

private const val LOCKED = "Не удалось расшифровать"
