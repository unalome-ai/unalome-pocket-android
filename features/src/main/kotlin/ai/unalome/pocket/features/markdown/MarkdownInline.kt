package ai.unalome.pocket.features.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** Inline Markdown: `**bold**`, `*italic*`/`_italic_`, `` `code` ``, `~~strike~~` and `[links](url)`. */
object MarkdownInline {
    data class Styles(
        val code: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x1AFFFFFF)),
        val link: SpanStyle = SpanStyle(color = Color(0xFF2DD4BF), textDecoration = TextDecoration.Underline),
    )

    /**
     * Builds the styled text. An emphasis marker that is never closed styles the rest of the text
     * (and is hidden), so a streaming `**Stop` is bold at once instead of flashing asterisks.
     */
    fun build(text: String, styles: Styles = Styles()): AnnotatedString = buildAnnotatedString {
        appendInline(text, styles)
    }

    private fun AnnotatedString.Builder.appendInline(text: String, styles: Styles) {
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) { append(plain.toString()); plain.clear() }
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_[]()#+-.!|~>" -> {
                    plain.append(text[i + 1]); i += 2
                }
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    flush()
                    val code = if (close < 0) text.substring(i + 1) else text.substring(i + 1, close)
                    withStyle(styles.code) { append(code) }
                    i = if (close < 0) text.length else close + 1
                }
                (text.startsWith("**", i) || text.startsWith("__", i)) && isLeftFlanking(text, i, 2) -> {
                    i = emphasis(text, i, text.substring(i, i + 2), SpanStyle(fontWeight = FontWeight.Bold), styles, ::flush)
                }
                text.startsWith("~~", i) -> {
                    i = emphasis(text, i, "~~", SpanStyle(textDecoration = TextDecoration.LineThrough), styles, ::flush)
                }
                (c == '*' || c == '_') && isLeftFlanking(text, i, 1) -> {
                    i = emphasis(text, i, c.toString(), SpanStyle(fontStyle = FontStyle.Italic), styles, ::flush)
                }
                c == '[' -> {
                    val link = Regex("^\\[([^\\]]*)]\\(([^)\\s]+)\\)").find(text.substring(i))
                    if (link != null) {
                        flush()
                        val url = link.groupValues[2]
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(styles.link))) {
                            appendInline(link.groupValues[1], styles)
                        }
                        i += link.value.length
                    } else {
                        plain.append(c); i++
                    }
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
    }

    /** `*` or `_` that opens emphasis: followed by a non-space, and `_` not inside a word. */
    private fun isLeftFlanking(text: String, i: Int, length: Int): Boolean {
        val next = text.getOrNull(i + length) ?: return false
        if (next.isWhitespace()) return false
        if (text[i] == '_') {
            val previous = text.getOrNull(i - 1)
            if (previous != null && previous.isLetterOrDigit()) return false
        }
        return true
    }

    private fun AnnotatedString.Builder.emphasis(
        text: String,
        i: Int,
        marker: String,
        style: SpanStyle,
        styles: Styles,
        flush: () -> Unit,
    ): Int {
        val start = i + marker.length
        var close = text.indexOf(marker, start)
        // For single markers, skip a double one ("*a **b** c*").
        while (marker.length == 1 && close >= 0 && text.startsWith(marker + marker, close)) {
            val after = text.indexOf(marker + marker, close + 2)
            close = if (after < 0) -1 else text.indexOf(marker, after + 2)
        }
        flush()
        val inner = if (close < 0) text.substring(start) else text.substring(start, close)
        withStyle(style) { appendInline(inner, styles) }
        return if (close < 0) text.length else close + marker.length
    }
}
