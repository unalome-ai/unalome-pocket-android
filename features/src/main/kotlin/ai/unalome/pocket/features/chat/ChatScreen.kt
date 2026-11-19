package ai.unalome.pocket.features.chat

import ai.unalome.pocket.chatcore.AttachmentStore
import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.Persona
import ai.unalome.pocket.chatcore.EngineHost
import ai.unalome.pocket.chatcore.Message
import ai.unalome.pocket.design.GlassIconButton
import ai.unalome.pocket.design.GradientText
import ai.unalome.pocket.design.Pill
import ai.unalome.pocket.design.StatusDot
import ai.unalome.pocket.design.UnalomeColor
import ai.unalome.pocket.design.UnalomeFigure
import ai.unalome.pocket.design.UnalomeGradient
import ai.unalome.pocket.design.UnalomeIcons
import ai.unalome.pocket.design.UnalomeType
import ai.unalome.pocket.features.AppLinks
import ai.unalome.pocket.design.SecondaryButton
import ai.unalome.pocket.design.Tag
import ai.unalome.pocket.design.UnalomeRadius
import ai.unalome.pocket.design.glass
import ai.unalome.pocket.features.models.DownloadControls
import ai.unalome.pocket.features.models.ModelController
import ai.unalome.pocket.features.models.rememberDownloadStarter
import ai.unalome.pocket.modelcatalog.ByteFormat
import ai.unalome.pocket.modelcatalog.InstalledModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalUriHandler
import kotlin.math.roundToInt
import ai.unalome.pocket.features.markdown.MarkdownText
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** One conversation: the message list, the status pill and the composer. */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    models: ModelController,
    onBack: (() -> Unit)?,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPersonas: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val personas by viewModel.personaList.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val engine by models.host.engineFlow.collectAsStateWithLifecycle()
    val active by models.active.collectAsStateWithLifecycle()
    val installed by models.library.installed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasNoModel = active == ModelController.Active.None
    var showLoadError by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    // Images: the system photo picker (no storage permission) and the camera app, which writes
    // into an app-private file shared through the FileProvider (the app has no camera permission).
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentStore.MAX_PER_MESSAGE),
    ) { uris ->
        viewModel.addImages(uris.map { uri -> { context.contentResolver.openInputStream(uri) ?: error("The image could not be opened.") } })
    }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = pendingPhoto
        pendingPhoto = null
        if (path == null) return@rememberLauncherForActivityResult
        val file = File(path)
        if (saved && file.length() > 0) viewModel.addImages(listOf { file.inputStream() }) { file.delete() } else file.delete()
    }
    val onPickImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val onTakePhoto = {
        val file = File(context.cacheDir, "camera/photo-${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
        pendingPhoto = file.absolutePath
        try {
            takePhoto.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        } catch (e: ActivityNotFoundException) {
            pendingPhoto = null
            notice = "No camera app" to "This phone has no camera app that can take a photo for the chat. Choose a photo instead."
        }
    }

    // Voice: on-device recognition only.
    val voice = remember { VoiceInput(context.applicationContext) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    var voiceAvailability by remember { mutableStateOf<VoiceAvailability?>(null) }
    var showMicRationale by remember { mutableStateOf(false) }
    var voiceDownload by remember { mutableStateOf<VoiceAvailability.NeedsDownload?>(null) }
    LaunchedEffect(voice) { voiceAvailability = voice.availability() }
    val startListening: () -> Unit = {
        scope.launch {
            when (val availability = voice.availability().also { voiceAvailability = it }) {
                is VoiceAvailability.Available -> {
                    val before = viewModel.draft.value.trimEnd()
                    voice.start(
                        availability.languageTag,
                        onText = { heard -> viewModel.onDraftChange(if (before.isEmpty()) heard else "$before $heard") },
                        onEnd = { message -> message?.let { notice = "Voice input" to it } },
                    )
                }
                is VoiceAvailability.NeedsDownload -> voiceDownload = availability
                is VoiceAvailability.Unavailable -> notice = "Voice input" to availability.reason
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else notice = "Voice input" to
            "Microphone access was not allowed, so voice input is off. You can allow it later in Android Settings, under Apps, Unalome Pocket, Permissions."
    }
    val onMic = {
        when {
            voiceState == VoiceInput.State.Listening -> voice.stop()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startListening()
            else -> showMicRationale = true
        }
    }

    // Load the model while the user writes, so the first reply does not wait for it.
    LaunchedEffect(active) { models.preload() }

    Column(
        modifier
            .fillMaxSize()
            .background(UnalomeColor.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        ChatTopBar(
            title = state.title,
            engineName = engine.displayName,
            installed = installed,
            active = active,
            allowsScripted = models.allowsScriptedEngine,
            isGenerating = state.isGenerating,
            supportsThinking = engine.capabilities.supportsThinking,
            isThinkingEnabled = state.isThinkingEnabled,
            onThinkingChange = viewModel::setThinkingEnabled,
            onSelectModel = models::select,
            onSelectScripted = models::selectScripted,
            onOpenModels = onOpenModels,
            onBack = onBack,
            persona = state.persona,
            personas = personas,
            onSelectPersona = viewModel::selectPersona,
            onOpenPersonas = onOpenPersonas,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.isLoaded && state.messages.isEmpty()) {
                if (hasNoModel) {
                    FirstRunOffer(models, onOpenModels, Modifier.align(Alignment.Center))
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyChat()
                        if (personas.isNotEmpty()) PersonaChips(personas, state.persona, viewModel::selectPersona, onOpenPersonas)
                    }
                }
            } else {
                MessageList(state, viewModel, context)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hasNoModel) {
                if (state.messages.isNotEmpty()) NoModelBar(onOpenModels)
            } else {
                StatusPill(engine.displayName, state, engineState, onShowError = { showLoadError = true })
                Spacer(Modifier.height(10.dp))
                if (state.editingMessageId != null) EditingBar(viewModel::cancelEditing)
                Composer(
                    draft = draft,
                    onDraftChange = viewModel::onDraftChange,
                    canSend = viewModel.canSend,
                    isGenerating = state.isGenerating,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    images = state.draftImages,
                    isAddingImage = state.isAddingImage,
                    imagePath = viewModel::imagePath,
                    onRemoveImage = viewModel::removeImage,
                    supportsImages = viewModel.supportsImages,
                    canAddImage = state.canAddImage && !state.isGenerating,
                    onPickImages = onPickImages,
                    onTakePhoto = onTakePhoto,
                    onImagesUnsupported = {
                        notice = "Images" to "${engine.displayName} cannot read images. Choose a model with image support, such as Gemma 4 " +
                            "or Qwen 3.5 with its image reader downloaded, in Models."
                    },
                    isListening = voiceState == VoiceInput.State.Listening,
                    voiceOff = voiceAvailability is VoiceAvailability.Unavailable,
                    onMic = onMic,
                )
            }
        }
    }

    if (showMicRationale) {
        AlertDialog(
            onDismissRequest = { showMicRationale = false },
            title = { Text("Dictate on this phone") },
            text = {
                Text(
                    "To dictate, Unalome Pocket needs the microphone. It listens only while the microphone button is on, and your " +
                        "speech is recognised on this phone. Nothing is recorded or sent anywhere.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicRationale = false
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showMicRationale = false }) { Text("Not now") } },
            containerColor = UnalomeColor.BackgroundRaised,
        )
    }

    voiceDownload?.let { needed ->
        AlertDialog(
            onDismissRequest = { voiceDownload = null },
            title = { Text("Voice input") },
            text = {
                Text(
                    "On-device speech recognition for ${needed.languageName} is not on this phone yet. Android can download it; " +
                        "your speech is then recognised on the phone and never sent to a server.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    voiceDownload = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        voice.downloadLanguage(needed.languageTag) { message ->
                            message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            scope.launch { voiceAvailability = voice.availability() }
                        }
                    }
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { voiceDownload = null }) { Text("Not now") } },
            containerColor = UnalomeColor.BackgroundRaised,
        )
    }

    notice?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
            containerColor = UnalomeColor.BackgroundRaised,
        )
    }

    val loadError = (engineState as? EngineHost.State.Failed)?.message?.takeIf { showLoadError }
    (state.errorMessage ?: loadError)?.let { message ->
        val dismiss = {
            showLoadError = false
            viewModel.dismissError()
        }
        AlertDialog(
            onDismissRequest = dismiss,
            confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
            dismissButton = {
                TextButton(onClick = {
                    dismiss()
                    onOpenModels()
                }) { Text("Models") }
            },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            containerColor = UnalomeColor.BackgroundRaised,
        )
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    engineName: String,
    installed: List<InstalledModel>,
    active: ModelController.Active,
    allowsScripted: Boolean,
    isGenerating: Boolean,
    supportsThinking: Boolean,
    isThinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectScripted: () -> Unit,
    onOpenModels: () -> Unit,
    onBack: (() -> Unit)?,
    persona: Persona?,
    personas: List<Persona>,
    onSelectPersona: (Persona?) -> Unit,
    onOpenPersonas: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            GlassIconButton(UnalomeIcons.Back, "Back to chats", onBack)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = UnalomeType.Headline.copy(fontSize = UnalomeType.Headline.fontSize * 1.1f),
            color = UnalomeColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        Box {
            val thinkingOn = supportsThinking && isThinkingEnabled
            Pill(
                Modifier
                    .widthIn(max = 220.dp)
                    .alpha(if (isGenerating) 0.5f else 1f)
                    .semantics {
                        contentDescription = (persona?.let { "Persona: ${it.name}. " } ?: "") + "Model: $engineName" +
                            (if (thinkingOn) ", thinking on" else "") + ". Choose another model or persona, or switch thinking"
                    },
                onClick = { if (!isGenerating) menuOpen = true },
            ) {
                if (thinkingOn) {
                    Icon(UnalomeIcons.Sparkles, null, Modifier.size(16.dp), tint = UnalomeColor.GradientStart)
                    Spacer(Modifier.width(6.dp))
                }
                val label = persona?.let { "${it.name} · $engineName" } ?: engineName
                Text(label, style = UnalomeType.Callout, color = UnalomeColor.GradientStart, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Icon(UnalomeIcons.ChevronDown, null, Modifier.size(16.dp), tint = UnalomeColor.GradientStart)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.widthIn(min = 320.dp),
                containerColor = UnalomeColor.BackgroundRaised,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("MODEL", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = UnalomeType.Eyebrow, color = UnalomeColor.TextTertiary)
                installed.forEach { model ->
                    val isActive = active == ModelController.Active.Model(model.id)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.displayName, style = UnalomeType.Headline)
                                Text(ByteFormat.file(model.sizeBytes), style = UnalomeType.Footnote, color = UnalomeColor.TextTertiary)
                            }
                        },
                        onClick = {
                            menuOpen = false
                            if (!isActive) onSelectModel(model.id)
                        },
                        leadingIcon = {
                            if (isActive) Icon(UnalomeIcons.Check, "In use", tint = UnalomeColor.GradientStart) else Spacer(Modifier.size(24.dp))
                        },
                    )
                }
                if (allowsScripted) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Scripted demo", style = UnalomeType.Headline)
                                Text("Debug build only", style = UnalomeType.Footnote, color = UnalomeColor.TextTertiary)
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onSelectScripted()
                        },
                        leadingIcon = {
                            if (active == ModelController.Active.Scripted) Icon(UnalomeIcons.Check, "In use", tint = UnalomeColor.GradientStart) else Spacer(Modifier.size(24.dp))
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Manage models…", style = UnalomeType.Callout) },
                    onClick = {
                        menuOpen = false
                        onOpenModels()
                    },
                    leadingIcon = { Icon(UnalomeIcons.Plus, null, tint = UnalomeColor.TextSecondary) },
                )
                HorizontalDivider(color = UnalomeColor.GlassBorder)
                Text("PERSONA", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = UnalomeType.Eyebrow, color = UnalomeColor.TextTertiary)
                (listOf<Persona?>(null) + personas).forEach { item ->
                    val selected = item?.id == persona?.id
                    DropdownMenuItem(
                        text = { Text(item?.name ?: "None", style = UnalomeType.Callout) },
                        onClick = {
                            menuOpen = false
                            if (!selected) onSelectPersona(item)
                        },
                        leadingIcon = {
                            if (selected) Icon(UnalomeIcons.Check, "Selected", tint = UnalomeColor.GradientStart) else Spacer(Modifier.size(24.dp))
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Manage personas…", style = UnalomeType.Callout) },
                    onClick = {
                        menuOpen = false
                        onOpenPersonas()
                    },
                    leadingIcon = { Icon(UnalomeIcons.Person, null, tint = UnalomeColor.TextSecondary) },
                )
                if (supportsThinking) {
                    HorizontalDivider(color = UnalomeColor.GlassBorder)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Think before answering", style = UnalomeType.Callout)
                                Text("Slower; the reasoning is shown apart from the reply.", style = UnalomeType.Footnote, color = UnalomeColor.TextTertiary)
                            }
                        },
                        onClick = { onThinkingChange(!isThinkingEnabled) },
                        leadingIcon = { Icon(UnalomeIcons.Sparkles, null, tint = UnalomeColor.TextSecondary) },
                        trailingIcon = {
                            Switch(
                                checked = isThinkingEnabled,
                                onCheckedChange = onThinkingChange,
                                colors = SwitchDefaults.colors(checkedTrackColor = UnalomeColor.Teal),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(state: ChatUiState, viewModel: ChatViewModel, context: Context) {
    val listState = rememberLazyListState()
    val lastAssistantId = state.messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.id
    val lastUserId = state.lastUserMessage?.id
    // Reverse layout keeps the newest content anchored to the bottom while a reply grows.
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
    ) {
        item(key = "notice") { AINotice() }
        items(state.messages.asReversed(), key = { it.id }) { message ->
            if (message.role == ChatRole.USER) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    if (message.attachmentNames.isNotEmpty()) {
                        MessageImages(message.attachmentNames, viewModel::imagePath, Modifier.padding(start = 48.dp, bottom = 6.dp))
                    }
                    UserBubble(message.text)
                    if (message.id == lastUserId && !state.isGenerating && state.editingMessageId == null) {
                        ActionIcon(UnalomeIcons.Edit, "Edit and resend", viewModel::editLastMessage)
                    }
                }
            } else {
                val previousUser = state.messages.lastOrNull { it.role == ChatRole.USER && it.position < message.position }
                AssistantMessage(
                    message = message,
                    isStreaming = message.id == state.streamingMessageId,
                    reasoningStartedAt = state.reasoningStartedAt,
                    canRegenerate = message.id == lastAssistantId && state.canRegenerate,
                    onRegenerate = viewModel::regenerate,
                    onReport = { reportResponse(context, previousUser?.text, message, viewModel.engineName) },
                )
            }
        }
    }
    // A new message scrolls back to the bottom.
    LaunchedEffect(state.messages.size) { listState.animateScrollToItem(0) }
}

