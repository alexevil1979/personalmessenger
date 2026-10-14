package ru.pm.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.pm.messenger.AccountState
import ru.pm.messenger.AccountViewModel
import ru.pm.messenger.BuildConfig
import ru.pm.messenger.ChatViewModel
import ru.pm.messenger.ConnectionViewModel

@Composable
fun HomeScreen(
    connectionViewModel: ConnectionViewModel,
    accountViewModel: AccountViewModel,
    chatViewModel: ChatViewModel,
) {
    var baseUrl by rememberSaveable { mutableStateOf(BuildConfig.API_BASE_URL) }
    var wasSignedIn by rememberSaveable { mutableStateOf(false) }
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(accountState) {
        val signedIn = accountState is AccountState.SignedIn
        if (signedIn) {
            wasSignedIn = true
            chatViewModel.start()
        } else if (wasSignedIn && accountState is AccountState.SignedOut) {
            wasSignedIn = false
            chatViewModel.stop()
        }
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionScreen(viewModel = connectionViewModel, baseUrl = baseUrl, onBaseUrlChange = { baseUrl = it })
            HorizontalDivider()
            AccountScreen(viewModel = accountViewModel, baseUrl = baseUrl)
            if (accountState is AccountState.SignedIn) {
                HorizontalDivider()
                ChatScreen(viewModel = chatViewModel)
            }
        }
    }
}
