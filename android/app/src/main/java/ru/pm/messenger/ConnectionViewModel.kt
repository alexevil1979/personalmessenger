package ru.pm.messenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.pm.messenger.data.HealthApi
import ru.pm.messenger.data.HealthSnapshot
import javax.inject.Inject

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Loading : ConnectionState
    data class Success(val snapshot: HealthSnapshot) : ConnectionState
    data object Error : ConnectionState
}

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val healthApi: HealthApi,
) : ViewModel() {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun check(baseUrl: String) {
        if (_state.value is ConnectionState.Loading) return
        _state.value = ConnectionState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                ConnectionState.Success(healthApi.fetch(baseUrl))
            } catch (_: Exception) {
                ConnectionState.Error
            }
        }
    }
}
