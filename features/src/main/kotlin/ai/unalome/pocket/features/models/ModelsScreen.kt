package ai.unalome.pocket.features.models

import ai.unalome.pocket.design.GlassIconButton
import ai.unalome.pocket.design.SecondaryButton
import ai.unalome.pocket.design.Tag
import ai.unalome.pocket.design.UnalomeColor
import ai.unalome.pocket.design.UnalomeIcons
import ai.unalome.pocket.design.UnalomeRadius
import ai.unalome.pocket.design.UnalomeType
import ai.unalome.pocket.design.glass
import ai.unalome.pocket.modelcatalog.ByteFormat
import ai.unalome.pocket.modelcatalog.CatalogEntry
import ai.unalome.pocket.modelcatalog.InstalledModel
import ai.unalome.pocket.modelcatalog.ModelLabel
import ai.unalome.pocket.modelcatalog.ModelLibrary
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Installed models, the catalog grouped by how well each suits this phone, storage, delete. */
@Composable
fun ModelsScreen(controller: ModelController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val library = controller.library
    val installed by library.installed.collectAsStateWithLifecycle()
    val downloads by library.downloads.collectAsStateWithLifecycle()
    val storageUsed by library.storageUsedBytes.collectAsStateWithLifecycle()
    val active by controller.active.collectAsStateWithLifecycle()
    val recommendation = remember(installed, downloads) { library.recommendation() }
    val device = remember(installed, downloads) { library.device() }
    val startDownload = rememberDownloadStarter(library)
    val scope = rememberCoroutineScope()
    var licenseRequest by remember { mutableStateOf<CatalogEntry?>(null) }
    var deleteRequest by remember { mutableStateOf<InstalledModel?>(null) }

    LaunchedEffect(Unit) { library.reload() }

    Column(modifier.fillMaxSize().background(UnalomeColor.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(UnalomeIcons.Back, "Back", onBack)
            Spacer(Modifier.width(14.dp))
            Text("Models", Modifier.semantics { heading() }, style = UnalomeType.Title, color = UnalomeColor.TextPrimary)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(null) {
                Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}) {
                    Text("STORAGE", style = UnalomeType.Eyebrow, color = UnalomeColor.TextTertiary)
                    Text(
                        if (storageUsed > 0) "Models use ${ByteFormat.file(storageUsed)}" else "No models installed yet",
                        style = UnalomeType.Headline,
                        color = UnalomeColor.TextPrimary,
                    )
                    device.freeStorageBytes?.let {
                        Text("${ByteFormat.file(it)} free on this phone", style = UnalomeType.Footnote, color = UnalomeColor.TextSecondary)
                    }
                }
            }

            if (installed.isNotEmpty()) {
                Section("On this phone") {
                    installed.forEachIndexed { index, model ->
                        if (index > 0) Divider()
                        InstalledRow(
                            model = model,
                            entry = library.entry(model.id),
                            isInUse = active == ModelController.Active.Model(model.id),
                            onUse = { controller.select(model.id) },
                            onDelete = { deleteRequest = model },
                            needsProjector = library.needsProjector(model.id),
                            projectorState = downloads[model.id],
                            onDownloadProjector = { library.entry(model.id)?.let(startDownload) },
                            onPause = { library.pause(model.id) },
                            onDiscard = { library.discard(model.id) },
                        )
                    }
                }
            }

            val notInstalled = library.catalog.models.filter { entry -> installed.none { it.id == entry.id } }
            for (group in CatalogGroup.entries) {
                val entries = notInstalled.filter { group.matches(recommendation.label(it.id)) }.sortedByDescending { it.qualityRank }
                if (entries.isEmpty()) continue
                Section(group.title) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) Divider()
                        CatalogRow(
                            entry = entry,
                            label = recommendation.label(entry.id),
                            state = downloads[entry.id],
                            onDownload = {
                                if (library.hasAcceptedLicense(entry.id)) startDownload(entry) else licenseRequest = entry
                            },
                            onResume = { startDownload(entry) },
                            onPause = { library.pause(entry.id) },
                            onDiscard = { library.discard(entry.id) },
                        )
                    }
                }
            }

            if (controller.allowsScriptedEngine) {
                Section("Debug build") {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { controller.selectScripted() }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Scripted demo", style = UnalomeType.Headline, color = UnalomeColor.TextPrimary)
                            Text("Canned replies, for UI tests. Not in release builds.", style = UnalomeType.Footnote, color = UnalomeColor.TextSecondary)
                        }
                        if (active == ModelController.Active.Scripted) InUseMark()
                    }
                }
            }

            Section("This phone") {
                InfoRow("Memory", ByteFormat.memory(library.deviceAtLaunch.totalMemoryBytes))
                Divider()
                InfoRow("Free storage", device.freeStorageBytes?.let(ByteFormat::file) ?: "Unknown")
                Text(
                    "Models download from huggingface.co at a fixed version, only when you tap Download. Nothing else is sent. " +
                        "Speeds are measured on this phone after each reply.",
                    Modifier.padding(16.dp),
                    style = UnalomeType.Footnote,
                    color = UnalomeColor.TextTertiary,
                )
            }
        }
    }

    licenseRequest?.let { entry ->
        LicenseDialog(
            entry = entry,
            onAccept = {
                licenseRequest = null
                startDownload(entry)
            },
            onDismiss = { licenseRequest = null },
        )
    }

    deleteRequest?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text("Delete ${model.displayName}?") },
            text = { Text("This frees ${ByteFormat.file(model.sizeBytes)}. Your chats stay. You can download the model again later.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteRequest = null
                    scope.launch { controller.delete(model.id) }
                }) { Text("Delete", color = UnalomeColor.Error) }
            },
            dismissButton = { TextButton(onClick = { deleteRequest = null }) { Text("Cancel") } },
            containerColor = UnalomeColor.BackgroundRaised,
        )
    }
}

