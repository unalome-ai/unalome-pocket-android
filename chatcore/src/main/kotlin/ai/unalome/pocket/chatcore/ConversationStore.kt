package ai.unalome.pocket.chatcore

import kotlinx.coroutines.flow.Flow

/**
 * Creates, renames, deletes, searches and appends to conversations: the rules the iOS
 * `ConversationStore` has, over any [ConversationRepository].
 */
class ConversationStore(
    private val repository: ConversationRepository,
    /** Where messages' attached files live; they are deleted with their messages. */
    private val attachments: AttachmentFiles? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun conversations(query: String = ""): Flow<List<Conversation>> = repository.observeConversations(query)
    fun observeConversation(id: String): Flow<Conversation?> = repository.observeConversation(id)
    suspend fun conversation(id: String): Conversation? = repository.conversation(id)
    suspend fun messages(conversationId: String): List<Message> = repository.messages(conversationId)

    suspend fun createConversation(personaId: String? = null): Conversation {
        val conversation = Conversation(createdAt = now(), personaId = personaId)
        repository.insert(conversation)
        return conversation
    }

    /** Sets a user-chosen title; automatic titles stop. Blank titles are ignored. */
    suspend fun rename(conversationId: String, title: String): Conversation? {
        val trimmed = title.trim()
        val conversation = repository.conversation(conversationId) ?: return null
        if (trimmed.isEmpty()) return conversation
        val renamed = conversation.copy(title = trimmed, hasAutomaticTitle = false)
        repository.update(renamed)
        return renamed
    }

    suspend fun setThinkingEnabled(conversationId: String, enabled: Boolean) {
        val conversation = repository.conversation(conversationId) ?: return
        repository.update(conversation.copy(isThinkingEnabled = enabled))
    }

    suspend fun setPersona(conversationId: String, personaId: String?) {
        val conversation = repository.conversation(conversationId) ?: return
        repository.update(conversation.copy(personaId = personaId))
    }

    /** Deletes a conversation, its messages and their attached files. */
    suspend fun delete(conversationId: String) {
        val names = repository.messages(conversationId).flatMap { it.attachmentNames }
        repository.deleteConversation(conversationId)
        attachments?.delete(names)
    }

    /**
     * Appends a message after the existing ones. The first user message of a conversation with an
     * automatic title also sets the title.
     */
    suspend fun appendMessage(
        conversationId: String,
        role: ChatRole,
        text: String,
        modelName: String? = null,
        attachmentNames: List<String> = emptyList(),
    ): Message {
        val conversation = requireNotNull(repository.conversation(conversationId)) { "No conversation $conversationId" }
        val existing = repository.messages(conversationId)
        val time = now()
        val message = Message(
            conversationId = conversationId,
            role = role,
            text = text,
            position = (existing.maxOfOrNull { it.position } ?: -1) + 1,
            createdAt = time,
            modelName = modelName,
            attachmentNames = attachmentNames,
        )
        repository.insert(message)
        val isFirstUserMessage = role == ChatRole.USER && existing.none { it.role == ChatRole.USER }
        val title = if (isFirstUserMessage && conversation.hasAutomaticTitle) automaticTitle(text) else conversation.title
        repository.update(conversation.copy(title = title, updatedAt = time))
        return message
    }

    /** Persists in-place edits such as a finished streamed reply, and bumps the conversation. */
    suspend fun saveMessage(message: Message) {
        repository.update(message)
        repository.conversation(message.conversationId)?.let { repository.update(it.copy(updatedAt = now())) }
    }

    /**
     * Removes messages, e.g. a reply that is about to be regenerated, and their attached files
     * unless [keepAttachments] (a message edited and sent again reuses them).
     */
    suspend fun deleteMessages(messages: List<Message>, keepAttachments: Boolean = false) {
        repository.deleteMessages(messages.map { it.id })
        if (!keepAttachments) attachments?.delete(messages.flatMap { it.attachmentNames })
    }

    companion object {
        /** The first line of [text], trimmed and cut at a word boundary to at most [maxLength] characters. */
        fun automaticTitle(text: String, maxLength: Int = 40): String {
            val firstLine = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            if (firstLine.length <= maxLength) return firstLine
            // One extra character, so a word that ends exactly at the limit is kept.
            val candidate = firstLine.take(maxLength + 1)
            val boundary = candidate.indexOfLast { it.isWhitespace() }
            if (boundary <= 0) return firstLine.take(maxLength) + "…"
            return candidate.substring(0, boundary).trimEnd() + "…"
        }
    }
}
