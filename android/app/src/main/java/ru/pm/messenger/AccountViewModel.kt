package ru.pm.messenger

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.pm.messenger.data.Account
import ru.pm.messenger.data.AuthApi
import ru.pm.messenger.data.AuthCallException
import ru.pm.messenger.data.SessionStore
import javax.inject.Inject

sealed interface AccountState {
    data object SignedOut : AccountState
    data object Working : AccountState
    data class SignedIn(
        val username: String,
        val displayName: String,
        val role: String?,
    ) : AccountState
    data class Failed(val code: String) : AccountState
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow<AccountState>(AccountState.SignedOut)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        sessionStore.read()?.let { saved -> restore(saved) }
    }

    fun login(baseUrl: String, username: String, password: String) {
        val normalized = username.trim().lowercase()
        if (!normalized.matches(USERNAME)) {
            _state.value = AccountState.Failed("validation_error")
            return
        }
        submit {
            val account = authApi.login(baseUrl, normalized, password, deviceName())
            sessionStore.write(account)
            account.signedIn()
        }
    }

    fun register(
        baseUrl: String,
        username: String,
        displayName: String,
        password: String,
        invitationCode: String,
        privacyAccepted: Boolean,
    ) {
        val normalized = username.trim().lowercase()
        val name = displayName.trim()
        val code = invitationCode.trim()
        val failure = when {
            !privacyAccepted -> "privacy_required"
            !normalized.matches(USERNAME) || name.isEmpty() || name.length > 80 -> "validation_error"
            password.length < 12 || password.equals(normalized, ignoreCase = true) -> "weak_password"
            code.isEmpty() -> "invitation_invalid"
            else -> null
        }
        if (failure != null) {
            _state.value = AccountState.Failed(failure)
            return
        }
        submit {
            val account = authApi.register(baseUrl, normalized, name, password, code, deviceName())
            sessionStore.write(account)
            account.signedIn()
        }
    }

    fun logout() {
        val saved = sessionStore.read()
        _state.value = AccountState.Working
        viewModelScope.launch(Dispatchers.IO) {
            if (saved != null) {
                runCatching { authApi.logout(saved) }
            }
            sessionStore.clear()
            _state.value = AccountState.SignedOut
        }
    }

    private fun restore(saved: Account) {
        _state.value = AccountState.Working
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                authApi.me(saved).let {
                    AccountState.SignedIn(it.username, it.displayName, it.role)
                }
            } catch (error: AuthCallException) {
                if (error.status == 401) refreshOrSignOut(saved) else AccountState.Failed(error.code)
            } catch (_: Exception) {
                AccountState.Failed("network")
            }
        }
    }

    private fun refreshOrSignOut(saved: Account): AccountState =
        try {
            val rotated = authApi.refresh(saved)
            sessionStore.write(rotated)
            val profile = authApi.me(rotated)
            AccountState.SignedIn(profile.username, profile.displayName, profile.role)
        } catch (_: Exception) {
            sessionStore.clear()
            AccountState.SignedOut
        }

    private fun submit(block: () -> AccountState) {
        if (_state.value is AccountState.Working) return
        _state.value = AccountState.Working
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                block()
            } catch (error: AuthCallException) {
                AccountState.Failed(error.code)
            } catch (_: Exception) {
                AccountState.Failed("network")
            }
        }
    }

    private fun Account.signedIn() = AccountState.SignedIn(username, displayName, role)

    private fun deviceName(): String {
        val raw = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .joinToString(" ")
        return raw.ifBlank { "android" }.take(80)
    }

    private companion object {
        val USERNAME = Regex("^[a-z0-9_]{3,32}$")
    }
}
