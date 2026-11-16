package com.example.simondicevibe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.example.simondicevibe.AppContainer
import com.example.simondicevibe.SimonApp
import com.example.simondicevibe.data.history.HistoryRepository
import com.example.simondicevibe.data.history.HistorySnapshot
import com.example.simondicevibe.data.mqtt.BrokerStatus
import com.example.simondicevibe.data.mqtt.MqttGameClient
import com.example.simondicevibe.domain.EspMessage
import com.example.simondicevibe.domain.GameState
import com.example.simondicevibe.domain.Phase
import com.example.simondicevibe.domain.Banner
import com.example.simondicevibe.domain.LitSource
import com.example.simondicevibe.domain.SessionStore
import com.example.simondicevibe.domain.estimatePlaybackMs
import com.example.simondicevibe.domain.reduce
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONException

// ───────────────────────────── Home ─────────────────────────────

data class HomeUiState(val broker: BrokerStatus, val espOnline: Boolean)

class HomeViewModel(mqtt: MqttGameClient) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(mqtt.brokerStatus, mqtt.espOnline) { b, e -> HomeUiState(b, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(BrokerStatus.CONNECTING, false))

    init {
        mqtt.connect()
    }
}

// ───────────────────────────── Game ─────────────────────────────

/**
 * Mirrors the ESP32 live. Every MQTT message goes through the pure [reduce] function; this class only
 * adds the time-based parts: clearing a lit pad after its pulse, and (when the optional live telemetry
 * topic is not available) estimating when the ESP32 finishes replaying the sequence.
 */
class GameViewModel(private val mqtt: MqttGameClient, private val session: SessionStore) : ViewModel() {

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    private var estimateJob: Job? = null

    init {
        session.lastResult = null
        viewModelScope.launch {
            mqtt.messages.collect(::onMessage)
        }
        mqtt.publishStart()
    }

    private fun onMessage(msg: EspMessage) {
        val before = _state.value
        val after = before.reduce(msg)
        _state.value = after

        if (msg is EspMessage.LiveStep || msg is EspMessage.YourTurn || msg is EspMessage.GameOver ||
            msg is EspMessage.LevelCleared || (msg is EspMessage.Touch && msg.correct)
        ) {
            estimateJob?.cancel()
        }

        // Pulse duration: ESP32 lights each sequence element for timeout/4, player feedback lasts 200 ms.
        after.lit?.takeIf { it != before.lit }?.let { lit ->
            val pulse = if (lit.source == LitSource.ESP_SEQUENCE) (after.timeoutMs / 4L).coerceAtLeast(150L) else 260L
            viewModelScope.launch {
                delay(pulse)
                _state.update { if (it.lit?.token == lit.token) it.copy(lit = null) else it }
            }
        }

        when (msg) {
            is EspMessage.Level -> estimateTurn(extraMs = 0L)
            is EspMessage.Touch -> if (!msg.correct && msg.lives > 0) estimateTurn(extraMs = 200L + 600L)
            is EspMessage.Timeout -> if (msg.lives > 0) estimateTurn(extraMs = 600L)
            is EspMessage.GameOver -> session.lastResult = after.result
            else -> Unit
        }
    }

    /** Fallback when no live telemetry is present: open the countdown once the replay should be over. */
    private fun estimateTurn(extraMs: Long) {
        estimateJob?.cancel()
        if (_state.value.liveTelemetry) return
        val s = _state.value
        val wait = extraMs + estimatePlaybackMs(s.sequenceLength, s.timeoutMs)
        estimateJob = viewModelScope.launch {
            delay(wait)
            _state.update {
                if (it.phase == Phase.WATCHING && !it.liveTelemetry) {
                    it.copy(phase = Phase.YOUR_TURN, banner = Banner.TURN, turnToken = it.turnToken + 1)
                } else it
            }
        }
    }
}

// ───────────────────────────── History ─────────────────────────────

data class HistoryUiState(
    val server: String,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val snapshot: HistorySnapshot? = null,
    val error: String? = null,
)

class HistoryViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState(server = repository.server))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    fun onServerChange(text: String) = _state.update { it.copy(server = text) }

    fun refresh(pull: Boolean = false) {
        val server = HistoryRepository.normalizeServer(_state.value.server)
        if (server.isEmpty()) {
            _state.update { it.copy(loading = false, refreshing = false, error = "Escribí la IP de la PC donde corre Node-RED") }
            return
        }
        repository.server = server
        _state.update {
            it.copy(server = server, loading = !pull && it.snapshot == null, refreshing = pull, error = null)
        }
        viewModelScope.launch {
            runCatching { repository.fetch(server) }
                .onSuccess { snap -> _state.update { it.copy(loading = false, refreshing = false, snapshot = snap) } }
                .onFailure { e ->
                    val detail = if (e is JSONException) "la respuesta no es el historial" else e.message
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            snapshot = null,
                            error = "No se pudo consultar $server ($detail). ¿Node-RED está corriendo y el celular está en la misma WiFi?",
                        )
                    }
                }
        }
    }

    fun permissionDenied() = _state.update {
        it.copy(loading = false, refreshing = false, error = "Sin el permiso de red local la app no puede hablar con Node-RED en tu PC")
    }
}

// ───────────────────────────── Factories ─────────────────────────────

object Factories {
    val home = viewModelFactory { initializer { HomeViewModel(container().mqtt) } }
    val game = viewModelFactory { initializer { container().let { GameViewModel(it.mqtt, it.session) } } }
    val history = viewModelFactory { initializer { HistoryViewModel(container().history) } }

    private fun androidx.lifecycle.viewmodel.CreationExtras.container(): AppContainer =
        (this[APPLICATION_KEY] as SimonApp).container
}
