package ai.unalome.pocket.chatcore.data

import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.Conversation
import ai.unalome.pocket.chatcore.Message
import ai.unalome.pocket.chatcore.Persona
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(tableName = "conversations", indices = [Index("updatedAt")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val hasAutomaticTitle: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val isThinkingEnabled: Boolean,
    val personaId: String?,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId", "position"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: ChatRole,
    val text: String,
    val reasoning: String,
    val reasoningMillis: Long?,
    val stoppedAtLengthLimit: Boolean,
    val wasInterrupted: Boolean,
    val attachmentNames: List<String>,
    val modelName: String?,
    val tokensPerSecond: Double?,
    val position: Int,
    val createdAt: Long,
)

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String,
    val temperature: Double,
    val defaultModelId: String?,
    val isBuiltIn: Boolean,
    val position: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

class Converters {
    @TypeConverter
    fun roleToString(role: ChatRole): String = role.name

    @TypeConverter
    fun stringToRole(value: String): ChatRole = ChatRole.valueOf(value)

    /** File names never contain a newline, so one name per line is enough. */
    @TypeConverter
    fun namesToString(names: List<String>): String = names.joinToString("\n")

    @TypeConverter
    fun stringToNames(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}

fun ConversationEntity.toModel() = Conversation(
    id = id, title = title, hasAutomaticTitle = hasAutomaticTitle, createdAt = createdAt,
    updatedAt = updatedAt, isThinkingEnabled = isThinkingEnabled, personaId = personaId,
)

fun Conversation.toEntity() = ConversationEntity(
    id = id, title = title, hasAutomaticTitle = hasAutomaticTitle, createdAt = createdAt,
    updatedAt = updatedAt, isThinkingEnabled = isThinkingEnabled, personaId = personaId,
)

fun MessageEntity.toModel() = Message(
    id = id, conversationId = conversationId, role = role, text = text, position = position,
    createdAt = createdAt, reasoning = reasoning, reasoningMillis = reasoningMillis,
    stoppedAtLengthLimit = stoppedAtLengthLimit, wasInterrupted = wasInterrupted,
    attachmentNames = attachmentNames, modelName = modelName, tokensPerSecond = tokensPerSecond,
)

fun Message.toEntity() = MessageEntity(
    id = id, conversationId = conversationId, role = role, text = text, reasoning = reasoning,
    reasoningMillis = reasoningMillis, stoppedAtLengthLimit = stoppedAtLengthLimit,
    wasInterrupted = wasInterrupted, attachmentNames = attachmentNames, modelName = modelName,
    tokensPerSecond = tokensPerSecond, position = position, createdAt = createdAt,
)

fun PersonaEntity.toModel() = Persona(
    id = id, name = name, systemPrompt = systemPrompt, temperature = temperature, defaultModelId = defaultModelId,
    isBuiltIn = isBuiltIn, position = position, createdAt = createdAt, updatedAt = updatedAt,
)

fun Persona.toEntity() = PersonaEntity(
    id = id, name = name, systemPrompt = systemPrompt, temperature = temperature, defaultModelId = defaultModelId,
    isBuiltIn = isBuiltIn, position = position, createdAt = createdAt, updatedAt = updatedAt,
)
