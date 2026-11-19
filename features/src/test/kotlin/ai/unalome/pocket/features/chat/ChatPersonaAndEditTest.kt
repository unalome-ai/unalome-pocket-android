package ai.unalome.pocket.features.chat

import ai.unalome.pocket.chatcore.ChatEngine
import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.ChatTurn
import ai.unalome.pocket.chatcore.ConversationStore
import ai.unalome.pocket.chatcore.EngineCapabilities
import ai.unalome.pocket.chatcore.EngineHost
import ai.unalome.pocket.chatcore.GenerationEvent
import ai.unalome.pocket.chatcore.GenerationOptions
import ai.unalome.pocket.chatcore.InMemoryConversationRepository
import ai.unalome.pocket.chatcore.InMemoryPersonaRepository
import ai.unalome.pocket.chatcore.PersonaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Personas (system turn, temperature, default model) and edit-and-resend, against a recording engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPersonaAndEditTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ConversationStore(InMemoryConversationRepository())
    private val personas = PersonaStore(InMemoryPersonaRepository())

    private class RecordingEngine(private val vision: Boolean = false) : ChatEngine {
        override val displayName = "Recorder"
        override val capabilities get() = EngineCapabilities(supportsVision = vision, maxContextTokens = 4096)
        val calls = mutableListOf<Triple<ChatTurn, List<ChatTurn>, GenerationOptions>>()
        override suspend fun load(progress: (Double) -> Unit) {}
        override suspend fun unload() {}
        override fun reply(turn: ChatTurn, history: List<ChatTurn>, options: GenerationOptions): Flow<GenerationEvent> = flow {
            calls += Triple(turn, history, options)
            emit(GenerationEvent.Token("Reply ${calls.size}"))
            emit(GenerationEvent.Done)
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(engine: ChatEngine, id: String? = null, selected: MutableList<String> = mutableListOf()) =
        ChatViewModel(id, store, EngineHost(engine), writeScope = this, now = { testScheduler.currentTime }, personas = personas, selectModel = { selected += it })

    @Test
    fun aPersonaIsTheSystemTurnWithItsTemperatureAndIsRemembered() = runTest(dispatcher) {
        personas.ensureStarters()
        val coder = personas.get("coder")!!.let { personas.update(it.copy(defaultModelId = "gemma-4-e2b-it-q4_0"))!! }
        val engine = RecordingEngine()
        val selected = mutableListOf<String>()
        val chat = viewModel(engine, selected = selected)

        chat.selectPersona(coder)
        assertEquals(listOf("gemma-4-e2b-it-q4_0"), selected)
        chat.onDraftChange("Reverse a list")
        chat.send()
        advanceUntilIdle()

        val (turn, history, options) = engine.calls.single()
        assertEquals("Reverse a list", turn.text)
        assertEquals(listOf(ChatTurn(ChatRole.SYSTEM, coder.systemPrompt)), history)
        assertEquals(0.2, options.temperature, 0.0)
        val id = chat.state.value.conversationId!!
        assertEquals("coder", store.conversation(id)!!.personaId)

        // Reopened, the chat still uses the persona, and an edit to it applies from the next reply.
        personas.update(coder.copy(systemPrompt = "Only Kotlin.", temperature = 0.5))
        val reopened = viewModel(engine, id)
        advanceUntilIdle()
        assertEquals("Coder", reopened.state.value.persona?.name)
        reopened.onDraftChange("And sort it")
        reopened.send()
        advanceUntilIdle()
        val (_, secondHistory, secondOptions) = engine.calls.last()
        assertEquals(ChatTurn(ChatRole.SYSTEM, "Only Kotlin."), secondHistory.first())
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT), secondHistory.map { it.role })
        assertEquals(0.5, secondOptions.temperature, 0.0)
    }

    @Test
    fun withoutAPersonaThereIsNoSystemTurn() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val chat = viewModel(engine)
        chat.onDraftChange("Hello")
        chat.send()
        advanceUntilIdle()
        assertTrue(engine.calls.single().second.isEmpty())
        assertEquals(GenerationOptions().temperature, engine.calls.single().third.temperature, 0.0)
    }

    @Test
    fun editAndResendReplacesTheLastExchange() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val chat = viewModel(engine)
        chat.onDraftChange("First question")
        chat.send()
        advanceUntilIdle()
        chat.onDraftChange("Second questoin")
        chat.send()
        advanceUntilIdle()

        chat.editLastMessage()
        assertEquals("Second questoin", chat.draft.value)
        assertTrue(chat.state.value.editingMessageId != null)
        chat.onDraftChange("Second question")
        chat.send()
        advanceUntilIdle()

        val messages = chat.state.value.messages
        assertEquals(listOf("First question", "Reply 1", "Second question", "Reply 3"), messages.map { it.text })
        assertNull(chat.state.value.editingMessageId)
        assertEquals(messages.map { it.text }, store.messages(chat.state.value.conversationId!!).map { it.text })
        // The model saw the first exchange only, not the replaced one.
        assertEquals(listOf("First question", "Reply 1"), engine.calls.last().second.map { it.text })
    }

    @Test
    fun cancellingAnEditRestoresAnEmptyComposer() = runTest(dispatcher) {
        val chat = viewModel(RecordingEngine())
        chat.onDraftChange("Question")
        chat.send()
        advanceUntilIdle()
        chat.editLastMessage()
        chat.cancelEditing()
        assertEquals("", chat.draft.value)
        assertNull(chat.state.value.editingMessageId)
        assertEquals(2, chat.state.value.messages.size)
    }
}
