package ai.unalome.pocket.features

import ai.unalome.pocket.chatcore.AttachmentStore
import ai.unalome.pocket.chatcore.ConversationStore
import ai.unalome.pocket.chatcore.PersonaStore
import ai.unalome.pocket.chatcore.EngineHost
import ai.unalome.pocket.features.models.ModelController
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the screens need, built once by the app. Screens depend on the [EngineHost]'s
 * `ChatEngine` interface only, never on a concrete engine: the app supplies engines through
 * [ModelController]'s factory.
 */
class AppServices(
    val store: ConversationStore,
    val models: ModelController,
    /** Outlives screens, so a reply stopped by leaving the chat is still saved. */
    val appScope: CoroutineScope,
    val appVersion: String,
    val personas: PersonaStore,
    val attachments: AttachmentStore,
) {
    val host: EngineHost get() = models.host

    /** Images shared to the app from another app, waiting for a new chat to take them. */
    val sharedImages = MutableStateFlow<List<Uri>>(emptyList())
}

/** Addresses the app shows, in one place. */
object AppLinks {
    const val SOURCE_CODE = "https://github.com/unalome-ai/unalome-pocket"
    const val WEBSITE = "https://unalome.ai"
    const val SUPPORT_EMAIL = "support@unalome.ai"
    const val LLAMA_CPP = "https://github.com/ggml-org/llama.cpp"
}
