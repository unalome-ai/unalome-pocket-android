package ai.unalome.pocket

import ai.unalome.pocket.chatcore.AttachmentStore
import ai.unalome.pocket.chatcore.ConversationStore
import ai.unalome.pocket.chatcore.PersonaStore
import ai.unalome.pocket.chatcore.RoomPersonaRepository
import ai.unalome.pocket.chatcore.EngineHost
import ai.unalome.pocket.chatcore.NoModelEngine
import ai.unalome.pocket.chatcore.RoomConversationRepository
import ai.unalome.pocket.chatcore.ScriptedEngine
import ai.unalome.pocket.chatcore.data.PocketDatabase
import ai.unalome.pocket.features.AppServices
import ai.unalome.pocket.features.models.EngineFactory
import ai.unalome.pocket.features.models.ModelController
import ai.unalome.pocket.llamaengine.LlamaConfig
import ai.unalome.pocket.llamaengine.LlamaEngine
import ai.unalome.pocket.modelcatalog.AndroidDevice
import ai.unalome.pocket.modelcatalog.Catalog
import ai.unalome.pocket.modelcatalog.InstalledModelStore
import ai.unalome.pocket.modelcatalog.ModelDownloadService
import ai.unalome.pocket.modelcatalog.ModelDownloader
import ai.unalome.pocket.modelcatalog.ModelLibrary
import ai.unalome.pocket.modelcatalog.ModelLibraryOwner
import ai.unalome.pocket.modelcatalog.ModelStorage
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Builds the app's services once: the chat store, the model library and the engine wiring. */
class PocketApplication : Application(), ModelLibraryOwner {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val modelLibrary: ModelLibrary by lazy {
        val root = File(noBackupFilesDir, "models")
        val storage = ModelStorage(root)
        val catalog = Catalog.parse(assets.open("catalog.json").bufferedReader().use { it.readText() })
        ModelLibrary(
            catalog = catalog,
            storage = storage,
            store = InstalledModelStore(File(root, "installed.json")),
            downloader = ModelDownloader(storage, freeSpace = { AndroidDevice.freeStorageBytes(root) }),
            readDevice = { AndroidDevice.profile(this, root) },
            scope = appScope,
        ).also { library -> library.onDownloadStarted = { ModelDownloadService.start(this) } }
    }

    private val servicesLazy = lazy {
        val database = PocketDatabase.open(this)
        val library = modelLibrary
        val config = LlamaConfig.forDevice(library.deviceAtLaunch.totalMemoryBytes)
        val storage = ModelStorage(File(noBackupFilesDir, "models"))
        val factory = EngineFactory { entry ->
            LlamaEngine(
                modelFile = storage.modelFile(entry),
                displayName = entry.displayName,
                config = config,
                modelSupportsThinking = entry.supportsThinking,
                nativeLibraryDir = applicationInfo.nativeLibraryDir,
                projectorFile = storage.projectorFile(entry),
                availableMemoryBytes = ::availableMemoryBytes,
            )
        }
        val personas = PersonaStore(RoomPersonaRepository(database))
        appScope.launch { personas.ensureStarters() }
        // App-private and never backed up (backups are off), like the chats.
        val attachments = AttachmentStore(File(filesDir, "attachments"))
        AppServices(
            store = ConversationStore(RoomConversationRepository(database), attachments),
            models = ModelController(
                library = library,
                host = EngineHost(NoModelEngine()),
                factory = factory,
                scope = appScope,
                // The scripted demo is for UI tests in debug builds; release builds cannot reach it.
                scriptedEngine = if (BuildConfig.DEBUG) ({ ScriptedEngine() }) else null,
            ),
            appScope = appScope,
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "?",
            personas = personas,
            attachments = attachments,
        )
    }

    val services: AppServices by servicesLazy

    /**
     * In the background with memory short, the model (gigabytes) is the first thing to give back:
     * Android would otherwise end the app. It is loaded again on the next use. Android 14+ only
     * reports these two levels to apps.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION") // the level constants still name what Android reports
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && servicesLazy.isInitialized()) {
            appScope.launch { services.models.host.unload() }
        }
    }

    /** Memory the app can use before Android starts ending apps, for the image check. */
    private fun availableMemoryBytes(): Long? {
        val manager = getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return info.availMem - info.threshold
    }
}
