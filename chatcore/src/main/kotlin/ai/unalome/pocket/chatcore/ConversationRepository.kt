package ai.unalome.pocket.chatcore

import ai.unalome.pocket.chatcore.data.PocketDatabase
import ai.unalome.pocket.chatcore.data.toEntity
import ai.unalome.pocket.chatcore.data.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Persistence for conversations and messages. [ConversationStore] holds the rules on top. */
interface ConversationRepository {
    /** Conversations matching [query] in title or message text, most recent first. */
    fun observeConversations(query: String): Flow<List<Conversation>>
    fun observeConversation(id: String): Flow<Conversation?>
    suspend fun conversation(id: String): Conversation?
    suspend fun messages(conversationId: String): List<Message>
    suspend fun insert(conversation: Conversation)
    suspend fun update(conversation: Conversation)
    suspend fun deleteConversation(id: String)
    suspend fun insert(message: Message)
    suspend fun update(message: Message)
    suspend fun deleteMessages(ids: List<String>)
}

class RoomConversationRepository(private val database: PocketDatabase) : ConversationRepository {
    private val conversations = database.conversations()
    private val messages = database.messages()

    override fun observeConversations(query: String) =
        conversations.observeMatching(escapeLike(query.trim())).map { list -> list.map { it.toModel() } }

    override fun observeConversation(id: String) = conversations.observe(id).map { it?.toModel() }
    override suspend fun conversation(id: String) = conversations.get(id)?.toModel()
    override suspend fun messages(conversationId: String) = messages.messages(conversationId).map { it.toModel() }
    override suspend fun insert(conversation: Conversation) = conversations.insert(conversation.toEntity())
    override suspend fun update(conversation: Conversation) = conversations.update(conversation.toEntity())
    override suspend fun deleteConversation(id: String) = conversations.delete(id)
    override suspend fun insert(message: Message) = messages.insert(message.toEntity())
    override suspend fun update(message: Message) = messages.update(message.toEntity())
    override suspend fun deleteMessages(ids: List<String>) {
        if (ids.isNotEmpty()) messages.deleteIds(ids)
    }

    companion object {
        /** Escapes `LIKE` wildcards so a search for "50%" matches the text "50%". */
        fun escapeLike(query: String): String =
            query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

/** A repository in memory, for previews and tests. Search matches like the Room one, ignoring case. */
class InMemoryConversationRepository : ConversationRepository {
    private val state = MutableStateFlow(Snapshot())

    private data class Snapshot(
        val conversations: Map<String, Conversation> = emptyMap(),
        val messages: Map<String, Message> = emptyMap(),
    )

    override fun observeConversations(query: String): Flow<List<Conversation>> = state.map { snapshot ->
        val q = query.trim()
        snapshot.conversations.values
            .filter { conversation ->
                q.isEmpty() || conversation.title.contains(q, ignoreCase = true) ||
                    snapshot.messages.values.any { it.conversationId == conversation.id && it.text.contains(q, ignoreCase = true) }
            }
            .sortedByDescending { it.updatedAt }
    }

    override fun observeConversation(id: String) = state.map { it.conversations[id] }
    override suspend fun conversation(id: String) = state.value.conversations[id]
    override suspend fun messages(conversationId: String) =
        state.value.messages.values.filter { it.conversationId == conversationId }.sortedBy { it.position }

    override suspend fun insert(conversation: Conversation) = edit {
        require(conversation.id !in it.conversations) { "Duplicate conversation ${conversation.id}" }
        it.copy(conversations = it.conversations + (conversation.id to conversation))
    }

    override suspend fun update(conversation: Conversation) = edit {
        if (conversation.id !in it.conversations) it else it.copy(conversations = it.conversations + (conversation.id to conversation))
    }

    override suspend fun deleteConversation(id: String) = edit { snapshot ->
        snapshot.copy(
            conversations = snapshot.conversations - id,
            messages = snapshot.messages.filterValues { it.conversationId != id },
        )
    }

    override suspend fun insert(message: Message) = edit {
        require(message.conversationId in it.conversations) { "No conversation ${message.conversationId}" }
        it.copy(messages = it.messages + (message.id to message))
    }

    override suspend fun update(message: Message) = edit {
        if (message.id !in it.messages) it else it.copy(messages = it.messages + (message.id to message))
    }

    override suspend fun deleteMessages(ids: List<String>) = edit { it.copy(messages = it.messages - ids.toSet()) }

    private fun edit(change: (Snapshot) -> Snapshot) {
        synchronized(this) { state.value = change(state.value) }
    }
}

class RoomPersonaRepository(database: PocketDatabase) : PersonaRepository {
    private val personas = database.personas()
    override fun observeAll() = personas.observeAll().map { list -> list.map { it.toModel() } }
    override suspend fun all() = personas.all().map { it.toModel() }
    override suspend fun get(id: String) = personas.get(id)?.toModel()
    override suspend fun upsert(persona: Persona) = personas.upsert(persona.toEntity())
    override suspend fun delete(id: String) = personas.delete(id)
}
