package ai.unalome.pocket.chatcore

import kotlinx.coroutines.flow.Flow

/**
 * The single boundary between the app and any inference runtime.
 * Feature code talks to this interface only; a real on-device engine replaces [ScriptedEngine] later.
 */
interface ChatEngine {
    /** Human-readable engine or model name, shown in the status pill. */
    val displayName: String
    val capabilities: EngineCapabilities

    /** Loads the model, reporting progress from 0 to 1. Safe to call when already loaded. */
    suspend fun load(progress: (Double) -> Unit = {})

    /** Frees the model's memory. */
    suspend fun unload()

    /**
     * Streams the reply to [turn]. [history] holds the earlier turns, oldest first.
     * Cancelling the collecting coroutine stops generation. The flow ends after [GenerationEvent.Done].
     */
    fun reply(turn: ChatTurn, history: List<ChatTurn>, options: GenerationOptions): Flow<GenerationEvent>
}

data class EngineCapabilities(
    val supportsVision: Boolean,
    val maxContextTokens: Int,
    /** The model can reason before it answers, switchable per reply. */
    val supportsThinking: Boolean = false,
)

enum class ChatRole { SYSTEM, USER, ASSISTANT }

/** One message as the engine sees it. */
data class ChatTurn(
    val role: ChatRole,
    val text: String,
    /** Image files attached to this turn (user turns only). Engines without vision ignore them. */
    val images: List<String> = emptyList(),
)

data class GenerationOptions(
    val temperature: Double = 0.7,
    val thinking: Boolean = false,
    val maxTokens: Int = if (thinking) THINKING_MAX_TOKENS else DEFAULT_MAX_TOKENS,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 1024
        const val THINKING_MAX_TOKENS = 4096
    }
}

sealed interface GenerationEvent {
    /** A piece of reply text, appended as-is. */
    data class Token(val text: String) : GenerationEvent

    /** A piece of the model's reasoning, shown apart from the reply and never sent back to it. */
    data class Reasoning(val text: String) : GenerationEvent

    /** Decode speed so far. */
    data class Stats(val tokensPerSecond: Double) : GenerationEvent

    /** The reply was cut off at [GenerationOptions.maxTokens]. Followed by [Done]. */
    data object LengthLimitReached : GenerationEvent

    /** The reply finished normally. */
    data object Done : GenerationEvent
}
