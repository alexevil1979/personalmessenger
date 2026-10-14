package ru.pm.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.pm.messenger.AccountState
import ru.pm.messenger.AccountViewModel
import ru.pm.messenger.R

@Composable
fun AccountScreen(viewModel: AccountViewModel, baseUrl: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var registerMode by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var invitation by rememberSaveable { mutableStateOf("") }
    var privacyAccepted by rememberSaveable { mutableStateOf(false) }
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(R.string.account_title), style = MaterialTheme.typography.headlineMedium)
        when (val current = state) {
            is AccountState.SignedIn -> {
                Text(stringResource(R.string.signed_in, current.displayName, current.username))
                Text(
                    text = if (current.role == null) {
                        stringResource(R.string.role_none)
                    } else {
                        stringResource(R.string.role_label, current.role)
                    },
                )
                Text(text = stringResource(R.string.no_e2ee), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.current_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.new_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = {
                        viewModel.changePassword(currentPassword, newPassword)
                        currentPassword = ""
                        newPassword = ""
                    },
                    enabled = currentPassword.isNotBlank() && newPassword.isNotBlank(),
                ) {
                    Text(stringResource(R.string.change_password))
                }
                current.notice?.let { notice ->
                    Text(text = stringResource(messageFor(notice)), color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = viewModel::logout, enabled = true) {
                    Text(stringResource(R.string.logout))
                }
            }
            else -> {
                Text(text = stringResource(R.string.account_body), style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                )
                if (registerMode) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.display_name)) },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (registerMode) {
                    OutlinedTextField(
                        value = invitation,
                        onValueChange = { invitation = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.invitation_code)) },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = privacyAccepted, onCheckedChange = { privacyAccepted = it })
                        Text(stringResource(R.string.privacy_accept))
                    }
                }
                if (state is AccountState.Working) {
                    CircularProgressIndicator()
                } else {
                    Button(
                        onClick = {
                            if (registerMode) {
                                viewModel.register(baseUrl, username, displayName, password, invitation, privacyAccepted)
                            } else {
                                viewModel.login(baseUrl, username, password)
                            }
                            password = ""
                        },
                        enabled = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    ) {
                        Text(stringResource(if (registerMode) R.string.register else R.string.login))
                    }
                }
                if (state is AccountState.Failed) {
                    Text(
                        text = stringResource(messageFor((state as AccountState.Failed).code)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { registerMode = !registerMode }) {
                    Text(stringResource(if (registerMode) R.string.have_account else R.string.need_account))
                }
            }
        }
    }
}

private fun messageFor(code: String): Int = when (code) {
    "privacy_required" -> R.string.error_privacy
    "weak_password" -> R.string.error_password
    "invitation_invalid" -> R.string.error_invitation
    "username_taken" -> R.string.error_username_taken
    "invalid_credentials" -> R.string.error_credentials
    "account_blocked" -> R.string.error_blocked
    "password_changed" -> R.string.password_changed
    "rate_limited" -> R.string.error_rate_limit
    "validation_error" -> R.string.error_validation
    "network" -> R.string.error_network
    else -> R.string.error_generic
}
