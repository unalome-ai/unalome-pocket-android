package ai.unalome.pocket.modelcatalog

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Models screen's state: catalog, installed models, downloads, storage and the selection. */
class ModelLibrary(
    val catalog: Catalog,
    private val storage: ModelStorage,
    private val store: InstalledModelStore,
    private val downloader: ModelDownloader,
    private val readDevice: () -> DeviceProfile,
    /** Lives as long as the app; downloads run here, kept alive by [ModelDownloadService]. */
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface DownloadState {
        val progress: DownloadProgress?

        data class Downloading(override val progress: DownloadProgress?) : DownloadState
        /** Stopped by the user or the system; the bytes so far are kept. */
        data class Paused(override val progress: DownloadProgress?) : DownloadState
        data class Failed(val message: String, override val progress: DownloadProgress?) : DownloadState
    }

    sealed interface Event {
        data class Finished(val entry: CatalogEntry) : Event
        data class Failed(val entry: CatalogEntry, val message: String) : Event
    }

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private val _storageUsedBytes = MutableStateFlow(0L)
    val storageUsedBytes: StateFlow<Long> = _storageUsedBytes.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    /** The model chats use; `null` when none is installed. */
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Called when a download starts, so the app can keep itself running (foreground service). */
    var onDownloadStarted: () -> Unit = {}

    private val jobs = mutableMapOf<String, Job>()

    /** The device as read at launch, for the memory figures; storage is read fresh. */
    val deviceAtLaunch: DeviceProfile = readDevice()

    init {
        reload()
        // Downloads left unfinished by an earlier run show as paused, ready to resume.
        _downloads.value = catalog.models
            .filter { !storage.isComplete(it) && storage.partialBytes(it) > 0 }
            .associate { it.id to DownloadState.Paused(onDisk(it)) }
    }

    fun device(): DeviceProfile = readDevice().copy(totalMemoryBytes = deviceAtLaunch.totalMemoryBytes)

    fun entry(id: String): CatalogEntry? = catalog.entry(id)

    fun installedModel(id: String): InstalledModel? = _installed.value.firstOrNull { it.id == id }

    fun recommendation(): Recommendation = ModelRecommender.recommend(
        catalog = catalog.models,
        device = device(),
        installedIds = _installed.value.map { it.id }.toSet(),
        partialBytes = catalog.models.associate { it.id to storage.partialBytes(it) },
    )

    val isDownloading: Boolean get() = _downloads.value.values.any { it is DownloadState.Downloading }

    fun hasAcceptedLicense(id: String): Boolean = id in store.read().acceptedLicenses

    /**
     * Re-reads the records and the files. Records whose file is gone are dropped; a verified file
     * without a record (the app stopped right after the rename) gets one.
     */
    fun reload() {
        val snapshot = store.update { current ->
            val kept = current.models.filter { record -> catalog.entry(record.id)?.let(storage::isInstalled) == true }
            val missing = catalog.models
                .filter { entry -> storage.isInstalled(entry) && kept.none { it.id == entry.id } }
                .map { entry -> record(entry, current.acceptedLicenses[entry.id] ?: now()) }
            val models = kept + missing
            val selected = current.selectedId?.takeIf { id -> models.any { it.id == id } } ?: models.firstOrNull()?.id
            current.copy(models = models, selectedId = selected)
        }
        _installed.value = snapshot.models
        _selectedId.value = snapshot.selectedId
        _storageUsedBytes.value = storage.totalBytes()
    }

    /** Starts or resumes a download. The first call per model records that its licence was accepted. */
    fun download(entry: CatalogEntry) {
        synchronized(jobs) {
            if (jobs.containsKey(entry.id) || storage.isComplete(entry)) return
            store.update { snapshot ->
                if (entry.id in snapshot.acceptedLicenses) snapshot
                else snapshot.copy(acceptedLicenses = snapshot.acceptedLicenses + (entry.id to now()))
            }
            setState(entry.id, DownloadState.Downloading(_downloads.value[entry.id]?.progress))
            onDownloadStarted()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    var lastStorageRead = 0L
                    downloader.download(entry) { progress ->
                        setState(entry.id, DownloadState.Downloading(progress))
                        // The storage card follows the download, at most once a second.
                        val time = now()
                        if (time - lastStorageRead >= 1000) {
                            lastStorageRead = time
                            _storageUsedBytes.value = storage.totalBytes()
                        }
                    }
                    finish(entry)
                } catch (e: CancellationException) {
                    end(entry.id) { DownloadState.Paused(onDisk(entry)) }
                } catch (e: Exception) {
                    Log.w(TAG, "Download of ${entry.id} stopped: ${e.javaClass.simpleName}: ${e.message}")
                    val message = PlainErrorMessage.describe(e)
                    end(entry.id) { DownloadState.Failed(message, onDisk(entry)) }
                    _events.tryEmit(Event.Failed(entry, message))
                }
            }
            jobs[entry.id] = job
            job.start()
        }
    }

    /** Stops a download and keeps its bytes. */
    fun pause(id: String) {
        synchronized(jobs) { jobs[id] }?.cancel()
    }

    fun pauseAll() {
        synchronized(jobs) { jobs.values.toList() }.forEach { it.cancel() }
    }

    /** Stops a download and deletes its bytes. */
    fun discard(id: String) {
        val job = synchronized(jobs) { jobs.remove(id) }
        job?.cancel()
        scope.launch {
            job?.join()
            catalog.entry(id)?.let(storage::deletePartial)
            _downloads.update { it - id }
            _storageUsedBytes.value = storage.totalBytes()
        }
    }

    /** Deletes an installed model's files. The caller unloads it first if it is in use. */
    fun delete(id: String) {
        catalog.entry(id)?.let(storage::delete)
        store.update { snapshot ->
            val models = snapshot.models.filter { it.id != id }
            snapshot.copy(models = models, selectedId = snapshot.selectedId.takeIf { it != id } ?: models.firstOrNull()?.id)
        }
        reload()
    }

    fun select(id: String) {
        if (installedModel(id) == null) return
        store.update { it.copy(selectedId = id) }
        _selectedId.value = id
    }

    /** Stores a finished reply's decode speed against the model that wrote it. */
    fun recordMeasurement(id: String, tokensPerSecond: Double) {
        if (tokensPerSecond <= 0) return
        val snapshot = store.update { current ->
            current.copy(models = current.models.map { if (it.id == id) it.copy(measuredTokensPerSecond = tokensPerSecond) else it })
        }
        _installed.value = snapshot.models
    }

    /** What an unfinished download has on disk, against what it fetches in all. */
    private fun onDisk(entry: CatalogEntry): DownloadProgress {
        return DownloadProgress(storage.partialBytes(entry), storage.downloadTotalBytes(entry))
    }

    /** The model is installed for text but its image reader is missing (installed before it was offered). */
    fun needsProjector(id: String): Boolean = catalog.entry(id)?.let(storage::needsProjector) == true

    private fun setState(id: String, state: DownloadState) {
        _downloads.update { it + (id to state) }
    }

    private fun end(id: String, state: () -> DownloadState) {
        synchronized(jobs) {
            // Discarded meanwhile: `discard` cleans up.
            if (jobs.remove(id) == null) return
        }
        setState(id, state())
        _storageUsedBytes.value = storage.totalBytes()
    }

    private fun finish(entry: CatalogEntry) {
        synchronized(jobs) { jobs.remove(entry.id) }
        store.update { snapshot ->
            val accepted = snapshot.acceptedLicenses[entry.id] ?: now()
            // Adding the image reader to an installed model keeps its record, with the new size.
            val previous = snapshot.models.firstOrNull { it.id == entry.id }
            val updated = previous?.copy(sizeBytes = storage.installedBytes(entry)) ?: record(entry, accepted)
            val models = snapshot.models.filter { it.id != entry.id } + updated
            snapshot.copy(models = models, selectedId = snapshot.selectedId ?: entry.id)
        }
        _downloads.update { it - entry.id }
        reload()
        _events.tryEmit(Event.Finished(entry))
    }

    private fun record(entry: CatalogEntry, licenseAcceptedAt: Long) = InstalledModel(
        id = entry.id,
        displayName = entry.displayName,
        sizeBytes = storage.installedBytes(entry).takeIf { it > 0 } ?: entry.totalDownloadBytes,
        licenseName = entry.license.name,
        licenseUrl = entry.license.url,
        licenseAcceptedAt = licenseAcceptedAt,
        installedAt = now(),
    )

    private companion object {
        const val TAG = "ModelLibrary"
    }
}
