package ai.unalome.pocket.features

import ai.unalome.pocket.chatcore.ConversationStore
import ai.unalome.pocket.chatcore.EngineHost
import kotlinx.coroutines.CoroutineScope

/**
 * What the screens need, built once by the app. Screens depend on the [EngineHost]'s
 * `ChatEngine` interface only, never on a concrete engine.
 */
class AppServices(
    val store: ConversationStore,
    val host: EngineHost,
    /** Outlives screens, so a reply stopped by leaving the chat is still saved. */
    val appScope: CoroutineScope,
    val appVersion: String,
)

/** Addresses the app shows, in one place. */
object AppLinks {
    const val SOURCE_CODE = "https://github.com/unalome-ai/unalome-pocket"
    const val WEBSITE = "https://unalome.ai"
    const val SUPPORT_EMAIL = "support@unalome.ai"
}
