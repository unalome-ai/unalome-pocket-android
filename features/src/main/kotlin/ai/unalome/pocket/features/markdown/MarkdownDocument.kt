package ai.unalome.pocket.features.markdown

import androidx.compose.runtime.Immutable

/**
 * A reply's Markdown as plain values, ready to render: the subset small models write (paragraphs,
 * headings, bold/italic/code/links, lists, fenced code, GFM tables, quotes, rules).
 *
 * Parsing is synchronous and line-based, and every block only depends on the lines before it, so
 * a streaming reply never reflows earlier blocks: an unclosed code fence is already a code block,
 * a table header without its separator row is already a table, and an unclosed `**` is bold to
 * the end of the text so far. Values, not views, so Compose skips blocks that did not change.
 */
@Immutable
data class MarkdownDocument(val blocks: List<MarkdownBlock>) {
    companion object {
        fun parse(text: String): MarkdownDocument = MarkdownDocument(BlockParser(text.replace("\r\n", "\n").lines()).parse())
    }
}

@Immutable
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MarkdownBlock>>) : MarkdownBlock
    data class Table(val header: List<String>, val alignments: List<Alignment>, val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock

    enum class Alignment { Start, Center, End }
}

private val FENCE = Regex("^ {0,3}(```+|~~~+)\\s*([\\w+#.-]*).*$")
private val HEADING = Regex("^ {0,3}(#{1,6})(?:\\s+(.*?))?\\s*#*\\s*$")
private val RULE = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val BULLET = Regex("^( {0,3})([-*+])\\s+(.*)$")
private val ORDERED = Regex("^( {0,3})(\\d{1,9})[.)]\\s+(.*)$")
private val QUOTE = Regex("^ {0,3}>\\s?(.*)$")
private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

private class BlockParser(private val lines: List<String>) {
    private var index = 0

    fun parse(): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.isBlank() -> index++
                FENCE.matches(line) -> blocks += code()
                HEADING.matches(line) -> {
                    val match = HEADING.find(line)!!
                    blocks += MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
                    index++
                }
                isTableStart() -> blocks += table()
                RULE.matches(line) -> { blocks += MarkdownBlock.Rule; index++ }
                QUOTE.matches(line) -> blocks += quote()
                BULLET.matches(line) || ORDERED.matches(line) -> blocks += list()
                else -> blocks += paragraph()
            }
        }
        return blocks
    }

    private fun code(): MarkdownBlock {
        val open = FENCE.find(lines[index])!!
        val fence = open.groupValues[1]
        val language = open.groupValues[2].ifEmpty { null }
        index++
        val body = mutableListOf<String>()
        while (index < lines.size) {
            val line = lines[index]
            index++
            if (line.trim().startsWith(fence) && line.trim().trimStart(fence[0]).isBlank()) break
            body += line
        }
        return MarkdownBlock.Code(language, body.joinToString("\n").trimEnd('\n'))
    }

    /** A pipe row followed by a separator row, or a pipe row that is the last line so far (streaming). */
    private fun isTableStart(): Boolean {
        val line = lines[index]
        if (!line.trimStart().startsWith("|")) return false
        val next = lines.getOrNull(index + 1) ?: return true
        if (TABLE_SEPARATOR.matches(next) && next.contains('-')) return true
        // The separator row is still streaming in: "| --- | -".
        return index + 2 == lines.size && next.trimStart().startsWith("|") && next.all { it in "|-: " }
    }

    private fun table(): MarkdownBlock {
        val header = cells(lines[index])
        index++
        var alignments = header.map { MarkdownBlock.Alignment.Start }
        if (index < lines.size && lines[index].trimStart().startsWith("|") && lines[index].all { it in "|-: \t" }) {
            alignments = cells(lines[index]).map { spec ->
                val s = spec.trim()
                when {
                    s.startsWith(":") && s.endsWith(":") && s.length > 1 -> MarkdownBlock.Alignment.Center
                    s.endsWith(":") -> MarkdownBlock.Alignment.End
                    else -> MarkdownBlock.Alignment.Start
                }
            }
            index++
        }
        val rows = mutableListOf<List<String>>()
        while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
            rows += cells(lines[index])
            index++
        }
        val width = header.size
        fun fit(row: List<String>) = (row + List((width - row.size).coerceAtLeast(0)) { "" }).take(width)
        val fittedAlignments = (alignments + List((width - alignments.size).coerceAtLeast(0)) { MarkdownBlock.Alignment.Start }).take(width)
        return MarkdownBlock.Table(header, fittedAlignments, rows.map(::fit))
    }

    private fun cells(line: String): List<String> {
        var row = line.trim()
        if (row.startsWith("|")) row = row.substring(1)
        if (row.endsWith("|") && !row.endsWith("\\|")) row = row.dropLast(1)
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inCode = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == '\\' && i + 1 < row.length && row[i + 1] == '|' -> { current.append('|'); i++ }
                c == '`' -> { inCode = !inCode; current.append(c) }
                c == '|' && !inCode -> { cells += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString().trim()
        return cells
    }

    private fun quote(): MarkdownBlock {
        val inner = mutableListOf<String>()
        while (index < lines.size) {
            val match = QUOTE.find(lines[index]) ?: break
            inner += match.groupValues[1]
            index++
        }
        return MarkdownBlock.Quote(BlockParser(inner).parse())
    }

    private fun list(): MarkdownBlock {
        val first = lines[index]
        val ordered = !BULLET.matches(first)
        val start = if (ordered) ORDERED.find(first)!!.groupValues[2].toInt() else 1
        val items = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        var contentIndent = 0
        var sawBlank = false
        while (index < lines.size) {
            val line = lines[index]
            val marker = (if (ordered) ORDERED else BULLET).find(line)
            when {
                marker != null && marker.groupValues[1].length < 2 -> {
                    current = mutableListOf(marker.groupValues[3])
                    items += current
                    contentIndent = line.length - marker.groupValues[3].length
                    sawBlank = false
                }
                line.isBlank() -> {
                    // A blank line ends the list unless the next line continues it.
                    val next = lines.getOrNull(index + 1)
                    if (next == null || !(next.startsWith(" ") || (if (ordered) ORDERED else BULLET).matches(next))) {
                        index++
                        break
                    }
                    sawBlank = true
                    current?.add("")
                }
                line.startsWith(" ") || line.startsWith("\t") -> {
                    val strip = line.takeWhile { it == ' ' || it == '\t' }.length.coerceAtMost(contentIndent.coerceAtLeast(2))
                    current?.add(line.substring(strip))
                }
                !sawBlank && current != null && !isBlockStart(line) -> current.add(line) // lazy continuation
                else -> break
            }
            index++
        }
        return MarkdownBlock.ListBlock(ordered, start, items.map { BlockParser(it).parse() })
    }

    private fun isBlockStart(line: String) =
        FENCE.matches(line) || HEADING.matches(line) || RULE.matches(line) || QUOTE.matches(line) ||
            BULLET.matches(line) || ORDERED.matches(line) || line.trimStart().startsWith("|")

    private fun paragraph(): MarkdownBlock {
        val text = mutableListOf(lines[index].trim())
        index++
        while (index < lines.size && lines[index].isNotBlank() && !isBlockStart(lines[index])) {
            text += lines[index].trim()
            index++
        }
        return MarkdownBlock.Paragraph(text.joinToString("\n"))
    }
}
