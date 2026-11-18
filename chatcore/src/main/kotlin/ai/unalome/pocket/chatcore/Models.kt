package ai.unalome.pocket.chatcore

import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    /** Empty until the first user message gives it an automatic title. */
    val title: String = "",
    /** `false` once the user renames the conversation; automatic titles then stop. */
    val hasAutomaticTitle: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /** Whether replies in this chat may reason first, for models that support it. */
    val isThinkingEnabled: Boolean = false,
    /** The persona this chat uses; `null` for none. An id, so deleting a persona leaves chats intact. */
    val personaId: String? = null,
) {
    /** The title to show; untitled conversations read "New chat". */
    val displayTitle: String get() = title.ifEmpty { "New chat" }
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: ChatRole,
    val text: String,
    /** Insertion order within the conversation. */
    val position: Int,
    val createdAt: Long,
    /** The model's reasoning before [text], if it showed any. Never sent back to the model. */
    val reasoning: String = "",
    /** How long the model reasoned, once it started answering or stopped. */
    val reasoningMillis: Long? = null,
    /** The reply was cut off at the length limit. */
    val stoppedAtLengthLimit: Boolean = false,
    /** The reply ended early (stopped, interrupted or failed); the text so far is kept. */
    val wasInterrupted: Boolean = false,
    /** Attached files, as names in a future attachment store. */
    val attachmentNames: List<String> = emptyList(),
    /** Engine or model that wrote this reply (assistant messages). */
    val modelName: String? = null,
    /** Decode speed of this reply (assistant messages). */
    val tokensPerSecond: Double? = null,
) {
    /** What the engine sees: the text only. Reasoning stays out of the history. */
    val turn: ChatTurn get() = ChatTurn(role, text, attachmentNames)
}
