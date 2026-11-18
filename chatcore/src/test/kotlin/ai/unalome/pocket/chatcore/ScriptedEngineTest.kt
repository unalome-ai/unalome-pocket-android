package ai.unalome.pocket.chatcore

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptedEngineTest {
    @Test
    fun tokensReproduceTheTextExactly() {
        val text = "Hello  **world**\n\n- one\n- two\n"
        assertEquals(text, ScriptedEngine.tokenize(text).joinToString(""))
    }

    @Test
    fun streamsTheScriptedAnswerThenDone() = runTest {
        val engine = ScriptedEngine()
        val events = engine.reply(ChatTurn(ChatRole.USER, "Show me some code"), emptyList(), GenerationOptions()).toList()
        val text = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        assertEquals(ScriptedEngine.DEFAULT_SCRIPTS[2].answer, text)
        assertTrue(events.any { it is GenerationEvent.Stats })
        assertEquals(GenerationEvent.Done, events.last())
    }

    @Test
    fun reasoningIsSplitFromTheAnswer() = runTest {
        val engine = ScriptedEngine()
        val events = engine.reply(ChatTurn(ChatRole.USER, "Should I walk or cycle?"), emptyList(), GenerationOptions()).toList()
        val reasoning = events.filterIsInstance<GenerationEvent.Reasoning>().joinToString("") { it.text }
        val answer = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        assertEquals(ScriptedEngine.DEFAULT_SCRIPTS[1].reasoning, reasoning.trim())
        assertEquals(ScriptedEngine.DEFAULT_SCRIPTS[1].answer, answer)
    }

    @Test
    fun withoutKeywordsTheScriptAdvancesWithEachUserTurn() {
        val engine = ScriptedEngine()
        val user = ChatTurn(ChatRole.USER, "Tell me more")
        val history = listOf(ChatTurn(ChatRole.USER, "x"), ChatTurn(ChatRole.ASSISTANT, "y"))
        assertEquals(ScriptedEngine.DEFAULT_SCRIPTS[0], engine.scriptFor(user, emptyList()))
        assertEquals(ScriptedEngine.DEFAULT_SCRIPTS[1], engine.scriptFor(user, history))
    }

    @Test
    fun lengthLimitCutsTheReply() = runTest {
        val engine = ScriptedEngine()
        val events = engine.reply(ChatTurn(ChatRole.USER, "Tell me more"), emptyList(), GenerationOptions(maxTokens = 3)).toList()
        assertEquals(3, events.filterIsInstance<GenerationEvent.Token>().size)
        assertTrue(GenerationEvent.LengthLimitReached in events)
    }
}
