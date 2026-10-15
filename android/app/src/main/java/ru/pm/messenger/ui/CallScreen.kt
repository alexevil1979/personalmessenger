package ru.pm.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer
import ru.pm.messenger.ChatViewModel
import ru.pm.messenger.R

@Composable
fun CallScreen(viewModel: ChatViewModel, phase: String, kind: String, peer: String) {
    val context = LocalContext.current
    val remote = remember { SurfaceViewRenderer(context) }
    val local = remember { SurfaceViewRenderer(context) }
    val title = when (phase) {
        "incoming" -> stringResource(R.string.call_incoming)
        "outgoing" -> stringResource(R.string.call_outgoing)
        else -> stringResource(if (kind == "video") R.string.call_video else R.string.call_audio)
    }
    Box(Modifier.fillMaxSize().background(Tg.bg)) {
        if (kind == "video" && phase != "incoming") {
            AndroidView(factory = { remote }, modifier = Modifier.fillMaxSize())
            AndroidView(
                factory = { local },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(width = 120.dp, height = 180.dp),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(peer, color = Tg.text, fontSize = 28.sp)
            Text(title, color = Tg.secondary, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
            Text(stringResource(R.string.call_direct), color = Tg.secondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                if (phase == "incoming") {
                    IconButton(
                        onClick = viewModel::acceptCall,
                        modifier = Modifier.size(72.dp).background(Tg.accent, CircleShape),
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.call_accept), tint = Tg.text)
                    }
                }
                IconButton(
                    onClick = viewModel::hangup,
                    modifier = Modifier.size(72.dp).background(Tg.danger, CircleShape),
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.call_hangup), tint = Tg.text)
                }
            }
        }
    }
    LaunchedEffect(phase, kind) {
        if (kind == "video" && phase != "incoming") viewModel.bindCallRenderers(local, remote)
    }
}
