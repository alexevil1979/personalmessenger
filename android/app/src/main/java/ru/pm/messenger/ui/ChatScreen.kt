package ru.pm.messenger.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.pm.messenger.ChatViewModel
import ru.pm.messenger.R
import ru.pm.messenger.data.ConversationEntity
import ru.pm.messenger.data.MessageEntity

private sealed interface ChatRow {
    data class Day(val label: String) : ChatRow
    data class Bubble(val message: MessageEntity) : ChatRow
}

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    myUsername: String,
    accountName: String,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.openId == null) {
        ChatList(viewModel, accountName, onOpenSettings)
    } else {
        Conversation(viewModel, myUsername)
    }
}

@Composable
private fun ChatList(viewModel: ChatViewModel, accountName: String, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    var peer by rememberSaveable { mutableStateOf("") }
    var groupTitle by rememberSaveable { mutableStateOf("") }
    val visible = state.conversations.filter { conversation ->
        val needle = query.trim()
        needle.isEmpty() ||
            conversation.peerDisplayName.contains(needle, ignoreCase = true) ||
            conversation.peerUsername.contains(needle, ignoreCase = true) ||
            conversation.preview.contains(needle, ignoreCase = true)
    }
    Column(Modifier.fillMaxSize().background(Tg.bg)) {
        Column(Modifier.fillMaxWidth().background(Tg.bars).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chats_title), color = Tg.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.new_chat), tint = Tg.accentBright)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = Tg.secondary)
                }
            }
            Text(accountName, color = Tg.secondary, fontSize = 13.sp)
            Text(stringResource(R.string.no_e2ee), color = Tg.secondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Tg.bg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Tg.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Tg.text, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text(stringResource(R.string.search), color = Tg.secondary, fontSize = 16.sp)
                            inner()
                        }
                    },
                )
            }
        }
        state.errorCode?.let { code ->
            Text(
                stringResource(chatError(code)),
                color = Tg.text,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().background(Tg.danger.copy(alpha = 0.85f)).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (query.isBlank()) R.string.empty_chats else R.string.nothing_found),
                    color = Tg.secondary,
                    fontSize = 16.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { conversation ->
                    ChatRowItem(conversation) {
                        viewModel.openConversation(
                            conversation.id,
                            conversation.peerDisplayName,
                            conversation.type,
                            conversation.myRole,
                            conversation.peerUsername,
                        )
                    }
                }
            }
        }
    }
    if (creating) {
        NewChatDialog(
            peer = peer,
            groupTitle = groupTitle,
            working = state.working,
            onPeer = { peer = it },
            onGroup = { groupTitle = it },
            onOpen = {
                viewModel.openPeer(peer)
                creating = false
                peer = ""
            },
            onCreate = {
                viewModel.createGroup(groupTitle)
                creating = false
                groupTitle = ""
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun ChatRowItem(conversation: ConversationEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(54.dp).clip(CircleShape).background(Tg.avatar(conversation.id)),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(conversation.peerDisplayName), color = Tg.text, fontWeight = FontWeight.Medium, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.peerDisplayName.ifBlank { conversation.peerUsername },
                    color = Tg.text,
                    fontSize = 16.sp,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatListTime(conversation.updatedAt),
                    color = if (conversation.unreadCount > 0) Tg.accentBright else Tg.secondary,
                    fontSize = 12.sp,
                )
            }
            val preview = conversation.preview.takeUnless { it.isBlank() || it == "null" }.orEmpty()
                .ifBlank { if (conversation.lastEncrypted) stringResource(R.string.encrypted_message) else "" }
            if (preview.isNotBlank() || conversation.unreadCount > 0) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        preview,
                        color = if (conversation.unreadCount > 0) Tg.text else Tg.secondary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (conversation.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        UnreadBadge(conversation.unreadCount)
                    }
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier = Modifier.height(22.dp).clip(CircleShape).background(Tg.unread).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Tg.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun NewChatDialog(
    peer: String,
    groupTitle: String,
    working: Boolean,
    onPeer: (String) -> Unit,
    onGroup: (String) -> Unit,
    onOpen: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tg.bars,
        title = { Text(stringResource(R.string.new_chat), color = Tg.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogField(peer, stringResource(R.string.peer_username), onPeer)
                TextButton(onClick = onOpen, enabled = !working && peer.isNotBlank()) {
                    Text(stringResource(R.string.open_chat), color = Tg.accentBright)
                }
                DialogField(groupTitle, stringResource(R.string.group_title), onGroup)
                TextButton(onClick = onCreate, enabled = !working && groupTitle.isNotBlank()) {
                    Text(stringResource(R.string.create_group), color = Tg.accentBright)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.back_to_chats), color = Tg.secondary) }
        },
    )
}

