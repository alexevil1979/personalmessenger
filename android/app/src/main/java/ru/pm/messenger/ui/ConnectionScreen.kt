package ru.pm.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.pm.messenger.ConnectionState
import ru.pm.messenger.ConnectionViewModel
import ru.pm.messenger.R

@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = stringResource(R.string.connection_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = stringResource(R.string.connection_body), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.server_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Button(
            onClick = { viewModel.check(baseUrl) },
            enabled = state !is ConnectionState.Loading && baseUrl.isNotBlank(),
        ) {
            Text(stringResource(R.string.check_connection))
        }
        when (val current = state) {
            ConnectionState.Idle -> Unit
            ConnectionState.Loading -> CircularProgressIndicator()
            is ConnectionState.Success -> {
                Text(stringResource(R.string.status_label, current.snapshot.status, current.snapshot.version))
                Text(stringResource(R.string.component_postgres, current.snapshot.postgres))
                Text(stringResource(R.string.component_redis, current.snapshot.redis))
            }
            ConnectionState.Error -> Text(
                text = stringResource(R.string.connection_error),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
