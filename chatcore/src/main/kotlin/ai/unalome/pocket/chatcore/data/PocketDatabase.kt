package ai.unalome.pocket.chatcore.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    /**
     * Conversations whose title or any message contains [query] (case-insensitive for ASCII),
     * most recent first. A blank query returns every conversation. `%`, `_` and `\` in the query
     * are escaped by the caller.
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE :query = ''
           OR title LIKE '%' || :query || '%' ESCAPE '\'
           OR id IN (SELECT conversationId FROM messages WHERE text LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY updatedAt DESC
        """,
    )
    fun observeMatching(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY position")
    suspend fun messages(conversationId: String): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<String>)
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class PocketDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao

    companion object {
        fun open(context: Context): PocketDatabase =
            Room.databaseBuilder(context, PocketDatabase::class.java, "pocket.db").build()

        fun inMemory(context: Context): PocketDatabase =
            Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java).allowMainThreadQueries().build()
    }
}
