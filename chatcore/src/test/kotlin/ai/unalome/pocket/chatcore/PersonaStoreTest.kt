package ai.unalome.pocket.chatcore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaStoreTest {
    private var clock = 100L
    private val repository = InMemoryPersonaRepository()
    private val store = PersonaStore(repository) { clock++ }

    @Test
    fun startersAreSeededOnceInOrderAndMatchTheIphoneApp() = runBlocking {
        store.ensureStarters()
        store.ensureStarters()
        val all = store.all()
        assertEquals(listOf("Assistant", "Writing editor", "Explainer", "Coder"), all.map { it.name })
        assertEquals(listOf(0.7, 0.4, 0.6, 0.2), all.map { it.temperature })
        assertTrue(all.all { it.isBuiltIn && it.defaultModelId == null })
        assertTrue(all[0].systemPrompt.startsWith("You are a helpful assistant running entirely on the user's device."))
    }

    @Test
    fun createEditDuplicateAndDelete() = runBlocking {
        store.ensureStarters()
        val pirate = store.create("  Pirate  ", "Answer like a pirate.\n", 2.3, "gemma-4-e2b-it-q4_0")
        assertEquals("Pirate", pirate.name)
        assertEquals("Answer like a pirate.", pirate.systemPrompt)
        assertEquals(1.5, pirate.temperature, 0.0) // clamped
        assertFalse(pirate.isBuiltIn)

        val edited = store.update(pirate.copy(name = "Captain", temperature = 0.33, isBuiltIn = true))!!
        assertEquals("Captain", edited.name)
        assertEquals(0.3, edited.temperature, 0.0)
        assertFalse("a user persona cannot make itself built-in", edited.isBuiltIn)

        val copy = store.duplicate(pirate.id)!!
        assertNotEquals(pirate.id, copy.id)
        assertEquals("Captain copy", copy.name)
        assertEquals("gemma-4-e2b-it-q4_0", copy.defaultModelId)

        // Starters first, then the user's in the order they were made.
        assertEquals(listOf("Assistant", "Writing editor", "Explainer", "Coder", "Captain", "Captain copy"), store.personas().first().map { it.name })

        assertTrue(store.delete(pirate.id))
        assertNull(store.get(pirate.id))
    }

    @Test
    fun startersCanBeEditedAndResetButNotDeleted() = runBlocking {
        store.ensureStarters()
        val coder = store.get("coder")!!
        val edited = store.update(coder.copy(systemPrompt = "Only Kotlin.", temperature = 0.9))!!
        assertTrue(edited.isBuiltIn)
        assertTrue(store.isModifiedStarter(edited))

        assertFalse(store.delete("coder"))
        assertEquals("Only Kotlin.", store.get("coder")!!.systemPrompt)

        val restored = store.reset("coder")!!
        assertEquals(StarterPersonas.starter("coder")!!.systemPrompt, restored.systemPrompt)
        assertEquals(0.2, restored.temperature, 0.0)
        assertFalse(store.isModifiedStarter(restored))
        assertEquals(4, store.all().size)
    }

    @Test
    fun aMissingStarterComesBack() = runBlocking {
        store.ensureStarters()
        repository.delete("explainer") // e.g. a database from a build that did not ship it
        store.ensureStarters()
        assertEquals("Explainer", store.get("explainer")!!.name)
    }
}
