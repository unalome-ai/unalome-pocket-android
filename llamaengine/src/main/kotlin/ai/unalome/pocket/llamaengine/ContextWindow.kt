package ai.unalome.pocket.llamaengine

import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.ChatTurn

/** The new message alone does not fit the model's context. */
class PromptTooLongException(message: String) : Exception(message)

/** Fits a conversation into the model's context by dropping the oldest turns. Pure. */
object ContextWindow {
    /**
     * Returns [turns] (oldest first, the new user turn last) shortened until the prompt plus
     * [reserveTokens] for the reply fits [contextTokens]. System turns and the last turn are always
     * kept; older turns go oldest first, and the kept history always starts with a user turn,
     * which every chat template accepts. [count] gives the prompt's token count for a list of turns.
     */
    fun fit(
        turns: List<ChatTurn>,
        contextTokens: Int,
        reserveTokens: Int,
        count: (List<ChatTurn>) -> Int,
    ): List<ChatTurn> {
        require(turns.isNotEmpty())
        val budget = contextTokens - reserveTokens
        val system = turns.dropLast(1).filter { it.role == ChatRole.SYSTEM }
        val last = turns.last()
        var history = turns.dropLast(1).filter { it.role != ChatRole.SYSTEM }
        while (true) {
            val candidate = system + history + last
            if (count(candidate) <= budget) return candidate
            if (history.isEmpty()) {
                throw PromptTooLongException(
                    "This message is too long for the model's memory. Shorten it, or start a new chat.",
                )
            }
            history = history.drop(1).dropWhile { it.role != ChatRole.USER }
        }
    }

    /** Room kept for the reply: the requested length, but never more than a quarter of the context. */
    fun reserve(maxTokens: Int, contextTokens: Int): Int = minOf(maxTokens, contextTokens / 4)
}
