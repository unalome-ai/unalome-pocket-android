package ai.unalome.pocket.llamaengine

import ai.unalome.pocket.chatcore.ChatRole
import ai.unalome.pocket.chatcore.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContextWindowTest {
    // One "token" per character keeps the arithmetic visible.
    private val count: (List<ChatTurn>) -> Int = { turns -> turns.sumOf { it.text.length } }
    private fun user(text: String) = ChatTurn(ChatRole.USER, text)
    private fun assistant(text: String) = ChatTurn(ChatRole.ASSISTANT, text)
    private val system = ChatTurn(ChatRole.SYSTEM, "sys")

    @Test
    fun keepsEverythingThatFits() {
        val turns = listOf(system, user("aaaa"), assistant("bbbb"), user("cc"))
        assertEquals(turns, ContextWindow.fit(turns, contextTokens = 100, reserveTokens = 10, count = count))
    }

    @Test
    fun dropsTheOldestExchangeFirstAndKeepsTheSystemPrompt() {
        val turns = listOf(system, user("1111111111"), assistant("2222222222"), user("3333"), assistant("4444"), user("55"))
        // budget 20: sys(3) + 3333 + 4444 + 55 = 13 fits; adding the first exchange (20 more) does not
        assertEquals(
            listOf(system, user("3333"), assistant("4444"), user("55")),
            ContextWindow.fit(turns, contextTokens = 30, reserveTokens = 10, count = count),
        )
    }

    @Test
    fun keptHistoryAlwaysStartsWithAUserTurn() {
        val turns = listOf(user("1111"), assistant("22"), assistant("33"), user("4"), assistant("5"), user("6"))
        val fitted = ContextWindow.fit(turns, contextTokens = 6, reserveTokens = 1, count = count)
        assertEquals(listOf(user("4"), assistant("5"), user("6")), fitted)
    }

    @Test
    fun aMessageTooLongOnItsOwnIsAReadableError() {
        val turns = listOf(system, user("x".repeat(50)))
        val error = assertThrows(PromptTooLongException::class.java) {
            ContextWindow.fit(turns, contextTokens = 40, reserveTokens = 10, count = count)
        }
        assertEquals("This message is too long for the model's memory. Shorten it, or start a new chat.", error.message)
    }

    @Test
    fun replyReserveIsCappedAtAQuarterOfTheContext() {
        assertEquals(1024, ContextWindow.reserve(maxTokens = 1024, contextTokens = 8192))
        assertEquals(1024, ContextWindow.reserve(maxTokens = 4096, contextTokens = 4096))
    }

    @Test
    fun deviceTuning() {
        val gib = 1L shl 30
        assertEquals(4096, DeviceTuning.contextTokens((2.4 * gib).toLong()))
        assertEquals(4096, DeviceTuning.contextTokens((7.4 * gib).toLong()))
        assertEquals(8192, DeviceTuning.contextTokens((11.2 * gib).toLong()))

        // 2 + 3 + 3 layout (e.g. 1 prime, 3 big, 4 little): the 4 faster cores
        assertEquals(4, DeviceTuning.threads(listOf(2_000_000L, 2_000_000, 2_000_000, 2_000_000, 2_800_000, 2_800_000, 2_800_000, 3_300_000), 8))
        // 2 big + 6 little: at least 2 threads
        assertEquals(2, DeviceTuning.threads(listOf(1_800_000L, 1_800_000, 1_800_000, 1_800_000, 1_800_000, 1_800_000, 2_400_000, 2_400_000), 8))
        // one cluster (the emulator) or unreadable sysfs: one core stays free for the UI, at most 4 threads
        assertEquals(4, DeviceTuning.threads(List(8) { 2_000_000L }, 8))
        assertEquals(3, DeviceTuning.threads(List(4) { 2_000_000L }, 4))
        assertEquals(4, DeviceTuning.threads(emptyList(), 8))
        assertEquals(1, DeviceTuning.threads(emptyList(), 2))
        assertEquals(1, DeviceTuning.threads(emptyList(), 1))
    }

    @Test
    fun aLongPersonaPromptStaysWhenEveryEarlierTurnIsDropped() {
        val persona = ChatTurn(ChatRole.SYSTEM, "p".repeat(15))
        val turns = listOf(persona, user("1111"), assistant("2222"), user("3333"), assistant("4444"), user("55"))
        assertEquals(listOf(persona, user("55")), ContextWindow.fit(turns, contextTokens = 30, reserveTokens = 10, count = count))
    }
}
