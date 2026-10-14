package ru.pm.messenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.WebSocket
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
import ru.pm.messenger.data.parseSocketMessage
import javax.inject.Inject

data class ChatUi(
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val openId: String? = null,
    val openType: String = "direct",
    val openRole: String = "member",
    val peerName: String = "",
    val errorCode: String? = null,
    val working: Boolean = false,
    val recording: Boolean = false,
    val downloadUrl: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatApi: ChatApi,
    private val chatDao: ChatDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUi())
    val state: StateFlow<ChatUi> = _state.asStateFlow()
    private var socket: WebSocket? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private val recordLock = Any()

    fun start() {
        refresh()
        socket?.close(1000, null)
        socket = chatApi.connect { payload ->
            val incoming = runCatching { parseSocketMessage(payload) }.getOrNull() ?: return@connect
            viewModelScope.launch(Dispatchers.IO) {
                chatDao.saveMessage(incoming.toEntity())
                if (_state.value.openId == incoming.conversationId) reloadMessages(incoming.conversationId)
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
                )
                sync(conversation.id)
            } catch (error: AuthCallException) {
                _state.value = _state.value.copy(working = false, errorCode = error.code)
            } catch (_: Exception) {
                _state.value = _state.value.copy(working = false, errorCode = "network")
            }
        }
    }

    fun openConversation(id: String, peerName: String, type: String, role: String) {
        _state.value = _state.value.copy(
            openId = id,
            openType = type,
            openRole = role,
            peerName = peerName,
            errorCode = null,
        )
        viewModelScope.launch(Dispatchers.IO) { sync(id) }
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

    fun send(text: String) {
        val conversationId = _state.value.openId ?: return
        val body = text.trim()
        if (body.isEmpty() || _state.value.working) return
        _state.value = _state.value.copy(working = true, errorCode = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = chatApi.send(conversationId, body)
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

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val remote = chatApi.conversations()
                chatDao.clearConversations()
                chatDao.saveConversations(remote.map { it.toEntity() })
                _state.value = _state.value.copy(conversations = chatDao.conversations(), errorCode = null)
            } catch (_: Exception) {
                _state.value = _state.value.copy(conversations = chatDao.conversations())
            }
        }
    }

    private fun sync(conversationId: String) {
        val incoming = chatApi.messages(conversationId, chatDao.maxSeq(conversationId))
        incoming.forEach { chatDao.saveMessage(it.toEntity()) }
        reloadMessages(conversationId)
    }

    override fun onCleared() {
        val rec = synchronized(recordLock) {
            recorder.also {
                recorder = null
                recordingFile = null
            }
        }
        rec?.runCatching { release() }
        super.onCleared()
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
)
