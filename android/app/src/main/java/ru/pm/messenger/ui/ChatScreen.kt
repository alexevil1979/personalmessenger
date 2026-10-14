package ru.pm.messenger.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.pm.messenger.ChatViewModel
import ru.pm.messenger.R

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var peer by rememberSaveable { mutableStateOf("") }
    var groupTitle by rememberSaveable { mutableStateOf("") }
    var memberName by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri != null) viewModel.sendPicked(uri)
    }
    val pickAudio = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri != null) viewModel.sendPicked(uri)
    }
    val askMic = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) viewModel.startRecording() else viewModel.showError("mic_denied")
    }
    LaunchedEffect(state.downloadUrl) {
        val url = state.downloadUrl ?: return@LaunchedEffect
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        viewModel.consumeDownload()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.chats_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = stringResource(R.string.chats_plaintext), color = MaterialTheme.colorScheme.error)
        if (state.openId == null) {
            OutlinedTextField(
                value = peer,
                onValueChange = { peer = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.peer_username)) },
                singleLine = true,
            )
            Button(onClick = { viewModel.openPeer(peer) }, enabled = !state.working && peer.isNotBlank()) {
                Text(stringResource(R.string.open_chat))
            }
            OutlinedTextField(
                value = groupTitle,
                onValueChange = { groupTitle = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.group_title)) },
                singleLine = true,
            )
            Button(onClick = { viewModel.createGroup(groupTitle) }, enabled = !state.working && groupTitle.isNotBlank()) {
                Text(stringResource(R.string.create_group))
            }
            state.conversations.forEach { conversation ->
                TextButton(
                    onClick = {
                        viewModel.openConversation(
                            conversation.id,
                            conversation.peerDisplayName,
                            conversation.type,
                            conversation.myRole,
                            conversation.peerUsername,
                        )
                    },
                ) {
                    Column {
                        Text(conversation.peerDisplayName)
                        if (conversation.preview.isNotBlank()) {
                            Text(conversation.preview, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        } else {
            Text(stringResource(R.string.chat_with, state.peerName), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = viewModel::closeConversation) {
                Text(stringResource(R.string.back_to_chats))
            }
            if (state.openType == "group" && (state.openRole == "owner" || state.openRole == "admin")) {
                OutlinedTextField(
                    value = memberName,
                    onValueChange = { memberName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.add_member_name)) },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        viewModel.addMember(memberName)
                        memberName = ""
                    },
                    enabled = !state.working && memberName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.add_member))
                }
                TextButton(
                    onClick = {
                        viewModel.removeMember(memberName)
                        memberName = ""
                    },
                    enabled = !state.working && memberName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.remove_member))
                }
            }
            if (state.openType == "direct") {
                TextButton(onClick = viewModel::blockOpenPeer, enabled = !state.working) {
                    Text(stringResource(R.string.block_person))
                }
            }
            if (state.openType == "group") {
                TextButton(onClick = viewModel::leaveOpenGroup, enabled = !state.working) {
                    Text(stringResource(R.string.leave_group))
                }
            }
            state.messages.forEach { message ->
                val label = when {
                    message.kind == "voice" && message.attachmentName.isNotBlank() ->
                        "${message.senderUsername}: ${message.attachmentName}"
                    message.attachmentName.isNotBlank() && message.text != message.attachmentName ->
                        "${message.senderUsername}: ${message.attachmentName} ${message.text}"
                    message.attachmentName.isNotBlank() ->
                        "${message.senderUsername}: ${message.attachmentName}"
                    else -> "${message.senderUsername}: ${message.text}"
                }
                if (message.attachmentId.isNotBlank()) {
                    TextButton(onClick = { viewModel.openAttachment(message.attachmentId) }) {
                        Text(label)
                    }
                } else {
                    Text(label)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.message_text)) },
                )
                Button(
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    enabled = !state.working && draft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.send_message))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pickFile.launch("*/*") }, enabled = !state.working) {
                    Text(stringResource(R.string.attach_file))
                }
                TextButton(onClick = { pickAudio.launch("audio/*") }, enabled = !state.working) {
                    Text(stringResource(R.string.attach_audio))
                }
                TextButton(
                    onClick = {
                        if (state.recording) {
                            viewModel.finishRecording()
                        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.startRecording()
                        } else {
                            askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !state.working,
                ) {
                    Text(stringResource(if (state.recording) R.string.stop_recording else R.string.record_voice))
                }
            }
        }
        state.errorCode?.let { code ->
            Text(text = stringResource(chatError(code)), color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun chatError(code: String): Int = when (code) {
    "user_not_found" -> R.string.error_user_missing
    "cannot_message_self" -> R.string.error_self
    "user_blocked" -> R.string.error_blocked_user
    "not_a_group" -> R.string.error_not_group
    "cannot_add_self" -> R.string.error_self
    "forbidden" -> R.string.error_forbidden
    "unsupported_media" -> R.string.error_unsupported
    "payload_too_large" -> R.string.error_too_large
    "mic_denied" -> R.string.error_mic_denied
    "mic_failed" -> R.string.error_mic_failed
    "owner_must_stay" -> R.string.error_owner_stays
    "cannot_remove_self" -> R.string.error_remove_self
    "network" -> R.string.error_network
    else -> R.string.error_generic
}