private enum class CatalogGroup(val title: String) {
    Recommended("Recommended for this phone"),
    Runs("Runs on this phone"),
    TooLarge("Too large for this phone");

    fun matches(label: ModelLabel): Boolean = when (this) {
        Recommended -> label == ModelLabel.Recommended
        Runs -> label == ModelLabel.Runs
        TooLarge -> label is ModelLabel.TooLarge
    }
}

@Composable
private fun InstalledRow(
    model: InstalledModel,
    entry: CatalogEntry?,
    isInUse: Boolean,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    needsProjector: Boolean,
    projectorState: ModelLibrary.DownloadState?,
    onDownloadProjector: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !isInUse, role = Role.Button, onClickLabel = "Use ${model.displayName}", onClick = onUse)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.displayName, style = UnalomeType.Headline, color = UnalomeColor.TextPrimary)
                    if (isInUse) {
                        Spacer(Modifier.width(8.dp))
                        Tag("In use", UnalomeColor.Emerald)
                    }
                }
                val parts = buildList {
                    add(ByteFormat.file(model.sizeBytes))
                    add(model.licenseName)
                    if (entry?.supportsVision == true && !needsProjector) add("Images")
                    if (entry?.supportsThinking == true) add("Thinking")
                    model.measuredTokensPerSecond?.let { add("${it.toInt()} tok/s measured") }
                }
                Text(parts.joinToString(" · "), style = UnalomeType.Footnote, color = UnalomeColor.TextSecondary)
            }
            GlassIconButtonPlain(onDelete, "Delete ${model.displayName}")
        }
        // Installed before the catalog offered its image reader: offer that on its own.
        val projector = entry?.projector
        if (needsProjector && projector != null) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (projectorState == null) {
                    Text("This model can also read images once its image reader is downloaded.", style = UnalomeType.Footnote, color = UnalomeColor.TextSecondary)
                    SecondaryButton("Download image support (${ByteFormat.file(projector.sizeBytes)})", onDownloadProjector)
                } else {
                    DownloadControls(
                        entry = entry,
                        state = projectorState,
                        canDownload = true,
                        onDownload = onDownloadProjector,
                        onResume = onDownloadProjector,
                        onPause = onPause,
                        onDiscard = onDiscard,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassIconButtonPlain(onClick: () -> Unit, label: String) {
    Box(
        Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(UnalomeIcons.Trash, null, Modifier.size(22.dp), tint = UnalomeColor.TextTertiary)
    }
}

@Composable
private fun InUseMark() {
    Icon(UnalomeIcons.Check, "In use", Modifier.size(22.dp), tint = UnalomeColor.Emerald)
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    label: ModelLabel,
    state: ModelLibrary.DownloadState?,
    onDownload: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.displayName, style = UnalomeType.Headline, color = UnalomeColor.TextPrimary)
            if (label == ModelLabel.Recommended) {
                Spacer(Modifier.width(8.dp))
                Tag("Recommended", UnalomeColor.Emerald)
            }
        }
        Text(entry.goodFor, style = UnalomeType.Callout, color = UnalomeColor.TextSecondary)
        val details = buildList {
            add(ByteFormat.file(entry.totalDownloadBytes))
            if (label !is ModelLabel.TooLarge) add("needs ${ByteFormat.memory(entry.minimumMemoryBytes)} memory") // else the reason says it
            add(entry.license.name)
            if (entry.supportsVision) add("Images")
            if (entry.supportsThinking) add("Thinking")
        }
        Text(details.joinToString(" · "), style = UnalomeType.Footnote, color = UnalomeColor.TextTertiary)
        if (label is ModelLabel.TooLarge) {
            Text(label.reason, style = UnalomeType.Footnote, color = UnalomeColor.Warning)
        }
        DownloadControls(
            entry = entry,
            state = state,
            canDownload = label.isUsable,
            onDownload = onDownload,
            onResume = onResume,
            onPause = onPause,
            onDiscard = onDiscard,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}) {
        Text(title, Modifier.weight(1f), style = UnalomeType.Callout, color = UnalomeColor.TextPrimary)
        Text(value, style = UnalomeType.Callout, color = UnalomeColor.TextSecondary)
    }
}

@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(title.uppercase(), Modifier.padding(start = 4.dp).semantics { heading() }, style = UnalomeType.Eyebrow, color = UnalomeColor.TextTertiary)
        }
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(UnalomeRadius.Card)), content = content)
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = UnalomeColor.GlassBorder)

