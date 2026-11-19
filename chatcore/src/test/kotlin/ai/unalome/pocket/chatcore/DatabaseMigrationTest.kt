package ai.unalome.pocket.chatcore

import ai.unalome.pocket.chatcore.data.PocketDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrades a version 1 database (milestones 1 and 2) to version 2, against the exported schemas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PocketDatabase::class.java)

    @Test
    fun version1ChatsSurviveAndPersonasWork() = runBlocking {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO conversations (id, title, hasAutomaticTitle, createdAt, updatedAt, isThinkingEnabled, personaId) " +
                    "VALUES ('c1', 'Old chat', 1, 10, 20, 1, NULL)",
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, role, text, reasoning, reasoningMillis, stoppedAtLengthLimit, wasInterrupted, " +
                    "attachmentNames, modelName, tokensPerSecond, position, createdAt) " +
                    "VALUES ('m1', 'c1', 'USER', 'Hello', '', NULL, 0, 0, '', NULL, NULL, 0, 10)",
            )
        }
        // Validates the migrated schema against 2.json.
        helper.runMigrationsAndValidate(name, 2, true, PocketDatabase.MIGRATION_1_2).close()

        val database = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), PocketDatabase::class.java, name)
            .addMigrations(*PocketDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val chats = ConversationStore(RoomConversationRepository(database))
            assertEquals("Old chat", chats.conversation("c1")!!.title)
            assertEquals(listOf("Hello"), chats.messages("c1").map { it.text })

            val personas = PersonaStore(RoomPersonaRepository(database))
            personas.ensureStarters()
            assertEquals(4, personas.all().size)
            chats.setPersona("c1", "coder")
            assertEquals("coder", chats.conversation("c1")!!.personaId)
        } finally {
            database.close()
        }
    }
}
