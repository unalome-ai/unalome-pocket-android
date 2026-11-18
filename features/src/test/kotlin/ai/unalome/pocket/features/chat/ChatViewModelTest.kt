package ai.unalome.pocket.features.chat

import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.ConversationStore
import ai.unalome.pocket.chatcore.EngineHost
import ai.unalome.pocket.chatcore.InMemoryConversationRepository
import ai.unalome.pocket.chatcore.ScriptedEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The chat flow against the scripted engine, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = InMemoryConversationRepository()
    private val store = ConversationStore(repository)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newViewModel(conversationId: String? = null) =
        ChatViewModel(
            conversationId, store, EngineHost(ScriptedEngine(tokenDelayMillis = 30, clock = { testScheduler.currentTime })), writeScope = this, now = { testScheduler.currentTime })

    @Test
    fun sendCreatesTheConversationAndStreamsTheReply() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onDraftChange("  Show me some code  ")
        assertTrue(viewModel.canSend)
        viewModel.send()
        assertTrue(viewModel.state.value.isGenerating)
        assertEquals("", viewModel.draft.value)

        advanceTimeBy(300)
        val midStream = viewModel.state.value
        assertTrue(midStream.isGenerating)
        assertNotNull(midStream.streamingMessageId)
        val partial = midStream.messages.last()
        assertEquals(ChatRole.ASSISTANT, partial.role)
        assertTrue(partial.text.isNotEmpty())
        assertTrue(ScriptedEngine.DEFAULT_SCRIPTS[2].answer.startsWith(partial.text))
        assertFalse(viewModel.canSend)

        advanceUntilIdle()
        val done = viewModel.state.value
        assertFalse(done.isGenerating)
        assertNull(done.streamingMessageId)
        assertEquals("Show me some code", done.title)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), done.messages.map { it.role })
        assertEquals(ScriptedEngine.DEFAULT_SCRIPTS[2].answer, done.messages.last().text)
        assertFalse(done.messages.last().wasInterrupted)

        // Persisted, with the title from the first message and the model name.
        val id = done.conversationId!!
        assertEquals("Show me some code", store.conversation(id)!!.title)
        val saved = store.messages(id)
        assertEquals(ScriptedEngine.DEFAULT_SCRIPTS[2].answer, saved.last().text)
        assertEquals("Scripted demo", saved.last().modelName)
    }

    @Test
    fun stopKeepsTheTextSoFarAndMarksItInterrupted() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onDraftChange("Tell me more")
        viewModel.send()
        advanceTimeBy(200)
        viewModel.stop()

        val stopped = viewModel.state.value
        assertFalse(stopped.isGenerating)
        val reply = stopped.messages.last()
        assertTrue(reply.text.isNotEmpty())
        assertTrue(reply.wasInterrupted)

        advanceUntilIdle()
        // Nothing more streams in after Stop, and the partial reply is saved.
        assertEquals(reply.text, viewModel.state.value.messages.last().text)
        val saved = store.messages(stopped.conversationId!!).last()
        assertEquals(reply.text, saved.text)
        assertTrue(saved.wasInterrupted)
    }

    @Test
    fun stopBeforeAnyTokenRemovesTheEmptyReply() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onDraftChange("Tell me more")
        viewModel.send()
        advanceTimeBy(5)
        viewModel.stop()
        advanceUntilIdle()
        assertEquals(listOf(ChatRole.USER), viewModel.state.value.messages.map { it.role })
        assertEquals(listOf(ChatRole.USER), store.messages(viewModel.state.value.conversationId!!).map { it.role })
    }

    @Test
    fun regenerateReplacesTheLastReply() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onDraftChange("Tell me more")
        viewModel.send()
        advanceUntilIdle()
        val first = viewModel.state.value.messages.last()
        assertTrue(viewModel.state.value.canRegenerate)

        viewModel.regenerate()
        assertTrue(viewModel.state.value.isGenerating)
        advanceUntilIdle()

        val messages = viewModel.state.value.messages
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), messages.map { it.role })
        assertTrue(messages.last().id != first.id)
        // Same turn and history, so the scripted reply is the same text.
        assertEquals(first.text, messages.last().text)
        assertEquals(messages.map { it.id }, store.messages(viewModel.state.value.conversationId!!).map { it.id })
    }

    @Test
    fun thinkingRepliesKeepReasoningApart() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onDraftChange("Should I walk, cycle or take the bus?")
        viewModel.send()
        advanceUntilIdle()
        val reply = viewModel.state.value.messages.last()
        assertEquals(ScriptedEngine.DEFAULT_SCRIPTS[1].reasoning, reply.reasoning.trim())
        assertEquals(ScriptedEngine.DEFAULT_SCRIPTS[1].answer, reply.text)
        assertNotNull(reply.reasoningMillis)
    }

    @Test
    fun reopeningAConversationLoadsItsMessages() = runTest(dispatcher) {
        val first = newViewModel()
        first.onDraftChange("Tell me more")
        first.send()
        advanceUntilIdle()
        val id = first.state.value.conversationId!!

        val reopened = newViewModel(id)
        advanceUntilIdle()
        assertEquals(first.state.value.messages.map { it.text }, reopened.state.value.messages.map { it.text })
        assertEquals("Tell me more", reopened.state.value.title)
    }

    @Test
    fun blankDraftsAreNotSent() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onDraftChange("   ")
        viewModel.send()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertNull(viewModel.state.value.conversationId)
    }
}
