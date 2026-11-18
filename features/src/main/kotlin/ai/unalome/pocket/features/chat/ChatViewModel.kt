package ai.unalome.pocket.features.chat

import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.ConversationStore
import ai.unalome.pocket.chatcore.EngineHost
import ai.unalome.pocket.chatcore.GenerationEvent
import ai.unalome.pocket.chatcore.GenerationOptions
import ai.unalome.pocket.chatcore.Message
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversationId: String? = null,
    val title: String = "New chat",
    val messages: List<Message> = emptyList(),
    val isLoaded: Boolean = false,
    val isGenerating: Boolean = false,
    /** The assistant message receiving tokens right now. */
    val streamingMessageId: String? = null,
    /** Decode speed of the current or most recent reply. */
    val tokensPerSecond: Double? = null,
    /** When the streaming reply started reasoning (ms), for "Thinking… N s". */
    val reasoningStartedAt: Long? = null,
    val isThinkingEnabled: Boolean = false,
    val errorMessage: String? = null,
) {
    val lastUserMessage: Message? get() = messages.lastOrNull { it.role == ChatRole.USER }
    val canRegenerate: Boolean get() = !isGenerating && lastUserMessage != null
}

/**
 * Drives one conversation: send, stream, stop and regenerate. Depends on the [EngineHost]'s
 * engine interface only. A new chat ([conversationId] `null`) is created on the first send, so
 * opening and leaving an empty chat stores nothing.
 */