@Composable
private fun UserBubble(text: String) {
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            Modifier
                .widthIn(max = 320.dp)
                .padding(start = 48.dp)
                .clip(shape)
                .background(Color(0xFF0E0D15), shape)
                .border(1.5.dp, UnalomeGradient.Brand, shape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            style = UnalomeType.Body,
            color = UnalomeColor.TextPrimary,
        )
    }
}

@Composable
private fun AssistantMessage(
    message: Message,
    isStreaming: Boolean,
    reasoningStartedAt: Long?,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onReport: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message.reasoning.isNotEmpty()) {
            Reasoning(message, isStreaming && message.text.isEmpty(), reasoningStartedAt)
        }
        if (message.text.isEmpty() && message.reasoning.isEmpty() && isStreaming) {
            TypingIndicator()
        }
        if (message.text.isNotEmpty()) {
            Box(Modifier.semantics { if (isStreaming) liveRegion = LiveRegionMode.Polite }) {
                MarkdownText(message.text)
            }
        }
        if (!isStreaming) {
            if (message.wasInterrupted || message.stoppedAtLengthLimit) {
                Text(
                    if (message.stoppedAtLengthLimit) "Stopped at the length limit" else "Stopped before the end",
                    style = UnalomeType.Footnote,
                    color = UnalomeColor.TextTertiary,
                )
            }
            MessageActions(message.text, canRegenerate, onRegenerate, onReport)
        }
    }
}

