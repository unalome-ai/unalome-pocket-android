package ai.unalome.pocket.modelcatalog

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Implemented by the Application, so the service finds the app's one [ModelLibrary]. */
interface ModelLibraryOwner {
    val modelLibrary: ModelLibrary
}

/**
 * Keeps the app running while a model downloads, with a progress notification.
 *
 * The download itself runs in [ModelLibrary] in the app's scope; this foreground service (type
 * `dataSync`) only raises the process to foreground priority so Android does not stop it when the
 * user leaves the app. It stops itself when no download is running. A plain service rather than
 * WorkManager: downloads always start from a tap in the app, need no scheduling or retry policy,
 * and stay a few lines without another dependency. If the process dies anyway, the `.part` file
 * stays and the Models screen offers Resume.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    private val library: ModelLibrary get() = (application as ModelLibraryOwner).modelLibrary

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            library.pauseAll()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification = progressNotification(library.downloads.value)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (observer == null) {
            observer = scope.launch {
                var lastPosted = 0L
                library.downloads.collect { downloads ->
                    if (downloads.values.none { it is ModelLibrary.DownloadState.Downloading }) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else if (canNotify()) {
                        // Progress changes 5 times a second; System UI only needs it about once a second.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPosted >= 1_000) {
                            lastPosted = now
                            post(NOTIFICATION_ID, progressNotification(downloads))
                        }
                    }
                }
            }
            scope.launch {
                library.events.collect { event -> if (canNotify()) notifyOutcome(event) }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits `dataSync` services to 6 hours a day: pause, keeping the bytes. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        library.pauseAll()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun canNotify(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled()

    /** Posts only with the notification permission (Android 13+); without it the download still runs. */
    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun progressNotification(downloads: Map<String, ModelLibrary.DownloadState>): Notification {
        val running = downloads.filterValues { it is ModelLibrary.DownloadState.Downloading }
        val first = running.keys.firstOrNull()?.let(library::entry)
        val progress = first?.let { running[it.id]?.progress }
        val title = when {
            first == null -> "Preparing download"
            running.size > 1 -> "Downloading ${running.size} models"
            else -> "Downloading ${first.displayName}"
        }
        val text = when {
            progress == null -> "Starting…"
            progress.isVerifying -> "Checking the file…"
            else -> "${ByteFormat.file(progress.completedBytes)} of ${ByteFormat.file(progress.totalBytes)}"
        }
        val pause = PendingIntent.getService(
            this, 1, Intent(this, ModelDownloadService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, ((progress?.fraction ?: 0.0) * 1000).toInt(), progress == null || progress.isVerifying)
            .addAction(0, "Pause", pause)
            .build()
    }

    private fun notifyOutcome(event: ModelLibrary.Event) {
        val (title, text) = when (event) {
            is ModelLibrary.Event.Finished -> "${event.entry.displayName} is ready" to "It runs on this phone, also offline."
            is ModelLibrary.Event.Failed -> "${event.entry.displayName} did not download" to event.message
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        post(event.entryId.hashCode(), notification)
    }

    private val ModelLibrary.Event.entryId: String
        get() = when (this) {
            is ModelLibrary.Event.Finished -> entry.id
            is ModelLibrary.Event.Failed -> entry.id
        }

    private fun openApp(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PAUSE = "ai.unalome.pocket.modelcatalog.PAUSE"

        /** Starts the service; call from the app while it is in the foreground (a user tap). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Progress of models downloading from Hugging Face."
                    },
                )
            }
        }
    }
}
