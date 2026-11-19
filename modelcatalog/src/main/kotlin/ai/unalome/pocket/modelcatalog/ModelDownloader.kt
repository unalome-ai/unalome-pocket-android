package ai.unalome.pocket.modelcatalog

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

data class DownloadProgress(
    val completedBytes: Long,
    val totalBytes: Long,
    /** Checking the finished file against Hugging Face's checksum. */
    val isVerifying: Boolean = false,
) {
    val fraction: Double get() = if (totalBytes > 0) (completedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0) else 0.0
}

/** Why a download stopped. Each case has a plain message in [PlainErrorMessage]. */
sealed class DownloadException(message: String) : IOException(message) {
    class NotEnoughSpace(val needed: Long, val available: Long) :
        DownloadException("Not enough free storage: needs ${ByteFormat.file(needed)}, ${ByteFormat.file(available)} available.")
    class BadStatus(val status: Int) : DownloadException("The download server answered with status $status.")
    class SizeMismatch : DownloadException("The file did not download completely. Try again.")
    class ChecksumMismatch : DownloadException(
        "The file arrived damaged (its checksum does not match the one Hugging Face publishes) and was deleted. Try the download again.",
    )
    class DisallowedHost(val host: String) : DownloadException("The download was redirected to $host, which this app does not contact.")
}

/** The only hosts the app downloads from: Hugging Face and its file CDN. */
object HuggingFace {
    fun fileUrl(entry: CatalogEntry): URL = URL("https://huggingface.co/${entry.repo}/resolve/${entry.revision}/${entry.file}")

    fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "huggingface.co" || h.endsWith(".huggingface.co") || h == "hf.co" || h.endsWith(".hf.co")
    }
}

/**
 * Downloads a model's files at their pinned revision: the projector first (for models that read
 * images), the model file last, so a model file in place always means a finished download.
 *
 * Each file's bytes go to `<file>.part`, asked for with an HTTP `Range` from what is already there,
 * so a cancelled or interrupted download resumes where it stopped, also after the app was closed.
 * At the end of each file its size and SHA-256 are checked and it is renamed into place
 * atomically; a file that fails the check is deleted, the others are kept. Redirects are followed
 * by hand, only over HTTPS and only to Hugging Face hosts. Requests carry a generic User-Agent and
 * nothing else that identifies the user or device.
 */
class ModelDownloader(
    private val storage: ModelStorage,
    private val freeSpace: () -> Long?,
    /** The URL of an entry's [CatalogEntry.file]; other files are asked for with a copy naming them. */
    private val urlFor: (CatalogEntry) -> URL = HuggingFace::fileUrl,
    private val isAllowedHost: (String) -> Boolean = HuggingFace::isAllowedHost,
    private val requireHttps: Boolean = true,
    private val headroomBytes: Long = 512L shl 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Fetches whatever [ModelStorage.downloadFiles] lists and is not in place yet: everything for a
     * new model, only the projector for a model installed without one. Returns the model file.
     */
    suspend fun download(entry: CatalogEntry, progress: (DownloadProgress) -> Unit): File {
        storage.prepare(entry)
        val files = storage.downloadFiles(entry)
        val total = files.sumOf { it.sizeBytes }
        for (file in files) {
            val part = storage.partFile(entry, file.file)
            if (part.length() > file.sizeBytes) part.delete()
        }
        val onDisk = files.sumOf { file ->
            if (storage.file(entry, file.file).isFile) file.sizeBytes else storage.partFile(entry, file.file).length()
        }
        val needed = (total - onDisk) + headroomBytes
        freeSpace()?.let { available -> if (needed > available) throw DownloadException.NotEnoughSpace(needed, available) }

        var done = 0L
        for (file in files) {
            val target = storage.file(entry, file.file)
            if (!target.isFile) fetchAndVerify(entry, file, target, done, total, progress)
            done += file.sizeBytes
        }
        progress(DownloadProgress(total, total))
        return storage.modelFile(entry)
    }

    private suspend fun fetchAndVerify(
        entry: CatalogEntry,
        file: CatalogEntry.ModelFile,
        target: File,
        before: Long,
        total: Long,
        progress: (DownloadProgress) -> Unit,
    ) {
        val part = storage.partFile(entry, file.file)
        if (part.length() < file.sizeBytes) {
            fetch(urlFor(entry.copy(file = file.file)), part, file.sizeBytes) { written ->
                progress(DownloadProgress(before + written, total))
            }
        }
        if (part.length() != file.sizeBytes) throw DownloadException.SizeMismatch()
        file.sha256?.let { expected ->
            progress(DownloadProgress(before + file.sizeBytes, total, isVerifying = true))
            if (sha256(part) != expected) {
                part.delete()
                throw DownloadException.ChecksumMismatch()
            }
        }
        withContext(Dispatchers.IO) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun fetch(url: URL, part: File, size: Long, progress: (Long) -> Unit) = coroutineScope {
        val offset = part.length()
        val current = AtomicReference<HttpURLConnection?>(null)
        // A stalled read only ends when the socket closes, so cancelling closes it.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { current.get()?.disconnect() }
        }
        try {
            withContext(Dispatchers.IO) {
                val connection = open(url, offset) { current.set(it) }
                val append = when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val range = connection.getHeaderField("Content-Range")
                        if (range != null && !range.startsWith("bytes $offset-")) throw DownloadException.BadStatus(status)
                        true
                    }
                    HttpURLConnection.HTTP_OK -> false // the server sent the whole file: start over
                    416 -> throw DownloadException.SizeMismatch().also { part.delete() }
                    else -> throw DownloadException.BadStatus(status)
                }
                var written = if (append) offset else 0L
                var lastReport = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (written + read > size) throw DownloadException.SizeMismatch()
                            output.write(buffer, 0, read)
                            written += read
                            val now = clock()
                            if (now - lastReport >= 200) {
                                lastReport = now
                                progress(written)
                            }
                        }
                    }
                }
                progress(written)
            }
        } finally {
            closer.cancel()
        }
    }

    private fun open(start: URL, offset: Long, onOpen: (HttpURLConnection) -> Unit): HttpURLConnection {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            if (requireHttps && url.protocol != "https") throw DownloadException.DisallowedHost(url.host)
            if (!isAllowedHost(url.host)) throw DownloadException.DisallowedHost(url.host)
            val connection = url.openConnection() as HttpURLConnection
            onOpen(connection)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val status = connection.responseCode
            if (status !in REDIRECTS) return connection
            val location = connection.getHeaderField("Location") ?: throw DownloadException.BadStatus(status)
            connection.disconnect()
            url = URL(url, location)
        }
        throw IOException("Too many redirects.")
    }

    companion object {
        const val USER_AGENT = "UnalomePocket"
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** Lowercase hex SHA-256, read in 1 MB chunks so a multi-gigabyte file never sits in memory. */
        suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