@Composable
private fun Reasoning(message: Message, isThinking: Boolean, startedAt: Long?) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(true) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (isThinking) {
        LaunchedEffect(Unit) {
            while (true) { nowMillis = System.currentTimeMillis(); delay(250) }
        }
    }
    val label = when {
        isThinking && startedAt != null -> "Thinking… ${seconds(nowMillis - startedAt)}"
        message.reasoningMillis != null -> "Thought for ${seconds(message.reasoningMillis!!)}"
        else -> "Thoughts"
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide reasoning" else "Show reasoning") { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(UnalomeIcons.Sparkles, null, Modifier.size(18.dp), tint = UnalomeColor.TextSecondary)
            Spacer(Modifier.width(8.dp))
            Text(label, style = UnalomeType.Callout, color = UnalomeColor.TextSecondary)
            Spacer(Modifier.width(4.dp))
            Icon(
                UnalomeIcons.ChevronDown,
                null,
                Modifier.size(16.dp).then(if (expanded) Modifier else Modifier.alpha(0.8f)),
                tint = UnalomeColor.TextSecondary,
            )
        }
        AnimatedVisibility(expanded) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0x33FFFFFF)))
                Text(
                    message.reasoning.trim(),
                    Modifier.padding(start = 14.dp),
                    style = UnalomeType.Callout.copy(fontSize = UnalomeType.Body.fontSize * 0.94f),
                    color = UnalomeColor.TextTertiary,
                )
            }
        }
    }
}