@Composable
private fun DialogField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = fieldColors(),
    )
}

@Composable
private fun Conversation(viewModel: ChatViewModel, myUsername: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var memberName by rememberSaveable { mutableStateOf("") }
    var menu by rememberSaveable { mutableStateOf(false) }
    var action by remember { mutableStateOf<MessageEntity?>(null) }
    var reply by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val rows = rowsOf(state.messages)
    val pickFile = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri != null) viewModel.sendPicked(uri)
    }
    val askMic = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) viewModel.startRecording() else viewModel.showError("mic_denied")
    }
    val askCall = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val audio = granted[Manifest.permission.RECORD_AUDIO] == true
        val wantsVideo = granted.containsKey(Manifest.permission.CAMERA)
        val camera = !wantsVideo || granted[Manifest.permission.CAMERA] == true
        if (audio && camera) viewModel.beginCall(if (wantsVideo) "video" else "audio")
        else viewModel.showError("mic_denied")
    }
    var keys by remember { mutableStateOf(false) }
    LaunchedEffect(state.downloadUrl) {
        val url = state.downloadUrl ?: return@LaunchedEffect
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        viewModel.consumeDownload()
    }
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }
    Column(Modifier.fillMaxSize().background(Tg.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Tg.bars).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::closeConversation) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_chats), tint = Tg.text)
            }
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(Tg.avatar(state.openId ?: state.peerName)),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials(state.peerName), color = Tg.text, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.peerName, color = Tg.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when {
                    state.openType == "group" -> stringResource(R.string.group_subtitle)
                    state.peerOnline -> stringResource(R.string.peer_online)
                    state.peerLastSeenAt.isNotBlank() -> stringResource(R.string.peer_last_seen, formatListTime(state.peerLastSeenAt))
                    else -> state.peerUsername
                }
                if (subtitle.isNotBlank()) Text(subtitle, color = if (state.peerOnline) Tg.accentBright else Tg.secondary, fontSize = 13.sp, maxLines = 1)
            }
            if (state.openType == "direct") {
                IconButton(onClick = {
                    askCall.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }) {
                    Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.call_audio), tint = Tg.accentBright)
                }
                IconButton(onClick = {
                    askCall.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                }) {
                    Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.call_video), tint = Tg.accentBright)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = Tg.secondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Tg.bars) {
                    if (state.openType == "direct") {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.keys_title), color = Tg.text) },
                            onClick = {
                                menu = false
                                keys = true
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(if (state.peerBlocked) R.string.unblock_person else R.string.block_person),
                                    color = Tg.text,
                                )
                            },
                            onClick = {
                                menu = false
                                viewModel.blockOpenPeer()
                            },
                        )
                    }
                    if (state.openType == "group") {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.leave_group), color = Tg.text) },
                            onClick = {
                                menu = false
                                viewModel.leaveOpenGroup()
                            },
                        )
                    }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            itemsIndexed(rows, key = { index, row ->
                when (row) {
                    is ChatRow.Day -> "day-$index"
                    is ChatRow.Bubble -> row.message.clientMessageId.ifBlank { "m-$index" }
                }
            }) { _, row ->
                when (row) {
                    is ChatRow.Day -> DayChip(row.label)
                    is ChatRow.Bubble -> MessageBubble(
                        message = row.message,
                        mine = row.message.senderUsername.equals(myUsername, ignoreCase = true),
                        read = state.openType == "direct" &&
                            row.message.senderUsername.equals(myUsername, ignoreCase = true) &&
                            (row.message.seq ?: 0) > 0 &&
                            (row.message.seq ?: 0) <= state.peerLastReadSeq,
                        onAttachment = { viewModel.openAttachment(row.message.attachmentId) },
                        onAction = { action = it },
                    )
                }
            }
        }
        if (state.openType == "group" && (state.openRole == "owner" || state.openRole == "admin")) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Tg.bars).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = memberName,
                    onValueChange = { memberName = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.add_member_name)) },
                    singleLine = true,
                    colors = fieldColors(),
                )
                TextButton(
                    onClick = {
                        viewModel.addMember(memberName)
                        memberName = ""
                    },
                    enabled = !state.working && memberName.isNotBlank(),
                ) { Text(stringResource(R.string.add_member), color = Tg.accentBright) }
                TextButton(
                    onClick = {
                        viewModel.removeMember(memberName)
                        memberName = ""
                    },
                    enabled = !state.working && memberName.isNotBlank(),
                ) { Text(stringResource(R.string.remove_member), color = Tg.danger) }
            }
        }
        state.errorCode?.let { code ->
            Text(stringResource(chatError(code)), color = Tg.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Composer(
            draft = draft,
            recording = state.recording,
            enabled = !state.working,
            quoteTitle = when {
                editing != null -> stringResource(R.string.editing_message)
                reply != null -> reply?.senderUsername.orEmpty()
                else -> null
            },
            quoteBody = if (editing != null) null else reply?.previewLine(),
            onClearQuote = {
                editing = null
                reply = null
            },
            onDraft = { draft = it },
            onSend = {
                val target = editing
                val quoted = reply
                if (target?.seq != null && target.seq > 0) {
                    viewModel.editMessage(target.seq, draft)
                } else {
                    viewModel.send(draft, quoted?.seq?.takeIf { it > 0 })
                }
                draft = ""
                editing = null
                reply = null
            },
            onAttach = { pickFile.launch("*/*") },
            onMic = {
                if (state.recording) {
                    viewModel.finishRecording()
                } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startRecording()
                } else {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
    }
    action?.let { message ->
        val mine = message.senderUsername.equals(myUsername, ignoreCase = true)
        val seq = message.seq
        val canEdit = mine && message.kind == "text" && message.attachmentId.isBlank() && seq != null && seq > 0
        AlertDialog(
            onDismissRequest = { action = null },
            containerColor = Tg.bars,
            title = { Text(stringResource(R.string.message_actions), color = Tg.text) },
            text = {
                Column {
                    if (seq != null && seq > 0) {
                        TextButton(onClick = {
                            reply = message
                            editing = null
                            action = null
                        }) { Text(stringResource(R.string.reply_message), color = Tg.accentBright) }
                    }
                    if (canEdit) {
                        TextButton(onClick = {
                            editing = message
                            reply = null
                            draft = message.text
                            action = null
                        }) { Text(stringResource(R.string.edit_message), color = Tg.text) }
                    }
                    if (mine && seq != null && seq > 0) {
                        TextButton(onClick = {
                            pendingDelete = seq
                            action = null
                        }) { Text(stringResource(R.string.delete_message), color = Tg.danger) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { action = null }) { Text(stringResource(R.string.back_to_chats), color = Tg.secondary) }
            },
        )
    }
    pendingDelete?.let { seq ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Tg.bars,
            title = { Text(stringResource(R.string.delete_message), color = Tg.text) },
            text = { Text(stringResource(R.string.delete_message_body), color = Tg.secondary) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.deleteMessage(seq)
                }) { Text(stringResource(R.string.delete_message), color = Tg.danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.back_to_chats), color = Tg.secondary) }
            },
        )
    }
    if (keys) {
        AlertDialog(
            onDismissRequest = { keys = false },
            containerColor = Tg.bars,
            title = { Text(stringResource(R.string.keys_title), color = Tg.text) },
            text = {
                Text(
                    stringResource(R.string.keys_body, viewModel.myFingerprint(), viewModel.peerFingerprint()),
                    color = Tg.secondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { keys = false }) { Text(stringResource(R.string.back_to_chats), color = Tg.secondary) }
            },
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    recording: Boolean,
    enabled: Boolean,
    quoteTitle: String?,
    quoteBody: String?,
    onClearQuote: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Tg.bars)) {
        if (!quoteTitle.isNullOrBlank() || !quoteBody.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (!quoteTitle.isNullOrBlank()) {
                        Text(quoteTitle, color = Tg.accentBright, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!quoteBody.isNullOrBlank()) {
                        Text(quoteBody, color = Tg.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onClearQuote) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.back_to_chats), tint = Tg.secondary)
                }
            }
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onAttach, enabled = enabled && !recording) {
            Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.attach_file), tint = Tg.secondary)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp, max = 140.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Tg.input)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (recording) {
                Text(stringResource(R.string.recording_now), color = Tg.danger, fontSize = 16.sp)
            } else {
                if (draft.isEmpty()) Text(stringResource(R.string.message_text), color = Tg.secondary, fontSize = 16.sp)
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    textStyle = TextStyle(color = Tg.text, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (draft.isNotBlank() && !recording) {
            IconButton(onClick = onSend, enabled = enabled) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send_message), tint = Tg.accentBright)
            }
        } else {
            IconButton(onClick = onMic, enabled = enabled) {
                Icon(
                    if (recording) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                    contentDescription = stringResource(if (recording) R.string.stop_recording else R.string.record_voice),
                    tint = if (recording) Tg.danger else Tg.secondary,
                )
            }
        }
    }
    }
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = Tg.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Tg.bars).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    mine: Boolean,
    read: Boolean,
    onAttachment: () -> Unit,
    onAction: (MessageEntity) -> Unit,
) {
    val attachment = message.attachmentName.takeUnless { it.isBlank() || it == "null" }.orEmpty()
    val bodyText = message.text.takeUnless { it == "null" }.orEmpty()
    val body = when {
        message.kind == "voice" && attachment.isNotBlank() -> attachment
        attachment.isNotBlank() && bodyText != attachment -> "$attachment\n$bodyText"
        attachment.isNotBlank() -> attachment
        else -> bodyText
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(
            modifier = Modifier
                .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (mine) 16.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 16.dp,
                    ),
                )
                .background(if (mine) Tg.outgoing else Tg.incoming)
                .combinedClickable(
                    onClick = { if (message.attachmentId.isNotBlank()) onAttachment() },
                    onLongClick = {
                        val seq = message.seq
                        if (seq != null && seq > 0) onAction(message)
                    },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (!mine && message.senderUsername.isNotBlank()) {
                Text(message.senderUsername, color = Tg.accentBright, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            if (message.replyToText.isNotBlank() || message.replyToUsername.isNotBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Tg.bg)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    if (message.replyToUsername.isNotBlank()) {
                        Text(message.replyToUsername, color = Tg.accentBright, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (message.replyToText.isNotBlank()) {
                        Text(message.replyToText, color = Tg.secondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (body.isNotBlank()) Text(body, color = Tg.text, fontSize = 16.sp)
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (message.edited) {
                    Text(stringResource(R.string.edited_mark), color = Tg.secondary, fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(formatBubbleTime(message.createdAt), color = Tg.secondary, fontSize = 11.sp)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    Text(if (read) "✓✓" else "✓", color = if (read) Tg.accentBright else Tg.secondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Tg.text,
    unfocusedTextColor = Tg.text,
    focusedBorderColor = Tg.accent,
    unfocusedBorderColor = Tg.input,
    focusedLabelColor = Tg.accentBright,
    unfocusedLabelColor = Tg.secondary,
    cursorColor = Tg.accentBright,
)

private fun rowsOf(messages: List<MessageEntity>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    messages.forEachIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)?.createdAt
        if (index == 0 || !sameDay(previous, message.createdAt)) {
            val label = formatDayLabel(message.createdAt)
            if (label.isNotBlank()) rows += ChatRow.Day(label)
        }
        rows += ChatRow.Bubble(message)
    }
    return rows
}

private fun MessageEntity.previewLine(): String {
    val attachment = attachmentName.takeUnless { it.isBlank() || it == "null" }.orEmpty()
    val body = text.takeUnless { it == "null" }.orEmpty()
    val value = if (attachment.isNotBlank()) attachment else body
    return value.lineSequence().firstOrNull().orEmpty()
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
    "peer_key_missing" -> R.string.error_peer_key
    "mic_failed" -> R.string.error_mic_failed
    "owner_must_stay" -> R.string.error_owner_stays
    "cannot_remove_self" -> R.string.error_remove_self
    "not_message_author" -> R.string.error_not_author
    "reply_not_found" -> R.string.error_reply_missing
    "not_editable" -> R.string.error_not_editable
    "message_not_found" -> R.string.error_generic
    "network" -> R.string.error_network
    else -> R.string.error_generic
}