class ChatViewModel(
    conversationId: String?,
    private val store: ConversationStore,
    private val host: EngineHost,
    /** Writes that must outlive this view model (the reply saved after Stop or leaving). */
    private val writeScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState(conversationId = conversationId, isLoaded = conversationId == null))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    val engineName: String get() = host.engine.displayName
    val supportsThinking: Boolean get() = host.engine.capabilities.supportsThinking
    val engineState get() = host.state

    private var generation: Generation? = null
    private var generationCounter = 0

    private class Generation(val id: Int, val job: Job)

    init {
        if (conversationId != null) viewModelScope.launch { load(conversationId) }
    }

    private suspend fun load(id: String) {
        val conversation = store.conversation(id)
        // A reply left empty by a process that ended mid-stream is dropped; the rest is kept.
        val (empty, kept) = store.messages(id).partition { it.role == ChatRole.ASSISTANT && it.text.isEmpty() && it.reasoning.isEmpty() }
        if (empty.isNotEmpty()) store.deleteMessages(empty)
        _state.update {
            it.copy(
                title = conversation?.displayTitle ?: "New chat",
                messages = kept,
                isThinkingEnabled = conversation?.isThinkingEnabled ?: false,
                tokensPerSecond = kept.lastOrNull { m -> m.role == ChatRole.ASSISTANT }?.tokensPerSecond,
                isLoaded = true,
            )
        }
    }

    fun onDraftChange(text: String) {
        _draft.value = text
    }

    val canSend: Boolean get() = !_state.value.isGenerating && _state.value.isLoaded && _draft.value.isNotBlank()

    fun send() {
        if (!canSend) return
        val text = _draft.value.trim()
        _draft.value = ""
        startGeneration {
            val id = ensureConversation()
            val user = store.appendMessage(id, ChatRole.USER, text)
            val title = store.conversation(id)?.displayTitle ?: "New chat"
            _state.update { it.copy(messages = it.messages + user, title = title) }
            generate(id)
        }
    }

    /** Drops everything after the last user message and asks the engine again. */
    fun regenerate() {
        val current = _state.value
        val lastUser = current.lastUserMessage ?: return
        val id = current.conversationId ?: return
        if (!current.canRegenerate) return
        startGeneration {
            val later = _state.value.messages.filter { it.position > lastUser.position }
            store.deleteMessages(later)
            _state.update { state -> state.copy(messages = state.messages.filter { it.position <= lastUser.position }) }
            generate(id)
        }
    }

    /** Stops the reply. The text streamed so far is kept and saved. */
    fun stop() {
        val running = generation ?: return
        generation = null
        running.job.cancel()
        _state.update { state ->
            val replyId = state.streamingMessageId
            state.copy(
                isGenerating = false,
                streamingMessageId = null,
                reasoningStartedAt = null,
                messages = state.messages.mapNotNull { message ->
                    when {
                        message.id != replyId -> message
                        message.text.isEmpty() && message.reasoning.isEmpty() -> null
                        else -> finalized(message, completed = false, state)
                    }
                },
            )
        }
    }

    fun setThinkingEnabled(enabled: Boolean) {
        _state.update { it.copy(isThinkingEnabled = enabled) }
        val id = _state.value.conversationId ?: return
        writeScope.launch { store.setThinkingEnabled(id, enabled) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private suspend fun ensureConversation(): String {
        _state.value.conversationId?.let { return it }
        val conversation = store.createConversation()
        if (_state.value.isThinkingEnabled) store.setThinkingEnabled(conversation.id, true)
        _state.update { it.copy(conversationId = conversation.id) }
        return conversation.id
    }

    private fun startGeneration(block: suspend () -> Unit) {
        val id = ++generationCounter
        _state.update { it.copy(isGenerating = true, errorMessage = null, tokensPerSecond = null, reasoningStartedAt = null) }
        val job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message ?: "Something went wrong.") }
            } finally {
                if (generation?.id == id) {
                    generation = null
                    _state.update { it.copy(isGenerating = false, streamingMessageId = null, reasoningStartedAt = null) }
                }
            }
        }
        generation = Generation(id, job)
    }

    private suspend fun generate(conversationId: String) {
        val history = _state.value.messages
        val turn = history.last().turn
        val earlier = history.dropLast(1).map { it.turn }
        var reply = store.appendMessage(conversationId, ChatRole.ASSISTANT, "", modelName = host.engine.displayName)
        _state.update { it.copy(messages = it.messages + reply, streamingMessageId = reply.id) }
        var completed = false
        try {
            host.ensureLoaded()
            val options = GenerationOptions(thinking = supportsThinking && _state.value.isThinkingEnabled)
            host.engine.reply(turn, earlier, options).collect { event ->
                reply = apply(event, reply)
            }
            completed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.message ?: "The reply stopped because of an error.") }
        } finally {
            persist(reply, completed)
        }
    }

    private fun apply(event: GenerationEvent, reply: Message): Message {
        val updated = when (event) {
            is GenerationEvent.Token -> endReasoning(reply).copy(text = reply.text + event.text)
            is GenerationEvent.Reasoning -> {
                if (_state.value.reasoningStartedAt == null) _state.update { it.copy(reasoningStartedAt = now()) }
                reply.copy(reasoning = reply.reasoning + event.text)
            }
            is GenerationEvent.Stats -> {
                _state.update { it.copy(tokensPerSecond = event.tokensPerSecond) }
                reply.copy(tokensPerSecond = event.tokensPerSecond)
            }
            GenerationEvent.LengthLimitReached -> reply.copy(stoppedAtLengthLimit = true)
            GenerationEvent.Done -> reply
        }
        if (updated != reply) {
            _state.update { state -> state.copy(messages = state.messages.map { if (it.id == updated.id) updated else it }) }
        }
        return updated
    }

    private fun endReasoning(reply: Message): Message {
        val started = _state.value.reasoningStartedAt ?: return reply
        if (reply.reasoningMillis != null) return reply
        return reply.copy(reasoningMillis = now() - started)
    }

    private fun finalized(reply: Message, completed: Boolean, state: ChatUiState): Message {
        val withReasoning = state.reasoningStartedAt?.let { started ->
            if (reply.reasoningMillis == null && reply.reasoning.isNotEmpty()) reply.copy(reasoningMillis = now() - started) else reply
        } ?: reply
        return withReasoning.copy(
            wasInterrupted = !completed && !reply.stoppedAtLengthLimit,
            tokensPerSecond = state.tokensPerSecond ?: reply.tokensPerSecond,
        )
    }

    /**
     * Saves the reply (or removes it if nothing was written) on [writeScope], so it is kept even
     * when the screen is gone. The UI state was already updated by [stop] if it ran.
     */
    private fun persist(reply: Message, completed: Boolean) {
        val state = _state.value
        val shown = state.messages.firstOrNull { it.id == reply.id }
        val final = shown?.takeIf { !completed && generation == null && it.wasInterrupted } ?: finalized(reply, completed, state)
        val isEmpty = final.text.isEmpty() && final.reasoning.isEmpty()
        _state.update { s ->
            s.copy(messages = s.messages.mapNotNull { if (it.id == final.id) final.takeUnless { isEmpty } else it })
        }
        writeScope.launch {
            if (isEmpty) store.deleteMessages(listOf(final)) else store.saveMessage(final)
        }
    }

    override fun onCleared() {
        // Leaving the conversation stops the reply; what was written so far is saved by `persist`.
        generation?.job?.cancel()
    }
}