private fun seconds(millis: Long): String {
    val s = millis / 1000.0
    return if (s < 10) String.format(Locale.ROOT, "%.0f s", s.coerceAtLeast(1.0)) else "${s.toInt()} s"
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 8.dp).semantics { contentDescription = "Writing a reply" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 150), RepeatMode.Reverse),
                label = "dot$index",
            )
            Box(Modifier.size(8.dp).alpha(alpha).background(UnalomeColor.GradientMiddle, CircleShape))
        }
    }
}

@Composable
private fun MessageActions(text: String, canRegenerate: Boolean, onRegenerate: () -> Unit, onReport: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var confirmingReport by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionIcon(UnalomeIcons.Copy, "Copy") {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Reply", text))) }
        }
        if (canRegenerate) ActionIcon(UnalomeIcons.Regenerate, "Regenerate", onRegenerate)
        ActionIcon(UnalomeIcons.Flag, "Report this response") { confirmingReport = true }
    }
    if (confirmingReport) {
        AlertDialog(
            onDismissRequest = { confirmingReport = false },
            title = { Text("Report this response?") },
            text = {
                Text(
                    "This opens an email to Unalome with this response and the message it answered. " +
                        "Nothing is sent unless you send the email yourself.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmingReport = false; onReport() }) { Text("Write email") } },
            dismissButton = { TextButton(onClick = { confirmingReport = false }) { Text("Cancel") } },
            containerColor = UnalomeColor.BackgroundRaised,
        )
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = UnalomeColor.TextTertiary)
    }
}

