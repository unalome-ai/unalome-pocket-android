package ai.unalome.pocket.modelcatalog

import java.io.File

/**
 * Where model files live: `noBackupFilesDir/models`, app-private internal storage that Android
 * never backs up or transfers, whatever the backup rules say.
 *
 *     models/<id>/<file>.gguf        finished, verified downloads (the model, and its projector)
 *     models/<id>/<file>.gguf.part   downloads in progress, kept so they can resume
 *     models/installed.json          the installed-model records
 *
 * A model's projector is downloaded before the model file, and the model file is moved into place
 * last, so a present model file means a finished download. The one exception is a model installed
 * before its projector was in the catalog: it is installed for text, and [needsProjector] offers
 * the projector on its own.
 */
class ModelStorage(val root: File) {
    fun directory(entry: CatalogEntry): File = File(root, entry.id)

    fun modelFile(entry: CatalogEntry): File = file(entry, entry.file)

    /** The projector's final path, or `null` for a text-only model. */
    fun projectorFile(entry: CatalogEntry): File? = entry.projector?.let { file(entry, it.file) }

    fun file(entry: CatalogEntry, name: String): File = File(directory(entry), name)

    fun partFile(entry: CatalogEntry, name: String = entry.file): File = File(directory(entry), "$name.part")

    /** The model can be used, for text at least. */
    fun isInstalled(entry: CatalogEntry): Boolean = modelFile(entry).isFile

    fun hasProjector(entry: CatalogEntry): Boolean = projectorFile(entry)?.isFile == true

    /** Every file of the model is in place. */
    fun isComplete(entry: CatalogEntry): Boolean = entry.files.all { file(entry, it.file).isFile }

    /** Installed for text, but the image reader is missing. */
    fun needsProjector(entry: CatalogEntry): Boolean = entry.projector != null && isInstalled(entry) && !hasProjector(entry)

    /**
     * The files the next download fetches: only the projector for a model installed without one,
     * else all of them (finished ones are skipped, but count towards the progress).
     */
    fun downloadFiles(entry: CatalogEntry): List<CatalogEntry.ModelFile> =
        if (isInstalled(entry)) listOfNotNull(entry.projector) else entry.files

    /** Bytes the next download fetches in all, see [downloadFiles]. */
    fun downloadTotalBytes(entry: CatalogEntry): Long = downloadFiles(entry).sumOf { it.sizeBytes }

    /**
     * Bytes of an unfinished download on disk: its partial files, plus the files it already
     * finished while the model itself is not in place yet.
     */
    fun partialBytes(entry: CatalogEntry): Long {
        val parts = entry.files.sumOf { partFile(entry, it.file).takeIf(File::isFile)?.length() ?: 0 }
        val finished = if (isInstalled(entry)) 0 else entry.files.sumOf { file(entry, it.file).takeIf(File::isFile)?.length() ?: 0 }
        return parts + finished
    }

    /** Bytes of the model's finished files. */
    fun installedBytes(entry: CatalogEntry): Long = entry.files.sumOf { file(entry, it.file).takeIf(File::isFile)?.length() ?: 0 }

    /** Removes a model's files, finished or partial. */
    fun delete(entry: CatalogEntry) {
        directory(entry).deleteRecursively()
    }

    /**
     * Drops an unfinished download: everything for a model that is not installed (a finished
     * projector included), only the partial files for one that is.
     */
    fun deletePartial(entry: CatalogEntry) {
        if (!isInstalled(entry)) {
            delete(entry)
            return
        }
        entry.files.forEach { partFile(entry, it.file).delete() }
    }

    /** Bytes of model files on disk, partial downloads included (not the small records file at the root). */
    fun totalBytes(): Long = root.listFiles { file -> file.isDirectory }.orEmpty().sumOf { directory ->
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun prepare(entry: CatalogEntry) {
        directory(entry).mkdirs()
    }
}
