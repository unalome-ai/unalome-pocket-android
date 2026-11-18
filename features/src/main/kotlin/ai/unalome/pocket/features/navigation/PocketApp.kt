package ai.unalome.pocket.features.navigation

import ai.unalome.pocket.design.UnalomeTheme
import ai.unalome.pocket.features.AppServices
import ai.unalome.pocket.features.chat.ChatScreen
import ai.unalome.pocket.features.chat.ChatViewModel
import ai.unalome.pocket.features.conversations.ConversationsScreen
import ai.unalome.pocket.features.conversations.ConversationsViewModel
import ai.unalome.pocket.features.settings.SettingsScreen
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import java.util.UUID

/** Destinations. Kept as data so a two-pane (list–detail) scene can place them side by side later. */
@Serializable
data object ConversationsRoute : NavKey

/** A chat; `conversationId` `null` is a new chat. [instance] keeps two new chats apart. */
@Serializable
data class ChatRoute(val conversationId: String?, val instance: String = UUID.randomUUID().toString()) : NavKey

@Serializable
data object SettingsRoute : NavKey

/**
 * The app's root. Phone layout: a single-pane stack that opens on a new chat above the chat list.
 * Navigation 3 keeps the back stack as plain data, so a tablet layout can add a list–detail
 * `SceneStrategy` (material3-adaptive-navigation3) without changing the screens.
 */
@Composable
fun PocketApp(services: AppServices) {
    UnalomeTheme {
        val backStack = rememberNavBackStack(ConversationsRoute, ChatRoute(null))
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<ConversationsRoute> {
                    val viewModel = viewModel { ConversationsViewModel(services.store, services.appScope) }
                    ConversationsScreen(
                        viewModel = viewModel,
                        onOpen = { id -> backStack.add(ChatRoute(id)) },
                        onNewChat = { backStack.add(ChatRoute(null)) },
                        onOpenSettings = { backStack.add(SettingsRoute) },
                    )
                }
                entry<ChatRoute> { route ->
                    val viewModel = viewModel { ChatViewModel(route.conversationId, services.store, services.host, services.appScope) }
                    ChatScreen(viewModel, onBack = { backStack.removeAt(backStack.lastIndex) })
                }
                entry<SettingsRoute> {
                    SettingsScreen(
                        appVersion = services.appVersion,
                        engineName = services.host.engine.displayName,
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                    )
                }
            },
        )
    }
}
