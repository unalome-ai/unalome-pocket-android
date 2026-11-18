package ai.unalome.pocket.chatcore

import ai.unalome.pocket.chatcore.data.PocketDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Room schema and queries, on Robolectric's SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomConversationRepositoryTest {
    private val database = PocketDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private var clock = 1_000L
    private val store = ConversationStore(RoomConversationRepository(database)) { clock++ }

    @After
    fun close() = database.close()

    @Test
    fun storesMessagesWithAllFields() = runBlocking {
        val conversation = store.createConversation()
        store.appendMessage(conversation.id, ChatRole.USER, "How do tables work?")
        val reply = store.appendMessage(conversation.id, ChatRole.ASSISTANT, "", modelName = "Scripted demo")
        store.saveMessage(
            reply.copy(text = "Like this", reasoning = "think", reasoningMillis = 1200, wasInterrupted = true, tokensPerSecond = 29.5),
        )
        val messages = store.messages(conversation.id)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), messages.map { it.role })
        val saved = messages[1]
        assertEquals("Like this", saved.text)
        assertEquals("think", saved.reasoning)
        assertEquals(1200L, saved.reasoningMillis)
        assertEquals(true, saved.wasInterrupted)
        assertEquals("Scripted demo", saved.modelName)
        assertEquals(29.5, saved.tokensPerSecond!!, 0.0)
        assertEquals("How do tables work?", store.conversation(conversation.id)!!.title)
    }

    @Test
    fun searchEscapesWildcardsAndMatchesMessageText() = runBlocking {
        val sale = store.createConversation()
        store.appendMessage(sale.id, ChatRole.USER, "Is 50% off a good deal?")
        val other = store.createConversation()
        store.appendMessage(other.id, ChatRole.USER, "Plan a picnic")
        store.appendMessage(other.id, ChatRole.ASSISTANT, "Bring SANDWICHES")

        assertEquals(listOf(sale.id), store.conversations("50%").first().map { it.id })
        assertEquals(listOf(other.id), store.conversations("sandwich").first().map { it.id })
        assertTrue(store.conversations("_").first().isEmpty())
        assertEquals(2, store.conversations("").first().size)
    }

    @Test
    fun deleteCascadesToMessages() = runBlocking {
        val conversation = store.createConversation()
        store.appendMessage(conversation.id, ChatRole.USER, "Hi")
        store.delete(conversation.id)
        assertTrue(store.messages(conversation.id).isEmpty())
    }
}
