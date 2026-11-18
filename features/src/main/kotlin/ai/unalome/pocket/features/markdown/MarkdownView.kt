package ai.unalome.pocket.features.markdown

import ai.unalome.pocket.design.UnalomeColor
import ai.unalome.pocket.design.UnalomeRadius
import ai.unalome.pocket.design.UnalomeType
import ai.unalome.pocket.design.glass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders a reply's Markdown. Re-parsing on every token is cheap; unchanged blocks are skipped. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, style: TextStyle = UnalomeType.Body) {
    val document = remember(markdown) { MarkdownDocument.parse(markdown) }
    Blocks(document.blocks, style, modifier)
}

@Composable
private fun Blocks(blocks: List<MarkdownBlock>, style: TextStyle, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block -> Block(block, style) }
    }
}

@Composable
private fun Block(block: MarkdownBlock, style: TextStyle) {
    when (block) {
        is MarkdownBlock.Paragraph -> InlineText(block.text, style)
        is MarkdownBlock.Heading -> InlineText(
            block.text,
            style.copy(
                fontWeight = FontWeight.Bold,
                fontSize = when (block.level) { 1 -> 24.sp; 2 -> 20.sp; 3 -> 18.sp; else -> style.fontSize },
                lineHeight = when (block.level) { 1 -> 30.sp; 2 -> 26.sp; else -> style.lineHeight },
                letterSpacing = (-0.01).sp,
            ),
            Modifier.padding(top = 4.dp),
        )
        is MarkdownBlock.Code -> CodeBlock(block)
        is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(UnalomeColor.GlassBorder.copy(alpha = 0.3f), RoundedCornerShape(2.dp)))
            Blocks(block.blocks, style.copy(color = UnalomeColor.TextSecondary), Modifier.padding(start = 12.dp))
        }
        is MarkdownBlock.ListBlock -> ListBlock(block, style)
        is MarkdownBlock.Table -> TableBlock(block, style)
        MarkdownBlock.Rule -> HorizontalDivider(color = UnalomeColor.GlassBorder.copy(alpha = 0.2f))
    }
}


@Composable
private fun InlineText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val annotated = remember(text) { MarkdownInline.build(text) }
    Text(annotated, modifier, style = style.copy(color = if (style.color == Color.Unspecified) UnalomeColor.TextPrimary else style.color))
}

@Composable
private fun ListBlock(block: MarkdownBlock.ListBlock, style: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.items.forEachIndexed { index, item ->
            Row {
                val marker = if (block.ordered) "${block.start + index}." else "•"
                Text(
                    marker,
                    Modifier.widthIn(min = if (block.ordered) 28.dp else 22.dp).padding(start = if (block.ordered) 4.dp else 8.dp),
                    style = style.copy(color = if (block.ordered) UnalomeColor.TextSecondary else UnalomeColor.TextTertiary),
                )
                Blocks(item, style, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(UnalomeRadius.Control))) {
        if (block.language != null) {
            Text(
                block.language,
                Modifier.padding(start = 14.dp, top = 10.dp),
                style = UnalomeType.Caption.copy(color = UnalomeColor.TextTertiary),
            )
        }
        SelectionContainer {
            Text(
                block.code,
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
                style = UnalomeType.Code.copy(color = UnalomeColor.TextPrimary),
                softWrap = false,
            )
        }
    }
}

@Composable
private fun TableBlock(block: MarkdownBlock.Table, style: TextStyle) {
    val shape = RoundedCornerShape(UnalomeRadius.Control)
    val cellStyle = style.copy(fontSize = 16.sp, color = UnalomeColor.TextPrimary)
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        // Scrolls sideways only when even the longest words do not fit.
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            TableLayout(
                columns = block.header.size,
                availableWidth = available,
                modifier = Modifier
                    .clip(shape)
                    .background(UnalomeColor.GlassFill)
                    .border(1.dp, UnalomeColor.GlassBorder, shape),
            ) {
                val rows = listOf(block.header) + block.rows
                rows.forEachIndexed { rowIndex, row ->
                    row.forEachIndexed { column, cell ->
                        val align = when (block.alignments.getOrNull(column)) {
                            MarkdownBlock.Alignment.Center -> TextAlign.Center
                            MarkdownBlock.Alignment.End -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        val annotated = remember(cell) { MarkdownInline.build(cell) }
                        val divider = UnalomeColor.GlassBorder
                        Text(
                            annotated,
                            Modifier
                                .then(if (rowIndex == 0) Modifier.background(Color(0x0DFFFFFF)) else Modifier)
                                .drawBehind {
                                    if (rowIndex > 0) drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            style = (if (rowIndex == 0) headerStyle else cellStyle).copy(textAlign = align),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lays cells out row by row. Columns take their natural width when the table fits; otherwise
 * they shrink towards their longest word, and rows take the height of their tallest cell.
 */
@Composable
private fun TableLayout(columns: Int, availableWidth: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, _ ->
        if (columns == 0 || measurables.isEmpty()) return@Layout layout(0, 0) {}
        val rows = measurables.chunked(columns)
        val cap = 280.dp.roundToPx()
        val natural = IntArray(columns)
        val minimum = IntArray(columns)
        rows.forEach { row ->
            row.forEachIndexed { c, cell ->
                natural[c] = maxOf(natural[c], minOf(cell.maxIntrinsicWidth(Constraints.Infinity), cap))
                minimum[c] = maxOf(minimum[c], minOf(cell.minIntrinsicWidth(Constraints.Infinity), cap))
            }
        }
        for (c in 0 until columns) natural[c] = maxOf(natural[c], minimum[c])
        val totalNatural = natural.sum()
        val totalMinimum = minimum.sum()
        val widths = when {
            totalNatural <= availableWidth -> natural
            totalMinimum >= availableWidth -> minimum
            else -> {
                val ratio = (availableWidth - totalMinimum).toFloat() / (totalNatural - totalMinimum)
                IntArray(columns) { c -> minimum[c] + ((natural[c] - minimum[c]) * ratio).toInt() }
            }
        }
        val placeables = rows.map { row ->
            val height = row.mapIndexed { c, cell -> cell.minIntrinsicHeight(widths[c]) }.max()
            row.mapIndexed { c, cell -> cell.measure(Constraints.fixed(widths[c], height)) }
        }
        val width = widths.sum()
        val height = placeables.sumOf { row -> row.maxOf { it.height } }
        layout(width, height) {
            var y = 0
            placeables.forEach { row ->
                var x = 0
                row.forEachIndexed { c, placeable ->
                    placeable.place(x, y)
                    x += widths[c]
                }
                y += row.maxOf { it.height }
            }
        }
    }
}
