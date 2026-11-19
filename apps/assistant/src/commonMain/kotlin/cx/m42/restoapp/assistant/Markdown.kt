package cx.m42.restoapp.assistant

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textStyles

/*
 * The Markdown the panel's assistant writes (remark-gfm in the web), cut to what a chat needs: paragraphs, headings,
 * lists, quotes, fenced code, tables, and inline bold, italic, code and links. A paragraph that is one link and
 * nothing else is a way into the panel, and is drawn as a button.
 */

internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Item(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val head: List<String>, val rows: List<List<String>>, val alignEnd: List<Boolean>) : MdBlock
}

/** `| a | b |` → `[a, b]`. */
private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private val TABLE_RULE = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

internal fun markdownBlocks(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(paragraph.joinToString("\n"))
        paragraph.clear()
    }
    val lines = source.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                blocks += MdBlock.Code(code.joinToString("\n"))
            }
            trimmed.isEmpty() -> flush()
            trimmed.startsWith("|") && i + 1 < lines.size && TABLE_RULE.matches(lines[i + 1]) -> {
                flush()
                val head = cells(trimmed)
                val rule = cells(lines[i + 1])
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += cells(lines[i++])
                i--
                blocks += MdBlock.Table(head, rows, head.indices.map { rule.getOrNull(it)?.endsWith(":") == true })
            }
            HEADING.matches(trimmed) -> {
                flush()
                val m = HEADING.find(trimmed)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                blocks += MdBlock.Item("•", m.groupValues[2], m.groupValues[1].length / 2)
            }
            NUMBER.matches(line) -> {
                flush()
                val m = NUMBER.find(line)!!
                blocks += MdBlock.Item("${m.groupValues[2]}.", m.groupValues[3], m.groupValues[1].length / 2)
            }
            trimmed.startsWith(">") -> {
                flush()
                blocks += MdBlock.Quote(trimmed.removePrefix(">").trimStart())
            }
            else -> paragraph += line
        }
        i++
    }
    flush()
    return blocks
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBER = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")

/** Inline Markdown into an AnnotatedString; links call [onLink] with their target. */
internal fun inline(text: String, link: Color, codeBackground: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(at, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(inline(g[1].ifEmpty { g[2] }, link, codeBackground, onLink))
            }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(g[3]) }
            g[4].isNotEmpty() -> {
                val target = g[5]
                withLink(LinkAnnotation.Clickable(tag = target, styles = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))) { onLink(target) }) {
                    append(g[4])
                }
            }
            g[6].isNotEmpty() || g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6].ifEmpty { g[7] }) }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

private val LONE_LINK = Regex("""^\s*\[([^\]]+)]\(([^)\s]+)\)\s*$""")

@Composable
internal fun Markdown(source: String, color: Color, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val blocks = remember(source) { markdownBlocks(source) }
    val link = Kit.palette.onBrandSoft
    val codeBackground = Kit.colors.muted
    val body: TextStyle = Kit.typography.p.copy(color = color)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> {
                    val lone = LONE_LINK.find(block.text)
                    if (lone != null) {
                        LinkButton(lone.groupValues[1]) { onLink(lone.groupValues[2]) }
                    } else {
                        BasicText(inline(block.text, link, codeBackground, onLink), style = body)
                    }
                }
                is MdBlock.Heading -> BasicText(
                    inline(block.text, link, codeBackground, onLink),
                    style = (if (block.level <= 2) Kit.typography.large else Kit.textStyles.label).copy(color = color, fontWeight = FontWeight.SemiBold),
                )
                is MdBlock.Item -> Row(Modifier.fillMaxWidth().padding(start = (block.depth * 14).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (block.marker == "•") {
                        Box(Modifier.padding(top = 9.dp).size(5.dp).clip(CircleShape).background(Kit.colors.mutedForeground))
                    } else {
                        BasicText(block.marker, style = body.copy(color = Kit.colors.mutedForeground), modifier = Modifier.width(20.dp))
                    }
                    BasicText(inline(block.text, link, codeBackground, onLink), style = body)
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Kit.colors.border))
                    BasicText(inline(block.text, link, codeBackground, onLink), style = body.copy(color = Kit.colors.mutedForeground), modifier = Modifier.padding(start = 10.dp))
                }
                is MdBlock.Code -> BasicText(
                    block.text,
                    style = Kit.textStyles.monoSmall.copy(color = color),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Kit.colors.border, RoundedCornerShape(8.dp))
                        .background(Kit.palette.canvas).horizontalScroll(rememberScrollState()).padding(10.dp),
                )
                is MdBlock.Table -> MdTable(block, link, codeBackground, onLink)
            }
        }
    }
}

/** A link alone in its paragraph: a way into the panel, outlined in the accent like the prototype's. */
@Composable
private fun LinkButton(title: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, Kit.palette.brandBorder, shape)
            .kitClickable(shape, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("assistant:link"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = Kit.textStyles.label, color = Kit.palette.onBrandSoft)
        Icon(Lucide.ChevronRight, contentDescription = null, size = 14.dp, tint = Kit.palette.onBrandSoft)
    }
}

/**
 * A table of the reply in a bordered frame that scrolls sideways by itself: a quiet header, hairlines, numbers to
 * the right. Columns are as wide as their widest cell (up to a limit), so a short table stays short.
 */
@Composable
private fun MdTable(table: MdBlock.Table, link: Color, codeBackground: Color, onLink: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val columns = maxOf(table.head.size, table.rows.maxOfOrNull { it.size } ?: 0)
    fun numeric(i: Int) = table.alignEnd.getOrElse(i) { false } ||
        (table.rows.isNotEmpty() && table.rows.all { row -> row.getOrNull(i)?.let { NUMERIC.matches(it) } ?: true })
    val ends = (0 until columns).map { it > 0 && numeric(it) }
    // As wide as the table, up to the column: a wider one scrolls sideways inside its own frame.
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, Kit.colors.border, shape)
            .horizontalScroll(rememberScrollState())
            .testTag("assistant:table"),
    ) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            @Composable
            fun line(cellsOf: List<String>, header: Boolean) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(if (header) Modifier.background(Kit.colors.muted) else Modifier)
                        .padding(horizontal = 6.dp),
                ) {
                    for (i in 0 until columns) {
                        val text = cellsOf.getOrElse(i) { "" }
                        Box(
                            Modifier.widthIn(min = if (i == 0) 96.dp else 64.dp, max = 260.dp).weight(1f, fill = true).padding(horizontal = 6.dp, vertical = 7.dp),
                            contentAlignment = if (ends[i]) Alignment.CenterEnd else Alignment.CenterStart,
                        ) {
                            if (header) {
                                Text(text, style = Kit.textStyles.caption.copy(fontWeight = FontWeight.SemiBold), color = Kit.colors.mutedForeground, maxLines = 1)
                            } else {
                                BasicText(
                                    inline(text, link, codeBackground, onLink),
                                    style = Kit.textStyles.number.copy(color = Kit.colors.foreground, textAlign = if (ends[i]) TextAlign.End else TextAlign.Start),
                                )
                            }
                        }
                    }
                }
            }
            line(table.head, header = true)
            table.rows.forEach { row ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(Kit.colors.border))
                line(row, header = false)
            }
        }
    }
}

/** A cell that is a number, money or a share: «1 420 ₽», «12%», «−3,5». */
private val NUMERIC = Regex("""^[−\-+]?[\d\s\u00A0.,]+\s?(%|₽|\$|€|₸|шт\.?|мин)?$""")
