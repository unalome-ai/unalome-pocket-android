package ai.unalome.pocket.features.markdown

import ai.unalome.pocket.chatcore.ScriptedEngine
import ai.unalome.pocket.features.markdown.MarkdownBlock.Code
import ai.unalome.pocket.features.markdown.MarkdownBlock.Heading
import ai.unalome.pocket.features.markdown.MarkdownBlock.ListBlock
import ai.unalome.pocket.features.markdown.MarkdownBlock.Paragraph
import ai.unalome.pocket.features.markdown.MarkdownBlock.Quote
import ai.unalome.pocket.features.markdown.MarkdownBlock.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentTest {
    @Test
    fun parsesTheScriptedCodeAndTableReply() {
        val blocks = MarkdownDocument.parse(ScriptedEngine.DEFAULT_SCRIPTS[2].answer).blocks
        assertEquals(listOf(Paragraph::class, Code::class, Heading::class, Table::class, Quote::class), blocks.map { it::class })
        val code = blocks[1] as Code
        assertEquals("kotlin", code.language)
        assertTrue(code.code.startsWith("engine.reply"))
        val table = blocks[3] as Table
        assertEquals(listOf("Part", "Role"), table.header)
        assertEquals(2, table.rows.size)
    }

    @Test
    fun parsesListsAndOrderedStart() {
        val blocks = MarkdownDocument.parse("Intro:\n- one\n- two\n\n3. c\n4. d").blocks
        assertEquals(3, blocks.size)
        val bullets = blocks[1] as ListBlock
        assertEquals(false, bullets.ordered)
        assertEquals(2, bullets.items.size)
        val numbers = blocks[2] as ListBlock
        assertEquals(3, numbers.start)
    }

    @Test
    fun nestedListItems() {
        val list = MarkdownDocument.parse("- a\n  - b\n- c").blocks.single() as ListBlock
        assertEquals(2, list.items.size)
        assertTrue(list.items[0].any { it is ListBlock })
    }

    @Test
    fun nestedItemsIndentedByFourSpacesAreKept() {
        // As Gemma 4 writes them: a label item, then four-space sub-items.
        val text = "* **Symbols:**\n    * On the left, a **red apple**.\n    * On the right, a **yellow star**.\n* **Text:** CAT 42"
        val list = MarkdownDocument.parse(text).blocks.single() as ListBlock
        assertEquals(2, list.items.size)
        val inner = list.items[0].filterIsInstance<ListBlock>().single()
        assertEquals(2, inner.items.size)
        assertEquals(Paragraph("On the left, a **red apple**."), inner.items[0].single())
    }

    @Test
    fun streamingPrefixesNeverChangeEarlierBlocks() {
        // Every prefix of a reply parses to blocks whose finished ones match the full parse.
        for (script in ScriptedEngine.DEFAULT_SCRIPTS) {
            val full = MarkdownDocument.parse(script.answer).blocks
            val tokens = ScriptedEngine.tokenize(script.answer)
            var text = ""
            for (token in tokens) {
                text += token
                val partial = MarkdownDocument.parse(text).blocks
                // All but the last (still growing) block are final.
                partial.dropLast(1).forEachIndexed { index, block -> assertEquals("prefix: $text", full[index], block) }
            }
        }
    }

    @Test
    fun unclosedFenceIsAlreadyCode() {
        val block = MarkdownDocument.parse("```kotlin\nval x = 1").blocks.single()
        assertEquals(Code("kotlin", "val x = 1"), block)
    }

    @Test
    fun tableHeaderAloneIsAlreadyATable() {
        assertTrue(MarkdownDocument.parse("Compare:\n\n| A | B |").blocks.last() is Table)
    }
}
