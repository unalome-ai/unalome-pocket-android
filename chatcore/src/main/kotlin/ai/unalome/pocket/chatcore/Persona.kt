package ai.unalome.pocket.chatcore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * A saved way to start a chat: a system prompt, a temperature and, optionally, a model. Chats keep
 * the persona's id, so editing a persona changes the chats that use it from their next reply on.
 */
data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Sent to the model as the SYSTEM turn, before the conversation. */
    val systemPrompt: String,
    /** 0 to 1.5; lower is more focused and repeatable, higher more varied. */
    val temperature: Double = 0.7,
    /** A catalog model id, chosen when a chat starts with this persona if it is installed. */
    val defaultModelId: String? = null,
    /** One of the starters the app ships: it can be edited and reset, but not deleted. */
    val isBuiltIn: Boolean = false,
    /** Display order; starters come first. */
    val position: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = createdAt,
)

/** The starter personas, the same as the iPhone app's. No companion or romantic personas. */
object StarterPersonas {
    val all: List<Persona> = listOf(
        Persona(
            id = "assistant",
            name = "Assistant",
            systemPrompt = "You are a helpful assistant running entirely on the user's device. Answer clearly and directly. " +
                "Prefer short answers; use lists or tables when they make things easier to scan. If you are not sure about something, say so.",
            temperature = 0.7,
        ),
        Persona(
            id = "editor",
            name = "Writing editor",
            systemPrompt = "You are a careful editor. Improve the user's text for clarity, grammar and flow while keeping their meaning, " +
                "tone and language. Return the edited text first, then a short list of the main changes. Do not add new content unless asked.",
            temperature = 0.4,
        ),
        Persona(
            id = "explainer",
            name = "Explainer",
            systemPrompt = "You explain things simply. Start with a one-sentence answer, then build up step by step with plain words and " +
                "a concrete example. Define any technical term the first time you use it. Check understanding by ending with one short " +
                "question the user could try.",
            temperature = 0.6,
        ),
        Persona(
            id = "coder",
            name = "Coder",
            systemPrompt = "You are an experienced software engineer. Give working, idiomatic code in fenced code blocks with the " +
                "language named. Keep explanations brief and focused on why. Point out edge cases and anything the user should test.",
            temperature = 0.2,
        ),
    ).mapIndexed { index, persona -> persona.copy(isBuiltIn = true, position = index) }

    fun starter(id: String): Persona? = all.firstOrNull { it.id == id }
}

interface PersonaRepository {
    fun observeAll(): Flow<List<Persona>>
    suspend fun all(): List<Persona>
    suspend fun get(id: String): Persona?
    suspend fun upsert(persona: Persona)
    suspend fun delete(id: String)
}

/** Creates, edits, duplicates, deletes and resets personas; keeps the starters present. */
class PersonaStore(
    private val repository: PersonaRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Starters first in their order, then the user's in the order they were made. */
    fun personas(): Flow<List<Persona>> = repository.observeAll().map(::sorted)

    suspend fun all(): List<Persona> = sorted(repository.all())

    suspend fun get(id: String): Persona? = repository.get(id)

    /** Adds any starter that is missing (first launch, or a starter added by an update). */
    suspend fun ensureStarters() {
        val existing = repository.all().map { it.id }.toSet()
        val time = now()
        StarterPersonas.all.filter { it.id !in existing }.forEach { repository.upsert(it.copy(createdAt = time, updatedAt = time)) }
    }

    suspend fun create(name: String, systemPrompt: String, temperature: Double, defaultModelId: String?): Persona {
        val time = now()
        val position = (repository.all().maxOfOrNull { it.position } ?: -1) + 1
        val persona = Persona(
            name = cleanName(name), systemPrompt = systemPrompt.trim(), temperature = clampTemperature(temperature),
            defaultModelId = defaultModelId, position = position, createdAt = time,
        )
        repository.upsert(persona)
        return persona
    }

    /** Saves edits; a built-in stays built-in, whatever the caller passes. */
    suspend fun update(persona: Persona): Persona? {
        val current = repository.get(persona.id) ?: return null
        val updated = persona.copy(
            name = cleanName(persona.name),
            systemPrompt = persona.systemPrompt.trim(),
            temperature = clampTemperature(persona.temperature),
            isBuiltIn = current.isBuiltIn,
            position = current.position,
            createdAt = current.createdAt,
            updatedAt = now(),
        )
        repository.upsert(updated)
        return updated
    }

    /** A user persona with the same settings, named "<name> copy". */
    suspend fun duplicate(id: String): Persona? {
        val source = repository.get(id) ?: return null
        return create("${source.name} copy", source.systemPrompt, source.temperature, source.defaultModelId)
    }

    /** Deletes a user persona. Starters cannot be deleted; returns whether anything was deleted. */
    suspend fun delete(id: String): Boolean {
        val persona = repository.get(id) ?: return false
        if (persona.isBuiltIn) return false
        repository.delete(id)
        return true
    }

    /** Restores a starter's shipped name, prompt and temperature. */
    suspend fun reset(id: String): Persona? {
        val starter = StarterPersonas.starter(id) ?: return null
        val current = repository.get(id)
        val time = now()
        val restored = starter.copy(createdAt = current?.createdAt ?: time, updatedAt = time)
        repository.upsert(restored)
        return restored
    }

    /** The starter's shipped version, if [persona] is a starter that was changed. */
    fun isModifiedStarter(persona: Persona): Boolean {
        val starter = StarterPersonas.starter(persona.id) ?: return false
        return persona.isBuiltIn && (persona.name != starter.name || persona.systemPrompt != starter.systemPrompt ||
            persona.temperature != starter.temperature || persona.defaultModelId != starter.defaultModelId)
    }

    private fun sorted(list: List<Persona>) = list.sortedWith(compareBy({ !it.isBuiltIn }, { it.position }, { it.createdAt }))

    companion object {
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 1.5

        fun clampTemperature(value: Double): Double =
            (Math.round(value.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE) * 10) / 10.0)

        private fun cleanName(name: String) = name.trim().ifEmpty { "Untitled persona" }
    }
}

class InMemoryPersonaRepository : PersonaRepository {
    private val state = MutableStateFlow<Map<String, Persona>>(emptyMap())
    override fun observeAll(): Flow<List<Persona>> = state.map { it.values.toList() }
    override suspend fun all() = state.value.values.toList()
    override suspend fun get(id: String) = state.value[id]
    override suspend fun upsert(persona: Persona) = state.update { it + (persona.id to persona) }
    override suspend fun delete(id: String) = state.update { it - id }
}
