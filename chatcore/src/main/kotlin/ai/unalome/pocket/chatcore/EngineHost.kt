package ai.unalome.pocket.chatcore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the engine in use and its load state; loads it on first use and swaps it on request. */
class EngineHost(initial: ChatEngine) {
    sealed interface State {
        data object Unloaded : State
        data class Loading(val progress: Double) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _engine = MutableStateFlow(initial)
    /** The engine in use; changes when the user picks another model. */
    val engineFlow: StateFlow<ChatEngine> = _engine.asStateFlow()
    val engine: ChatEngine get() = _engine.value

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()

    suspend fun ensureLoaded() = mutex.withLock {
        if (_state.value == State.Ready) return@withLock
        _state.value = State.Loading(0.0)
        try {
            engine.load { progress -> _state.value = State.Loading(progress) }
            _state.value = State.Ready
        } catch (e: CancellationException) {
            _state.value = State.Unloaded
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "The model could not be loaded.")
            throw e
        }
    }

    suspend fun unload() = mutex.withLock {
        engine.unload()
        _state.value = State.Unloaded
    }

    /** Unloads the current engine (stopping any reply) and makes [next] the one in use. */
    suspend fun switchTo(next: ChatEngine) = mutex.withLock {
        if (next === engine) return@withLock
        engine.unload()
        _engine.value = next
        _state.value = State.Unloaded
    }
}

/** Stands in while no model is installed: it cannot answer, and the UI offers a download instead. */
class NoModelEngine(override val displayName: String = "No model") : ChatEngine {
    override val capabilities = EngineCapabilities(supportsVision = false, maxContextTokens = 0)

    override suspend fun load(progress: (Double) -> Unit) = throw IllegalStateException(MESSAGE)

    override suspend fun unload() = Unit

    override fun reply(turn: ChatTurn, history: List<ChatTurn>, options: GenerationOptions): Flow<GenerationEvent> =
        flow { throw IllegalStateException(MESSAGE) }

    private companion object {
        const val MESSAGE = "No model is installed yet. Download one to start chatting."
    }
}
