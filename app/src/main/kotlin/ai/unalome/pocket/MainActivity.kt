package ai.unalome.pocket

import ai.unalome.pocket.features.navigation.PocketApp
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    /** Counts "New chat" shortcut taps while the app is open; the app then opens a new chat. */
    private val newChatRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark, transparent system bars over the near-black background, on every API level.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val services = (application as PocketApplication).services
        if (savedInstanceState == null) receiveShared(intent)
        // A cold start opens on a new chat anyway, so the shortcut needs nothing more then.
        setContent { PocketApp(services, newChatRequests.intValue) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_NEW_CHAT) newChatRequests.intValue++
        receiveShared(intent)
    }

    /** Images shared from another app ("Share" › Unalome Pocket) start a new chat with them. */
    private fun receiveShared(intent: Intent) {
        if (intent.type?.startsWith("image/") != true) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) (application as PocketApplication).services.sharedImages.value = uris
    }

    companion object {
        const val ACTION_NEW_CHAT = "ai.unalome.pocket.action.NEW_CHAT"
    }
}
