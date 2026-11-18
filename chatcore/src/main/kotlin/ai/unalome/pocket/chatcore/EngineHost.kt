package ai.unalome.pocket.chatcore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the engine in use and its load state; loads it on first use. */
class EngineHost(val engine: ChatEngine) {
    sealed interface State {
        data object Unloaded : State
        data class Loading(val progress: Double) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()

    suspend fun ensureLoaded() = mutex.withLock {
        if (_state.value == State.Ready) return@withLock
        _state.value = State.Loading(0.0)
        try {
            engine.load { progress -> _state.value = State.Loading(progress) }
            _state.value = State.Ready
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "The model could not be loaded.")
            throw e
        }
    }

    suspend fun unload() = mutex.withLock {
        engine.unload()
        _state.value = State.Unloaded
    }
}
