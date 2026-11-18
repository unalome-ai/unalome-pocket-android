package ai.unalome.pocket.chatcore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoreTest {
    private var clock = 1_000L
    private val store = ConversationStore(InMemoryConversationRepository()) { clock++ }

    @Test
    fun automaticTitleIsTheFirstLineCutAtAWordBoundary() {
        assertEquals("Hello there", ConversationStore.automaticTitle("  Hello there  \nsecond line"))
        assertEquals(
            "How do I look after a cast-iron pan and…",
            ConversationStore.automaticTitle("How do I look after a cast-iron pan and keep it from rusting?"),
        )
        // A word ending exactly at the limit is kept whole.
        assertEquals("a".repeat(40), ConversationStore.automaticTitle("a".repeat(40)))
        // No space to cut at: a hard cut.
        assertEquals("b".repeat(40) + "…", ConversationStore.automaticTitle("b".repeat(50)))
        assertEquals("", ConversationStore.automaticTitle("   "))
    }

    @Test
    fun firstUserMessageSetsTheTitleAndLaterOnesDoNot() = runTest {
        val conversation = store.createConversation()
        assertEquals("New chat", conversation.displayTitle)
        store.appendMessage(conversation.id, ChatRole.USER, "Plan a picnic")
        store.appendMessage(conversation.id, ChatRole.ASSISTANT, "Sure")
        store.appendMessage(conversation.id, ChatRole.USER, "Something else")
        assertEquals("Plan a picnic", store.conversation(conversation.id)!!.title)
        assertEquals(listOf(0, 1, 2), store.messages(conversation.id).map { it.position })
    }

    @Test
    fun renamedConversationsKeepTheirTitle() = runTest {
        val conversation = store.createConversation()
        store.rename(conversation.id, "  My title ")
        store.appendMessage(conversation.id, ChatRole.USER, "Plan a picnic")
        val renamed = store.conversation(conversation.id)!!
        assertEquals("My title", renamed.title)
        assertFalse(renamed.hasAutomaticTitle)
        // Blank renames are ignored.
        store.rename(conversation.id, "   ")
        assertEquals("My title", store.conversation(conversation.id)!!.title)
    }

    @Test
    fun searchMatchesTitlesAndMessagesMostRecentFirst() = runTest {
        val picnic = store.createConversation()
        store.appendMessage(picnic.id, ChatRole.USER, "Plan a picnic")
        store.appendMessage(picnic.id, ChatRole.ASSISTANT, "Bring sandwiches")
        val pan = store.createConversation()
        store.appendMessage(pan.id, ChatRole.USER, "Cast-iron pan care")

        assertEquals(listOf(pan.id, picnic.id), store.conversations("").first().map { it.id })
        assertEquals(listOf(picnic.id), store.conversations("SANDWICH").first().map { it.id })
        assertEquals(listOf(pan.id), store.conversations("iron").first().map { it.id })
        assertTrue(store.conversations("nothing like this").first().isEmpty())
    }

    @Test
    fun deletingAConversationDeletesItsMessages() = runTest {
        val conversation = store.createConversation()
        store.appendMessage(conversation.id, ChatRole.USER, "Hi")
        store.delete(conversation.id)
        assertTrue(store.conversations().first().isEmpty())
        assertTrue(store.messages(conversation.id).isEmpty())
    }
}
