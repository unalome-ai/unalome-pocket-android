package ai.unalome.pocket.features.chat

import ai.unalome.pocket.chatcore.AttachmentStore
import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.ChatTurn
import ai.unalome.pocket.chatcore.ConversationStore
import ai.unalome.pocket.chatcore.EngineHost
import ai.unalome.pocket.chatcore.GenerationEvent
import ai.unalome.pocket.chatcore.GenerationOptions
import ai.unalome.pocket.chatcore.Message
import ai.unalome.pocket.chatcore.Persona
import ai.unalome.pocket.chatcore.PersonaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

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
    /** The chat's persona; for a new chat, the one chosen for it before the first message. */
    val persona: Persona? = null,
    /** Images attached to the message being written, as attachment names. */
    val draftImages: List<String> = emptyList(),
    /** An image is being stored. */
    val isAddingImage: Boolean = false,
    /** The last user message is being edited; sending replaces it and everything after it. */
    val editingMessageId: String? = null,
) {
    val lastUserMessage: Message? get() = messages.lastOrNull { it.role == ChatRole.USER }
    val canRegenerate: Boolean get() = !isGenerating && lastUserMessage != null
    val canAddImage: Boolean get() = draftImages.size < AttachmentStore.MAX_PER_MESSAGE && !isAddingImage
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
    /** Called with the decode speed of each reply that ran to the end, to record it per model. */
    private val onReplyFinished: (tokensPerSecond: Double) -> Unit = {},
    /** Where attached images are stored; `null` turns images off. */
    private val attachments: AttachmentStore? = null,
    private val personas: PersonaStore? = null,
    /** Switches to a persona's default model, if it is installed. */
    private val selectModel: (modelId: String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState(conversationId = conversationId, isLoaded = conversationId == null))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    val engineName: String get() = host.engine.displayName
    val supportsThinking: Boolean get() = host.engine.capabilities.supportsThinking
    val engineState get() = host.state
    /** The engine in use reads images. */
    val supportsImages: Boolean get() = attachments != null && host.engine.capabilities.supportsVision

    /** Every persona, starters first, for the picker. */
    val personaList: StateFlow<List<Persona>> = (personas?.personas() ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var generation: Generation? = null
    private var generationCounter = 0

    private class Generation(val id: Int, val job: Job)

    init {
        if (conversationId != null) viewModelScope.launch { load(conversationId) }
    }

    private suspend fun load(id: String) {
        val conversation = store.conversation(id)
        val persona = conversation?.personaId?.let { personas?.get(it) }
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
                persona = persona,
            )
        }
    }

    fun onDraftChange(text: String) {
        _draft.value = text
    }

    val canSend: Boolean
        get() = !_state.value.isGenerating && _state.value.isLoaded && !_state.value.isAddingImage &&
            (_draft.value.isNotBlank() || _state.value.draftImages.isNotEmpty())

    fun send() {
        if (!canSend) return
        val images = _state.value.draftImages
        if (images.isNotEmpty() && !supportsImages) {
            _state.update {
                it.copy(errorMessage = "$engineName cannot read images. Choose a model with image support, or remove the image.")
            }
            return
        }
        // A message with only an image still asks something.
        val text = _draft.value.trim().ifEmpty { "Describe this image." }
        val editing = _state.value.editingMessageId
        _draft.value = ""
        _state.update { it.copy(draftImages = emptyList(), editingMessageId = null) }
        startGeneration {
            val id = ensureConversation()
            if (editing != null) {
                // Edit and resend: the edited message and everything after it are replaced.
                val edited = _state.value.messages.firstOrNull { it.id == editing }
                if (edited != null) {
                    val later = _state.value.messages.filter { it.position >= edited.position }
                    val kept = images.toSet()
                    store.deleteMessages(later, keepAttachments = true)
                    attachments?.delete(later.flatMap { it.attachmentNames }.filter { it !in kept })
                    _state.update { state -> state.copy(messages = state.messages.filter { it.position < edited.position }) }
                }
            }
            val user = store.appendMessage(id, ChatRole.USER, text, attachmentNames = images)
            val title = store.conversation(id)?.displayTitle ?: "New chat"
            _state.update { it.copy(messages = it.messages + user, title = title) }
            generate(id)
        }
    }

    /**
     * Stores picked or photographed images, one after another, and adds them to the draft, up to
     * [AttachmentStore.MAX_PER_MESSAGE]. [afterwards] runs when they are stored (e.g. to delete a
     * camera photo's temporary file).
     */
    fun addImages(openers: List<() -> InputStream>, afterwards: () -> Unit = {}) {
        val store = attachments
        if (store == null || _state.value.isAddingImage) return afterwards()
        val room = AttachmentStore.MAX_PER_MESSAGE - _state.value.draftImages.size
        val accepted = openers.take(room.coerceAtLeast(0))
        if (accepted.isEmpty()) return afterwards()
        _state.update { it.copy(isAddingImage = true) }
        viewModelScope.launch {
            try {
                for (open in accepted) {
                    val name = store.import(open)
                    _state.update { it.copy(draftImages = it.draftImages + name) }
                }
                if (openers.size > accepted.size) {
                    _state.update { it.copy(errorMessage = "A message can carry up to ${AttachmentStore.MAX_PER_MESSAGE} images.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "The image could not be added. ${e.message ?: ""}".trim()) }
            } finally {
                _state.update { it.copy(isAddingImage = false) }
                afterwards()
            }
        }
    }

    /** Removes an image from the draft, and its file unless an earlier message uses it. */
    fun removeImage(name: String) {
        _state.update { it.copy(draftImages = it.draftImages - name) }
        if (_state.value.messages.none { name in it.attachmentNames }) attachments?.delete(listOf(name))
    }

    /** The image's file, for thumbnails. */
    fun imagePath(name: String): String? = attachments?.paths(listOf(name))?.firstOrNull()

    /** Puts the last user message back in the composer; sending replaces it and the reply. */
    fun editLastMessage() {
        val state = _state.value
        if (state.isGenerating) return
        val last = state.lastUserMessage ?: return
        _draft.value = last.text
        _state.update { it.copy(editingMessageId = last.id, draftImages = last.attachmentNames) }
    }

    fun cancelEditing() {
        if (_state.value.editingMessageId == null) return
        _draft.value = ""
        _state.update { it.copy(editingMessageId = null, draftImages = emptyList()) }
    }

    /**
     * Chooses the chat's persona (`null` for none). Its default model is selected if installed.
     * A new chat keeps the choice until its first message creates it.
     */
    fun selectPersona(persona: Persona?) {
        _state.update { it.copy(persona = persona) }
        persona?.defaultModelId?.let(selectModel)
        val id = _state.value.conversationId ?: return
        writeScope.launch { store.setPersona(id, persona?.id) }
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
        val conversation = store.createConversation(personaId = _state.value.persona?.id)
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
        val resolve: (List<String>) -> List<String> = { names -> attachments?.paths(names) ?: emptyList() }
        val turn = history.last().turn(resolve)
        // The persona's prompt is the system turn, which the context window always keeps. It is
        // read fresh, so an edited persona applies from the next reply on.
        val persona = _state.value.persona?.let { personas?.get(it.id) ?: it }
        val system = persona?.systemPrompt?.takeIf { it.isNotBlank() }?.let { listOf(ChatTurn(ChatRole.SYSTEM, it)) }.orEmpty()
        val earlier = system + history.dropLast(1).map { it.turn(resolve) }
        var reply = store.appendMessage(conversationId, ChatRole.ASSISTANT, "", modelName = host.engine.displayName)
        _state.update { it.copy(messages = it.messages + reply, streamingMessageId = reply.id) }
        var completed = false
        try {
            host.ensureLoaded()
            val options = GenerationOptions(
                temperature = persona?.temperature ?: GenerationOptions().temperature,
                thinking = supportsThinking && _state.value.isThinkingEnabled,
            )
            host.engine.reply(turn, earlier, options).collect { event ->
                reply = apply(event, reply)
            }
            completed = true
            reply.tokensPerSecond?.let(onReplyFinished)
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
