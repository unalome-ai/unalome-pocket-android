package ai.unalome.pocket.features.chat

import ai.unalome.pocket.chatcore.ChatRole
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
import ai.unalome.pocket.features.markdown.MarkdownText
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
fun ChatScreen(viewModel: ChatViewModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            engineName = viewModel.engineName,
            supportsThinking = viewModel.supportsThinking,
            isThinkingEnabled = state.isThinkingEnabled,
            onThinkingChange = viewModel::setThinkingEnabled,
            onBack = onBack,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.isLoaded && state.messages.isEmpty()) {
                EmptyChat(Modifier.align(Alignment.Center))
            } else {
                MessageList(state, viewModel, context)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusPill(viewModel.engineName, state, engineState)
            Spacer(Modifier.height(10.dp))
            Composer(
                draft = draft,
                onDraftChange = viewModel::onDraftChange,
                canSend = viewModel.canSend,
                isGenerating = state.isGenerating,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onStub = { feature -> Toast.makeText(context, "$feature arrives with on-device models.", Toast.LENGTH_SHORT).show() },
            )
        }
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
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
    supportsThinking: Boolean,
    isThinkingEnabled: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
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
            Pill(
                Modifier.semantics { contentDescription = "Model: $engineName. Opens chat options" },
                onClick = { menuOpen = true },
            ) {
                if (isThinkingEnabled) {
                    Icon(UnalomeIcons.Sparkles, null, Modifier.size(16.dp), tint = UnalomeColor.GradientStart)
                    Spacer(Modifier.width(6.dp))
                }
                Text(engineName, style = UnalomeType.Callout, color = UnalomeColor.GradientStart, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Icon(UnalomeIcons.ChevronDown, null, Modifier.size(16.dp), tint = UnalomeColor.GradientStart)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = UnalomeColor.BackgroundRaised,
                shape = RoundedCornerShape(16.dp),
            ) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(engineName, style = UnalomeType.Headline)
                            Text("Canned replies until on-device models arrive", style = UnalomeType.Footnote, color = UnalomeColor.TextTertiary)
                        }
                    },
                    onClick = { menuOpen = false },
                    leadingIcon = { Icon(UnalomeIcons.Check, null, tint = UnalomeColor.GradientStart) },
                )
                if (supportsThinking) {
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.widthIn(max = 220.dp)) {
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
                UserBubble(message.text)
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
private fun StatusPill(engineName: String, state: ChatUiState, engineState: EngineHost.State) {
    Pill(Modifier.semantics(mergeDescendants = true) {}) {
        when (engineState) {
            is EngineHost.State.Loading -> {
                Text(
                    if (engineState.progress > 0 && engineState.progress < 1) "Loading $engineName · ${(engineState.progress * 100).toInt()}%" else "Loading $engineName…",
                    style = UnalomeType.Footnote.copy(fontSize = UnalomeType.Callout.fontSize),
                    color = UnalomeColor.TextSecondary,
                )
            }
            else -> {
                StatusDot()
                Spacer(Modifier.width(8.dp))
                val rate = state.tokensPerSecond?.let { " · ${it.toInt()} tok/s" } ?: ""
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

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    canSend: Boolean,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStub: (String) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(Color(0xFF1A1822), shape)
            .border(1.dp, UnalomeColor.GlassBorder, shape)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerIcon(UnalomeIcons.Plus, "Attach an image, coming with on-device models", stub = true) { onStub("Image input") }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (draft.isEmpty()) {
                Text("Message", style = UnalomeType.Body.copy(fontSize = UnalomeType.Body.fontSize * 1.05f), color = UnalomeColor.TextTertiary)
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
        ComposerIcon(UnalomeIcons.Mic, "Dictate, coming with on-device models", stub = true) { onStub("Voice input") }
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

@Composable
private fun ComposerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, stub: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (stub) UnalomeColor.TextTertiary else UnalomeColor.TextSecondary)
    }
}

