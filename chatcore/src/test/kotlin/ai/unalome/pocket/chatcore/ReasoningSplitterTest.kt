package ai.unalome.pocket.chatcore

import ai.unalome.pocket.chatcore.ReasoningSplitter.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningSplitterTest {
    private fun split(vararg chunks: String): Pair<String, String> {
        val splitter = ReasoningSplitter()
        val segments = chunks.flatMap { splitter.process(it) } + splitter.finish()
        return segments.filterIsInstance<Segment.Reasoning>().joinToString("") { it.text } to
            segments.filterIsInstance<Segment.Answer>().joinToString("") { it.text }
    }

    @Test
    fun separatesThinkBlock() {
        assertEquals("plan\n" to "Answer", split("<think>\nplan\n</think>\n\nAnswer"))
    }

    @Test
    fun handlesDelimitersSplitAcrossChunks() {
        assertEquals("plan " to "Answer", split("<thi", "nk>plan </th", "ink>", "Answer"))
    }

    @Test
    fun textWithoutTagsIsAllAnswer() {
        assertEquals("" to "a < b", split("a ", "< b"))
    }

    @Test
    fun unfinishedReasoningStaysReasoning() {
        assertEquals("still going" to "", split("<think>still ", "going"))
    }
}
