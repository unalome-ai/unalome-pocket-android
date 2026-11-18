package ai.unalome.pocket.chatcore

/**
 * Separates a model's reasoning (`<think>…</think>`) from its answer in a streamed reply.
 * A delimiter split across chunks is held back until it is clear whether it is one; whitespace
 * right after a delimiter is dropped. Port of the iOS `ReasoningSplitter`.
 */
class ReasoningSplitter(
    private val start: String = "<think>",
    private val end: String = "</think>",
) {
    sealed interface Segment {
        data class Reasoning(val text: String) : Segment
        data class Answer(val text: String) : Segment
    }

    var isInsideReasoning = false
        private set
    private var pending = ""
    private var trimLeadingWhitespace = false

    /** Routes one chunk. Returns the segments that are certain so far, in order. */
    fun process(chunk: String): List<Segment> {
        var text = pending + chunk
        pending = ""
        val segments = mutableListOf<Segment>()
        while (text.isNotEmpty()) {
            val delimiter = if (isInsideReasoning) end else start
            val index = text.indexOf(delimiter)
            if (index >= 0) {
                emit(text.substring(0, index), segments)
                text = text.substring(index + delimiter.length)
                isInsideReasoning = !isInsideReasoning
                trimLeadingWhitespace = true
                continue
            }
            val keep = longestSuffixPrefixing(text, delimiter)
            emit(text.dropLast(keep), segments)
            pending = text.takeLast(keep)
            break
        }
        return segments
    }

    /** Flushes held-back text at the end of the stream. */
    fun finish(): List<Segment> {
        val segments = mutableListOf<Segment>()
        emit(pending, segments)
        pending = ""
        return segments
    }

    private fun emit(piece: String, segments: MutableList<Segment>) {
        var text = piece
        if (trimLeadingWhitespace) {
            text = text.trimStart()
            if (text.isEmpty()) return
            trimLeadingWhitespace = false
        }
        if (text.isEmpty()) return
        val last = segments.lastOrNull()
        when {
            isInsideReasoning && last is Segment.Reasoning -> segments[segments.lastIndex] = Segment.Reasoning(last.text + text)
            !isInsideReasoning && last is Segment.Answer -> segments[segments.lastIndex] = Segment.Answer(last.text + text)
            isInsideReasoning -> segments += Segment.Reasoning(text)
            else -> segments += Segment.Answer(text)
        }
    }

    private fun longestSuffixPrefixing(text: String, delimiter: String): Int {
        for (length in minOf(text.length, delimiter.length - 1) downTo 1) {
            if (delimiter.startsWith(text.takeLast(length))) return length
        }
        return 0
    }
}