/** Opens a prefilled email; nothing is sent by the app. */
private fun reportResponse(context: Context, question: String?, message: Message, engineName: String) {
    fun quote(text: String) = text.trim().let { if (it.length > 1500) it.take(1500) + "… [shortened]" else it }
    val body = buildString {
        appendLine("What is wrong with this response? (optional)")
        appendLine(); appendLine(); appendLine("---")
        appendLine("Model: $engineName")
        appendLine("App: Unalome Pocket for Android")
        if (!question.isNullOrBlank()) { appendLine(); appendLine("My message:"); appendLine(quote(question)) }
        appendLine(); appendLine("The response:"); append(quote(message.text))
    }
    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", "", null)).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(AppLinks.SUPPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Report a response from Unalome Pocket")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No email app. Write to ${AppLinks.SUPPORT_EMAIL} and paste the response.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun AINotice() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(UnalomeIcons.Sparkles, null, Modifier.padding(top = 2.dp).size(16.dp), tint = UnalomeColor.TextTertiary)
        Spacer(Modifier.width(10.dp))
        Text(
            "Replies are AI-generated and can be wrong. Check anything important.",
            style = UnalomeType.Footnote,
            color = UnalomeColor.TextTertiary,
        )
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        UnalomeFigure(height = 96.dp)
        Spacer(Modifier.height(4.dp))
        Text("PRIVATE BY DESIGN", style = UnalomeType.Eyebrow, color = UnalomeColor.TextTertiary)
        Column(Modifier.semantics(mergeDescendants = true) { heading() }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing leaves", style = UnalomeType.Display, color = UnalomeColor.TextPrimary, textAlign = TextAlign.Center)
            GradientText("this phone.", UnalomeType.Display.copy(textAlign = TextAlign.Center))
        }
        Text(
            "Replies are written on this phone, and your chats are stored only here.",
            style = UnalomeType.Callout,
            color = UnalomeColor.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Replies are AI-generated and can be wrong.",
            style = UnalomeType.Footnote,
            color = UnalomeColor.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusPill(engineName: String, state: ChatUiState, engineState: EngineHost.State, onShowError: () -> Unit) {
    val failed = engineState is EngineHost.State.Failed
    Pill(Modifier.semantics(mergeDescendants = true) {}, onClick = if (failed) onShowError else null) {
        when (engineState) {
            is EngineHost.State.Loading -> {
                CircularProgressIndicator(Modifier.size(14.dp), color = UnalomeColor.GradientStart, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (engineState.progress > 0 && engineState.progress < 1) "Loading $engineName · ${(engineState.progress * 100).toInt()}%" else "Loading $engineName…",
                    style = UnalomeType.Footnote.copy(fontSize = UnalomeType.Callout.fontSize),
                    color = UnalomeColor.TextSecondary,
                    maxLines = 1,
                )
            }
            is EngineHost.State.Failed -> {
                StatusDot(color = UnalomeColor.Warning)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$engineName could not load · Details",
                    style = UnalomeType.Footnote.copy(fontSize = UnalomeType.Callout.fontSize),
                    color = UnalomeColor.TextSecondary,
                    maxLines = 1,
                )
            }
            else -> {
                StatusDot()
                Spacer(Modifier.width(8.dp))
                val rate = state.tokensPerSecond?.takeIf { it > 0 }?.let { " · ${it.roundToInt()} tok/s" } ?: ""
                Text(
                    "On device · $engineName$rate",
                    style = UnalomeType.Footnote.copy(fontSize = UnalomeType.Callout.fontSize),
                    color = UnalomeColor.TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Shown in place of the composer when the chat has messages but no model is installed any more. */
@Composable
private fun NoModelBar(onOpenModels: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(UnalomeRadius.Card)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "No model on this phone. Download one to continue this chat.",
            Modifier.weight(1f),
            style = UnalomeType.Callout,
            color = UnalomeColor.TextSecondary,
        )
        Spacer(Modifier.width(12.dp))
        SecondaryButton("Models", onOpenModels)
    }
}

/**
 * First run with no model: the recommended model with one download button, in place of the composer.
 * Its licence is named with a link; tapping Download accepts it, as on the iOS download step.
 */
@Composable
private fun FirstRunOffer(models: ModelController, onOpenModels: () -> Unit, modifier: Modifier = Modifier) {
    val library = models.library
    val downloads by library.downloads.collectAsStateWithLifecycle()
    val recommendation = remember(downloads) { library.recommendation() }
    val entry = recommendation.recommendedId?.let(library::entry)
    val startDownload = rememberDownloadStarter(library)
    val uriHandler = LocalUriHandler.current
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        UnalomeFigure(height = 80.dp)
        Text("PRIVATE BY DESIGN", style = UnalomeType.Eyebrow, color = UnalomeColor.TextTertiary)
        Column(Modifier.semantics(mergeDescendants = true) { heading() }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing leaves", style = UnalomeType.Display, color = UnalomeColor.TextPrimary, textAlign = TextAlign.Center)
            GradientText("this phone.", UnalomeType.Display.copy(textAlign = TextAlign.Center))
        }
        Text(
            "To start, download a model. It runs on this phone, also offline.",
            style = UnalomeType.Callout,
            color = UnalomeColor.TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (entry == null) {
            Text(
                "No model fits this phone's free storage or memory. Free up some space, then look again.",
                style = UnalomeType.Callout,
                color = UnalomeColor.Warning,
                textAlign = TextAlign.Center,
            )
            SecondaryButton("See all models", onOpenModels)
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(UnalomeRadius.Card)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.displayName, style = UnalomeType.Headline, color = UnalomeColor.TextPrimary)
                Spacer(Modifier.width(8.dp))
                Tag("Recommended", UnalomeColor.Emerald)
            }
            Text(entry.goodFor, style = UnalomeType.Callout, color = UnalomeColor.TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${entry.license.name}. Downloading accepts its terms.",
                    Modifier.weight(1f),
                    style = UnalomeType.Footnote,
                    color = UnalomeColor.TextTertiary,
                )
                entry.license.url?.let { url ->
                    TextButton(onClick = { uriHandler.openUri(url) }) {
                        Text("Licence", style = UnalomeType.Footnote, color = UnalomeColor.GradientStart)
                    }
                }
            }
            DownloadControls(
                entry = entry,
                state = downloads[entry.id],
                canDownload = true,
                onDownload = { startDownload(entry) },
                onResume = { startDownload(entry) },
                onPause = { library.pause(entry.id) },
                onDiscard = { library.discard(entry.id) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        TextButton(onClick = onOpenModels) {
            Text("Other models", style = UnalomeType.Callout, color = UnalomeColor.GradientStart)
        }
    }
}

/** Shown above the composer while the last message is being edited. */
@Composable
private fun EditingBar(onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(UnalomeIcons.Edit, null, Modifier.size(16.dp), tint = UnalomeColor.TextSecondary)
        Spacer(Modifier.width(8.dp))
        Text("Editing your last message", Modifier.weight(1f), style = UnalomeType.Footnote, color = UnalomeColor.TextSecondary)
        TextButton(onClick = onCancel) { Text("Cancel", style = UnalomeType.Footnote, color = UnalomeColor.GradientStart) }
    }
}

/** Personas to start a new chat with, under the empty chat. */
@Composable
private fun PersonaChips(personas: List<Persona>, selected: Persona?, onSelect: (Persona?) -> Unit, onManage: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("START AS", style = UnalomeType.Eyebrow, color = UnalomeColor.TextTertiary)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            items(personas, key = { it.id }) { persona ->
                val isSelected = persona.id == selected?.id
                Pill(
                    Modifier.semantics { contentDescription = persona.name + if (isSelected) ", selected" else "" },
                    onClick = { onSelect(if (isSelected) null else persona) },
                ) {
                    if (isSelected) {
                        Icon(UnalomeIcons.Check, null, Modifier.size(14.dp), tint = UnalomeColor.GradientStart)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        persona.name,
                        style = UnalomeType.Footnote.copy(fontSize = UnalomeType.Callout.fontSize),
                        color = if (isSelected) UnalomeColor.GradientStart else UnalomeColor.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
            item(key = "manage") {
                Pill(onClick = onManage) {
                    Icon(UnalomeIcons.Plus, "Manage personas", Modifier.size(14.dp), tint = UnalomeColor.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    canSend: Boolean,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    images: List<String>,
    isAddingImage: Boolean,
    imagePath: (String) -> String?,
    onRemoveImage: (String) -> Unit,
    supportsImages: Boolean,
    canAddImage: Boolean,
    onPickImages: () -> Unit,
    onTakePhoto: () -> Unit,
    onImagesUnsupported: () -> Unit,
    isListening: Boolean,
    voiceOff: Boolean,
    onMic: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    var attachMenu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF1A1822), shape)
            .border(1.dp, if (isListening) UnalomeColor.GradientStart else UnalomeColor.GlassBorder, shape)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (images.isNotEmpty() || isAddingImage) DraftImages(images, isAddingImage, imagePath, onRemoveImage)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                ComposerIcon(
                    UnalomeIcons.Plus,
                    if (supportsImages) "Attach an image" else "Attach an image, not available with this model",
                    dimmed = !supportsImages || !canAddImage,
                ) {
                    when {
                        !supportsImages -> onImagesUnsupported()
                        canAddImage -> attachMenu = true
                    }
                }
                DropdownMenu(
                    expanded = attachMenu,
                    onDismissRequest = { attachMenu = false },
                    containerColor = UnalomeColor.BackgroundRaised,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text("Choose photos", style = UnalomeType.Callout) },
                        leadingIcon = { Icon(UnalomeIcons.Image, null, tint = UnalomeColor.TextSecondary) },
                        onClick = {
                            attachMenu = false
                            onPickImages()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Take a photo", style = UnalomeType.Callout) },
                        leadingIcon = { Icon(UnalomeIcons.Camera, null, tint = UnalomeColor.TextSecondary) },
                        onClick = {
                            attachMenu = false
                            onTakePhoto()
                        },
                    )
                }
            }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) {
                    Text(
                        if (isListening) "Listening on this phone… tap the microphone to stop" else "Message",
                        style = UnalomeType.Body.copy(fontSize = UnalomeType.Body.fontSize * if (isListening) 0.9f else 1.05f),
                        color = UnalomeColor.TextTertiary,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Message" },
                    textStyle = UnalomeType.Body.copy(color = UnalomeColor.TextPrimary, fontSize = UnalomeType.Body.fontSize * 1.05f),
                    cursorBrush = SolidColor(UnalomeColor.GradientMiddle),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                )
            }
            ComposerIcon(
                if (isListening) UnalomeIcons.Stop else UnalomeIcons.Mic,
                when {
                    isListening -> "Stop dictating"
                    voiceOff -> "Dictate, not available on this phone"
                    else -> "Dictate"
                },
                dimmed = voiceOff && !isListening,
                tint = if (isListening) UnalomeColor.GradientStart else null,
                onClick = onMic,
            )
            Spacer(Modifier.width(6.dp))
            if (isGenerating) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF2A2833)).clickable(role = Role.Button, onClick = onStop)
                        .semantics { contentDescription = "Stop generating" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(UnalomeIcons.Stop, null, Modifier.size(22.dp), tint = UnalomeColor.TextPrimary)
                }
            } else {
                val background: Brush = if (canSend) UnalomeGradient.Brand else SolidColor(Color(0xFF1B4D48))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(background).clickable(enabled = canSend, role = Role.Button, onClick = onSend)
                        .semantics { contentDescription = "Send" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(UnalomeIcons.ArrowUp, null, Modifier.size(22.dp), tint = if (canSend) UnalomeColor.Background else Color(0xFF2E7C72))
                }
            }
        }
    }
}

@Composable
private fun ComposerIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    dimmed: Boolean,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint ?: if (dimmed) UnalomeColor.TextTertiary else UnalomeColor.TextSecondary)
    }
}
