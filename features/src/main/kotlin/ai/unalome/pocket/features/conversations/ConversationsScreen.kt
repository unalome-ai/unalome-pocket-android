package ai.unalome.pocket.features.conversations

import ai.unalome.pocket.chatcore.Conversation
import ai.unalome.pocket.design.GlassIconButton
import ai.unalome.pocket.design.UnalomeColor
import ai.unalome.pocket.design.UnalomeIcons
import ai.unalome.pocket.design.UnalomeMark
import ai.unalome.pocket.design.UnalomeRadius
import ai.unalome.pocket.design.UnalomeType
import ai.unalome.pocket.design.glass
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Every chat, most recent first, with search, delete and New chat. */
@Composable
fun ConversationsScreen(
    viewModel: ConversationsViewModel,
    onOpen: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().background(UnalomeColor.Background).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            UnalomeMark(height = 26.dp)
            Spacer(Modifier.width(12.dp))
            Text("Chats", Modifier.weight(1f).semantics { heading() }, style = UnalomeType.Title, color = UnalomeColor.TextPrimary)
            GlassIconButton(UnalomeIcons.Info, "About and privacy", onOpenSettings)
            Spacer(Modifier.width(10.dp))
            GlassIconButton(UnalomeIcons.NewChat, "New chat", onNewChat, tint = UnalomeColor.GradientStart)
        }
        SearchField(query, viewModel::onQueryChange, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val list = conversations
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyList(isSearching = query.isNotBlank(), query = query)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { conversation ->
                    ConversationRow(conversation, onOpen = { onOpen(conversation.id) }, onDelete = { viewModel.delete(conversation.id) })
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(UnalomeRadius.Control)).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(UnalomeIcons.Search, null, Modifier.size(20.dp), tint = UnalomeColor.TextTertiary)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search chats", style = UnalomeType.Callout, color = UnalomeColor.TextTertiary)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search chats" },
                singleLine = true,
                textStyle = UnalomeType.Callout.copy(color = UnalomeColor.TextPrimary),
                cursorBrush = SolidColor(UnalomeColor.GradientMiddle),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                UnalomeIcons.Close, "Clear search",
                Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = { onChange("") }),
                tint = UnalomeColor.TextTertiary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(conversation: Conversation, onOpen: () -> Unit, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState()
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(UnalomeRadius.Card)
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(shape).background(UnalomeColor.Error.copy(alpha = 0.85f)).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(UnalomeIcons.Trash, "Delete", tint = UnalomeColor.TextPrimary)
            }
        },
    ) {
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(UnalomeColor.Background)
                    .glass(shape)
                    .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }, onLongClickLabel = "More options")
                    .semantics {
                        customActions = listOf(CustomAccessibilityAction("Delete") { onDelete(); true })
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    conversation.displayTitle,
                    style = UnalomeType.Headline,
                    color = UnalomeColor.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    relativeTime(conversation.updatedAt),
                    style = UnalomeType.Footnote,
                    color = UnalomeColor.TextTertiary,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = UnalomeColor.BackgroundRaised) {
                DropdownMenuItem(
                    text = { Text("Delete", color = UnalomeColor.Error) },
                    leadingIcon = { Icon(UnalomeIcons.Trash, null, tint = UnalomeColor.Error) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun EmptyList(isSearching: Boolean, query: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (isSearching) UnalomeIcons.Search else UnalomeIcons.Chats, null, Modifier.size(40.dp), tint = UnalomeColor.TextTertiary)
        Spacer(Modifier.height(16.dp))
        Text(
            if (isSearching) "No results for “$query”" else "No chats yet",
            style = UnalomeType.Headline,
            color = UnalomeColor.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (isSearching) "Check the spelling or try a new search." else "Start one with the New chat button.",
            style = UnalomeType.Callout,
            color = UnalomeColor.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun relativeTime(time: Long, now: Long = System.currentTimeMillis()): String =
    if (now - time < DateUtils.MINUTE_IN_MILLIS) "Just now"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
