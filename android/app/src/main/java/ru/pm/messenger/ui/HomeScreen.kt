package ru.pm.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.pm.messenger.AccountState
import ru.pm.messenger.AccountViewModel
import ru.pm.messenger.BuildConfig
import ru.pm.messenger.ChatViewModel
import ru.pm.messenger.ConnectionViewModel
import ru.pm.messenger.R

@Composable
fun HomeScreen(
    connectionViewModel: ConnectionViewModel,
    accountViewModel: AccountViewModel,
    chatViewModel: ChatViewModel,
) {
    var baseUrl by rememberSaveable { mutableStateOf(BuildConfig.API_BASE_URL) }
    var wasSignedIn by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val signedIn = accountState as? AccountState.SignedIn
    LaunchedEffect(accountState) {
        val active = accountState is AccountState.SignedIn
        if (active) {
            wasSignedIn = true
            chatViewModel.start()
        } else if (wasSignedIn && accountState is AccountState.SignedOut) {
            wasSignedIn = false
            settings = false
            chatViewModel.stop()
        }
    }
    BackHandler(enabled = signedIn != null && (settings || chatState.openId != null)) {
        if (settings) settings = false else chatViewModel.closeConversation()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Tg.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        when {
            accountState is AccountState.Working && signedIn == null && !wasSignedIn -> {
                CircularProgressIndicator(color = Tg.accent, modifier = Modifier.align(Alignment.Center))
            }
            signedIn != null && settings -> {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    IconButton(onClick = { settings = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_chats), tint = Tg.text)
                    }
                    Text(
                        stringResource(R.string.settings),
                        color = Tg.text,
                        fontSize = 22.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                        ConnectionScreen(connectionViewModel, baseUrl) { baseUrl = it }
                        AccountScreen(accountViewModel, baseUrl) { baseUrl = it }
                    }
                }
            }
            signedIn != null -> ChatScreen(
                viewModel = chatViewModel,
                myUsername = signedIn.username,
                accountName = signedIn.displayName,
                onOpenSettings = { settings = true },
            )
            else -> AccountScreen(accountViewModel, baseUrl) { baseUrl = it }
        }
    }
}
