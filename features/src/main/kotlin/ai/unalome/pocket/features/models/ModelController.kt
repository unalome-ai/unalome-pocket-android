package ai.unalome.pocket.features.models

import ai.unalome.pocket.chatcore.ChatEngine
import ai.unalome.pocket.chatcore.EngineHost
import ai.unalome.pocket.chatcore.NoModelEngine
import ai.unalome.pocket.modelcatalog.CatalogEntry
import ai.unalome.pocket.modelcatalog.ModelLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Builds the engine for an installed catalog model. Provided by the app, which owns the native engine. */
fun interface EngineFactory {
    fun create(entry: CatalogEntry): ChatEngine
}

/**
 * Keeps the [EngineHost]'s engine in step with the selected model: picking, deleting and
 * downloading a model swap the engine here, never in the screens.
 */
class ModelController(
    val library: ModelLibrary,
    val host: EngineHost,
    private val factory: EngineFactory,
    private val scope: CoroutineScope,
    /** Debug builds only: the scripted demo engine can be picked for UI tests. `null` in release. */
    private val scriptedEngine: (() -> ChatEngine)? = null,
) {
    /** What chats run on. */
    sealed interface Active {
        data object None : Active
        data class Model(val id: String) : Active
        data object Scripted : Active
    }

    private val _active = MutableStateFlow<Active>(Active.None)
    val active: StateFlow<Active> = _active.asStateFlow()

    val allowsScriptedEngine: Boolean get() = scriptedEngine != null

    private val switching = Mutex()
    private var useScripted = false

    init {
        scope.launch {
            library.selectedId.collectLatest { id -> apply(id) }
        }
    }

    /** Uses an installed model from the next reply on and starts loading it. */
    fun select(id: String) {
        useScripted = false
        library.select(id)
        scope.launch {
            apply(id)
            preload()
        }
    }

    /** A persona's default model: used if it is installed and not already in use. */
    fun selectIfInstalled(id: String) {
        if (library.installedModel(id) == null || _active.value == Active.Model(id)) return
        select(id)
    }

    fun selectScripted() {
        val make = scriptedEngine ?: return
        useScripted = true
        scope.launch {
            switching.withLock {
                host.switchTo(make())
                _active.value = Active.Scripted
            }
        }
    }

    /** Loads the engine in use ahead of the first message, so the loading progress shows early. */
    fun preload() {
        if (_active.value is Active.Model) {
            scope.launch { runCatching { host.ensureLoaded() } }
        }
    }

    /** Stores a finished reply's speed against the model in use. */
    fun recordMeasurement(tokensPerSecond: Double) {
        (_active.value as? Active.Model)?.let { library.recordMeasurement(it.id, tokensPerSecond) }
    }

    /** Deletes a model's files; if it is in use, the engine is unloaded first and another model takes over. */
    suspend fun delete(id: String) {
        switching.withLock {
            if ((_active.value as? Active.Model)?.id == id) {
                host.switchTo(NoModelEngine())
                _active.value = Active.None
            }
            library.delete(id)
        }
        apply(library.selectedId.value)
    }

    private suspend fun apply(id: String?) = switching.withLock {
        if (useScripted) return@withLock
        val wanted = id?.let { selected -> library.entry(selected)?.takeIf { library.installedModel(selected) != null } }
        val current = _active.value
        if (wanted == null) {
            if (current !is Active.None) {
                host.switchTo(NoModelEngine())
                _active.value = Active.None
            }
            return@withLock
        }
        if (current == Active.Model(wanted.id)) return@withLock
        host.switchTo(factory.create(wanted))
        _active.value = Active.Model(wanted.id)
    }
}
