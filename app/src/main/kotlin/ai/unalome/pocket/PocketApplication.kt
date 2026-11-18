package ai.unalome.pocket

import ai.unalome.pocket.chatcore.ConversationStore
import ai.unalome.pocket.chatcore.EngineHost
import ai.unalome.pocket.chatcore.RoomConversationRepository
import ai.unalome.pocket.chatcore.ScriptedEngine
import ai.unalome.pocket.chatcore.data.PocketDatabase
import ai.unalome.pocket.features.AppServices
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Builds the app's services once. The engine is the scripted one until a real engine lands. */
class PocketApplication : Application() {
    val services: AppServices by lazy {
        val database = PocketDatabase.open(this)
        AppServices(
            store = ConversationStore(RoomConversationRepository(database)),
            host = EngineHost(ScriptedEngine()),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "?",
        )
    }
}
