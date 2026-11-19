package ai.unalome.pocket.features.navigation

import ai.unalome.pocket.design.UnalomeTheme
import ai.unalome.pocket.features.AppServices
import ai.unalome.pocket.features.chat.ChatScreen
import ai.unalome.pocket.features.chat.ChatViewModel
import ai.unalome.pocket.features.conversations.ConversationsScreen
import ai.unalome.pocket.features.conversations.ConversationsViewModel
import ai.unalome.pocket.features.models.ModelsScreen
import ai.unalome.pocket.features.personas.PersonasScreen
import ai.unalome.pocket.features.personas.PersonasViewModel
import ai.unalome.pocket.features.settings.SettingsScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Serializable
data object ModelsRoute : NavKey

@Serializable
data object PersonasRoute : NavKey

/**
 * The app's root. Phone layout: a single-pane stack that opens on a new chat above the chat list.
 * Navigation 3 keeps the back stack as plain data, so a tablet layout can add a list–detail
 * `SceneStrategy` (material3-adaptive-navigation3) without changing the screens.
 */
@Composable
fun PocketApp(services: AppServices, newChatRequests: Int = 0) {
    UnalomeTheme {
        val backStack = rememberNavBackStack(ConversationsRoute, ChatRoute(null))
        // The "New chat" app shortcut, when the app was already open.
        androidx.compose.runtime.LaunchedEffect(newChatRequests) {
            if (newChatRequests > 0) backStack.add(ChatRoute(null))
        }
        val sharedFor = androidx.compose.runtime.remember { mutableMapOf<String, List<android.net.Uri>>() }
        val shared by services.sharedImages.collectAsState()
        androidx.compose.runtime.LaunchedEffect(shared) {
            if (shared.isNotEmpty()) {
                val chat = ChatRoute(null)
                sharedFor[chat.instance] = shared
                services.sharedImages.value = emptyList()
                backStack.add(chat)
            }
        }
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
                        onOpenModels = { backStack.add(ModelsRoute) },
                    )
                }
                entry<ChatRoute> { route ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val viewModel = viewModel {
                        ChatViewModel(
                            route.conversationId,
                            services.store,
                            services.host,
                            services.appScope,
                            onReplyFinished = { rate -> services.models.recordMeasurement(rate) },
                            attachments = services.attachments,
                            personas = services.personas,
                            selectModel = { id -> services.models.selectIfInstalled(id) },
                        )
                    }
                    // Images shared from another app arrive in the new chat opened for them.
                    androidx.compose.runtime.LaunchedEffect(route.instance) {
                        sharedFor.remove(route.instance)?.let { uris ->
                            viewModel.addImages(uris.map { uri -> { context.contentResolver.openInputStream(uri) ?: error("The image could not be opened.") } })
                        }
                    }
                    ChatScreen(
                        viewModel = viewModel,
                        models = services.models,
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                        onOpenModels = { backStack.add(ModelsRoute) },
                        onOpenPersonas = { backStack.add(PersonasRoute) },
                    )
                }
                entry<SettingsRoute> {
                    SettingsScreen(
                        appVersion = services.appVersion,
                        onOpenModels = { backStack.add(ModelsRoute) },
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                        onOpenPersonas = { backStack.add(PersonasRoute) },
                    )
                }
                entry<PersonasRoute> {
                    val viewModel = viewModel { PersonasViewModel(services.personas, services.appScope) }
                    PersonasScreen(viewModel, services.models, onBack = { backStack.removeAt(backStack.lastIndex) })
                }
                entry<ModelsRoute> {
                    ModelsScreen(services.models, onBack = { backStack.removeAt(backStack.lastIndex) })
                }
            },
        )
    }
}
