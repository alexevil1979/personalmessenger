package ru.pm.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import ru.pm.messenger.ui.HomeScreen
import ru.pm.messenger.ui.MessengerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val connectionViewModel: ConnectionViewModel by viewModels()
    private val accountViewModel: AccountViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF17212B.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF17212B.toInt()),
        )
        setContent {
            MessengerTheme {
                HomeScreen(connectionViewModel, accountViewModel, chatViewModel)
            }
        }
    }
}
