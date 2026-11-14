package cx.m42.restoapp.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cx.m42.superizer.theme.AppTheme

/*
 * The Markdown the panel's assistant writes (remark-gfm in the web), cut to what a chat needs: paragraphs, headings,
 * lists, quotes, fenced code, and inline bold, italic, code and links. Tables come as their source lines.
 */

internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Item(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
}

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

@Composable
internal fun Markdown(source: String, color: Color, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val tokens = AppTheme
    val blocks = remember(source) { markdownBlocks(source) }
    val codeBackground = tokens.muted.copy(alpha = 0.14f)
    val body: TextStyle = tokens.body.copy(color = color)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> BasicText(inline(block.text, tokens.primary, codeBackground, onLink), style = body)
                is MdBlock.Heading -> BasicText(
                    inline(block.text, tokens.primary, codeBackground, onLink),
                    style = body.copy(fontWeight = FontWeight.SemiBold, fontSize = if (block.level <= 2) 18.sp else body.fontSize),
                )
                is MdBlock.Item -> Row(Modifier.fillMaxWidth().padding(start = (block.depth * 14).dp)) {
                    BasicText(block.marker, style = body, modifier = Modifier.width(if (block.marker.length > 2) 26.dp else 16.dp))
                    BasicText(inline(block.text, tokens.primary, codeBackground, onLink), style = body)
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.width(3.dp).background(tokens.border)) {}
                    BasicText(inline(block.text, tokens.primary, codeBackground, onLink), style = body.copy(color = tokens.muted), modifier = Modifier.padding(start = 8.dp))
                }
                is MdBlock.Code -> BasicText(
                    block.text,
                    style = tokens.mono.copy(color = color),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(codeBackground)
                        .horizontalScroll(rememberScrollState()).padding(8.dp),
                )
            }
        }
    }
}
