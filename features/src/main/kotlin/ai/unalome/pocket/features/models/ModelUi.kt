package ai.unalome.pocket.features.models

import ai.unalome.pocket.design.GradientProgressBar
import ai.unalome.pocket.design.PrimaryButton
import ai.unalome.pocket.design.SecondaryButton
import ai.unalome.pocket.design.UnalomeColor
import ai.unalome.pocket.design.UnalomeType
import ai.unalome.pocket.modelcatalog.ByteFormat
import ai.unalome.pocket.modelcatalog.CatalogEntry
import ai.unalome.pocket.modelcatalog.DownloadProgress
import ai.unalome.pocket.modelcatalog.ModelLibrary
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Starts a download, asking once (Android 13+) for permission to show its progress notification.
 * The download starts whatever the answer: the notification is a convenience, not a requirement.
 */
@Composable
fun rememberDownloadStarter(library: ModelLibrary): (CatalogEntry) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return { entry ->
        library.download(entry)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shouldAskForNotifications(context)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private fun shouldAskForNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
    val preferences = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    if (preferences.getBoolean("askedNotifications", false)) return false
    preferences.edit { putBoolean("askedNotifications", true) }
    return true
}

/** Asks the user to accept a model's licence before its first download, as on iOS. */
@Composable
fun LicenseDialog(entry: CatalogEntry, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("LICENCE", style = UnalomeType.Eyebrow, color = UnalomeColor.TextTertiary)
                Text(entry.displayName, style = UnalomeType.Title, color = UnalomeColor.TextPrimary)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This model is published under the ${entry.license.name}. Read it before you download; by downloading you accept its terms.",
                    style = UnalomeType.Callout,
                    color = UnalomeColor.TextSecondary,
                )
                entry.license.url?.let { url ->
                    TextButton(onClick = { uriHandler.openUri(url) }) {
                        Text("Read the licence", style = UnalomeType.Headline, color = UnalomeColor.GradientStart)
                    }
                }
                Text(
                    "It downloads from huggingface.co, ${ByteFormat.file(entry.totalDownloadBytes)}. Wi-Fi is best.",
                    style = UnalomeType.Footnote,
                    color = UnalomeColor.TextTertiary,
                )
            }
        },
        confirmButton = { PrimaryButton("Accept and download ${ByteFormat.file(entry.totalDownloadBytes)}", onAccept) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = UnalomeColor.TextSecondary) } },
        containerColor = UnalomeColor.BackgroundRaised,
    )
}

/** Download, progress with Pause, Resume/Delete, or the error with Try again. */
@Composable
fun DownloadControls(
    entry: CatalogEntry,
    state: ModelLibrary.DownloadState?,
    canDownload: Boolean,
    onDownload: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ModelLibrary.DownloadState.Downloading -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientProgressBar(state.progress?.fraction?.toFloat(), Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    progressText(state.progress),
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = UnalomeType.Footnote,
                    color = UnalomeColor.TextSecondary,
                )
                SecondaryButton("Pause", onPause, color = UnalomeColor.TextPrimary)
            }
        }
        is ModelLibrary.DownloadState.Paused -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientProgressBar(state.progress?.fraction?.toFloat(), Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paused" + (state.progress?.let { " at ${(it.fraction * 100).toInt()}%" } ?: ""),
                    Modifier.weight(1f),
                    style = UnalomeType.Footnote,
                    color = UnalomeColor.TextSecondary,
                )
                SecondaryButton("Delete", onDiscard, color = UnalomeColor.Error)
                SecondaryButton("Resume", onResume)
            }
        }
        is ModelLibrary.DownloadState.Failed -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.message, style = UnalomeType.Footnote, color = UnalomeColor.Error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Try again", onResume)
                if ((state.progress?.completedBytes ?: 0) > 0) SecondaryButton("Delete", onDiscard, color = UnalomeColor.Error)
            }
        }
        null -> if (canDownload) {
            PrimaryButton("Download ${ByteFormat.file(entry.totalDownloadBytes)}", onDownload, modifier.widthIn(min = 160.dp))
        }
    }
}

fun progressText(progress: DownloadProgress?): String = when {
    progress == null -> "Starting…"
    progress.isVerifying -> "Checking the file…"
    else -> "${(progress.fraction * 100).toInt()}% · ${ByteFormat.file(progress.completedBytes)} of ${ByteFormat.file(progress.totalBytes)}"
}
