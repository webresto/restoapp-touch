package cx.m42.restoapp.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.LayoutDashboard
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Check
import cx.m42.superizer.kit.Icon
import cx.m42.restoapp.protocol.Board
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.Formats
import cx.m42.restoapp.protocol.SummaryBlock
import cx.m42.restoapp.protocol.Views
import cx.m42.superizer.kit.BarChart
import cx.m42.superizer.kit.Grid
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Panel
import cx.m42.superizer.kit.SectionItem
import cx.m42.superizer.kit.ShareBar
import cx.m42.superizer.kit.Sparkline
import cx.m42.superizer.kit.Stat
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.byWindow
import cx.m42.superizer.kit.color
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textStyles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * The optional hints of the protocol (04-protocol.md, «Спецификация ключей (2026-11-18)»): the sections of an
 * app (P5), the board of a list (P6) and the summary over a card (P7). Each of them is drawn only when the
 * declaration has it and it makes sense; otherwise the screen is what it was without it.
 */

// ───────────────────────────── P5: sections ─────────────────────────────

/**
 * The sections of [app]: its queries with `ui.section`, in the order of `methods`, the home one first. Fewer than
 * two are no sections at all.
 */
internal fun sectionsOf(app: DeclaredApp): List<DeclaredMethod> {
    val marked = app.methods.filter { method ->
        method.kind == DeclaredMethod.KIND_QUERY &&
            !method.ui?.section.isNullOrBlank() &&
            method.ui?.view.let { it == Views.LIST || it == Views.DETAIL }
    }
    if (marked.size < 2) return emptyList()
    val home = marked.firstOrNull { it.name == app.home }
    return listOfNotNull(home) + marked.filter { it !== home }
}

/** The kit's item of a section: a card for a `detail` (the summary), a list for a `list`. */
internal fun sectionItem(method: DeclaredMethod, home: Boolean): SectionItem = SectionItem(
    id = method.name,
    label = method.ui?.section.orEmpty(),
    icon = when {
        home && method.ui?.view == Views.DETAIL -> Lucide.LayoutDashboard
        method.ui?.view == Views.DETAIL -> Lucide.FileText
        else -> Lucide.List
    },
)

// ───────────────────────────── P6: board ─────────────────────────────

/**
 * The board of a list as this client can draw it: its field is an `enum` field of the model, and its order has
 * a value. Anything else is no board — the list is drawn as without the key.
 */
internal fun boardOf(context: RenderContext, method: DeclaredMethod): Pair<Board, Field>? {
    val board = method.ui?.board ?: return null
    val field = context.field(method.model, board.field)?.takeIf { it.format == Formats.ENUM } ?: return null
    val order = board.order.filter { it.isNotBlank() }.distinct()
    if (order.isEmpty()) return null
    return board.copy(order = order) to field
}

/** The words of a value of the board's field: its `x-titles`, its specials, or itself. */
internal fun boardTitle(field: Field, value: String): String =
    field.titles[value] ?: field.specials.firstOrNull { it.value.contentOrNull == value }?.title ?: value

/** The tone of a value of the board's field: `x-tones`, or the tone of its special. */
internal fun boardTone(field: Field, value: String): String? =
    field.toneOf(value) ?: field.specials.firstOrNull { it.value.contentOrNull == value }?.tone

/** One card of the board: what a row of the list says, in a card. */
internal class BoardCard(
    val tag: String,
    val title: String,
    val subtitle: String?,
    val meta: String?,
    val value: Shown?,
    val onClick: (() -> Unit)?,
    /** A third line: where it goes. */
    val detail: String? = null,
    /** At the foot, before the value: «6 поз.». */
    val note: String? = null,
    /** The words of its column — what its stripe says to a screen reader. */
    val state: String? = null,
)

/**
 * The board (P6) on a window wider than a phone: a column per value of [order], each headed by the value's
 * words, its tone's dot and its count; the cards of the records in it. Wider than the window, it scrolls sideways.
 */
@Composable
internal fun BoardView(
    tag: String,
    field: Field,
    order: List<String>,
    columns: Map<String, List<BoardCard>>,
) {
    // The columns share the page's width while each keeps [BOARD_COLUMN_MIN]; past that the board scrolls sideways.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val gap = 16.dp
    val share = (maxWidth - gap * (order.size - 1)) / order.size
    val columnWidth = if (share > BOARD_COLUMN_MIN) minOf(share, BOARD_COLUMN_MAX) else BOARD_COLUMN_MIN
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("$tag:board"),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Top,
    ) {
        order.forEach { value ->
            val cards = columns[value].orEmpty()
            val tone = toneKit(boardTone(field, value))
            Column(
                Modifier
                    .width(columnWidth)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Kit.colors.muted.copy(alpha = 0.55f))
                    .padding(8.dp)
                    .testTag("$tag:board-$value"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tone.color()))
                    Text(boardTitle(field, value), Modifier.weight(1f), style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cards.size.toString(), variant = TextVariant.Muted, modifier = Modifier.testTag("$tag:board-$value:count"))
                }
                cards.forEach { card -> BoardCardView(card, tone) }
            }
        }
    }
    }
}

private val BOARD_COLUMN_MIN = 220.dp
private val BOARD_COLUMN_MAX = 320.dp

@Composable
internal fun BoardCardView(card: BoardCard, tone: Tone) {
    val shape = RoundedCornerShape(12.dp)
    val accent = tone.color()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Kit.colors.card)
            .border(1.dp, Kit.colors.border, shape)
            .drawBehind { drawRect(accent, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .then(if (card.onClick != null) Modifier.kitClickable(onClick = card.onClick) else Modifier)
            .padding(start = 15.dp, end = 12.dp, top = 11.dp, bottom = 11.dp)
            .then(if (card.state != null) Modifier.semantics { stateDescription = card.state } else Modifier)
            .testTag(card.tag),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.title, Modifier.weight(1f), style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.meta?.takeIf { it.isNotBlank() }?.let { Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1) }
        }
        card.subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        card.detail?.takeIf { it.isNotBlank() }?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Lucide.MapPin, contentDescription = null, size = 13.dp, tint = Kit.colors.mutedForeground)
                Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (card.value != null || card.note != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(card.note.orEmpty(), Modifier.weight(1f), style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1)
                card.value?.let { value -> ValueText(value.text, value.tone) }
            }
        }
    }
}

/**
 * The way of a record along the columns of its board: the steps passed ticked, the one it is at ringed in its
 * tone, the rest numbered. A value off the way (a rejected order) leaves every step unpassed and greyed.
 */
@Composable
internal fun WayPanel(field: Field, order: List<String>, current: String?, tag: String) {
    if (order.size < 2) return
    val at = order.indexOf(current)
    val brand = Kit.palette.brand
    val line = Kit.colors.border
    Panel(modifier = Modifier.testTag(tag)) {
        Row(Modifier.fillMaxWidth()) {
            order.forEachIndexed { i, value ->
                val last = i == order.lastIndex
                val done = at >= 0 && (i < at || (i == at && last))
                val now = i == at && !last
                val tone = toneKit(boardTone(field, value))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(2.dp).background(if (i == 0) androidx.compose.ui.graphics.Color.Transparent else if (at >= 0 && i <= at) brand else line))
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        done -> brand
                                        now -> tone.color().copy(alpha = 0.15f)
                                        else -> Kit.colors.secondary
                                    },
                                )
                                .then(if (now) Modifier.border(2.dp, tone.color(), CircleShape) else Modifier)
                                .testTag("$tag-$value"),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                done -> Icon(Lucide.Check, contentDescription = null, size = 14.dp, tint = androidx.compose.ui.graphics.Color.White)
                                now -> Box(Modifier.size(7.dp).clip(CircleShape).background(tone.color()))
                                else -> Text("${i + 1}", style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
                            }
                        }
                        Box(Modifier.weight(1f).height(2.dp).background(if (last) androidx.compose.ui.graphics.Color.Transparent else if (at >= 0 && i < at) brand else line))
                    }
                    Text(
                        boardTitle(field, value),
                        style = Kit.textStyles.caption.copy(fontWeight = if (now) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (done || now) Kit.colors.foreground else Kit.colors.mutedForeground,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

// ───────────────────────────── P7: summary ─────────────────────────────

/** The fields a summary draws as its blocks: the card does not repeat them under it. */
internal fun summaryFields(blocks: List<SummaryBlock>): Set<String> =
    blocks.filter { it.type in SummaryBlock.TYPES }.flatMap { listOfNotNull(it.field, it.delta) }.toSet()

private class SeriesPoint(val x: String, val y: Float)

private class ListLine(
    val title: String,
    val value: String?,
    /** «2×» before the title: how many of it. */
    val amount: String? = null,
    /** A muted line under the title: the modifiers of a dish. */
    val note: String? = null,
    val tone: String? = null,
    /** The line that sums the others up: «Итого». */
    val strong: Boolean = false,
)

private class SharePart(val title: String, val value: Double)

/**
 * The summary of a card (P7): its metric tiles in a row of their own, then its series, lists and shares —
 * in a grid on a window wider than a phone. A block of a type this client does not know, or whose field is
 * missing or of another shape, is skipped; nothing of the summary — the card is drawn as without it.
 */
@Composable
internal fun SummaryView(context: RenderContext, model: String?, record: JsonObject, blocks: List<SummaryBlock>) {
    val tag = "${context.tag}:summary"
    val metrics = blocks.filter { it.type == SummaryBlock.METRIC }.mapNotNull { block -> metricOf(context, model, record, block)?.let { block to it } }
    // A metric and the series of the same name with «Series» after it (`cpu` + `cpuSeries`) are one tile: the
    // number and its shape under it, as the prototype's gauges are; the series is not drawn again on its own.
    val seriesBlocks = blocks.filter { it.type == SummaryBlock.SERIES }.associateBy { it.field }
    val paired: Map<String, List<SeriesPoint>> = metrics.mapNotNull { (block, _) ->
        seriesBlocks[block.field + "Series"]?.let { series -> seriesOf(record, series)?.let { block.field to it } }
    }.toMap()
    val pairedFields = paired.keys.map { it + "Series" }.toSet()
    // P7 percent metrics — a level of a limit (processor, memory, disk): a ring and the number, in a row of their own.
    val gauges = metrics.filter { (_, metric) -> metric.fraction != null }
    val tiles = metrics - gauges.toSet()
    if (gauges.isNotEmpty()) {
        Grid(columns = byWindow(1, 3, 3).coerceAtMost(gauges.size), gap = byWindow(12.dp, 16.dp, 16.dp)) {
            gauges.forEach { (block, metric) -> Gauge(metric, paired[block.field], "$tag-${block.field}") }
        }
    }
    val others: List<@Composable () -> Unit> = blocks.filter { it.field !in pairedFields }.mapNotNull { block ->
        when (block.type) {
            SummaryBlock.SERIES -> seriesOf(record, block)?.let { points -> { SeriesPanel(context, model, block, points, "$tag-${block.field}") } }
            SummaryBlock.LIST -> linesOf(context, record, block)?.let { lines ->
                val totals = linesAt(context, record, block.totals).orEmpty()
                ({ ListPanel(context, model, block, lines, totals, "$tag-${block.field}") })
            }
            SummaryBlock.SHARE -> sharesOf(record, block)?.let { parts -> { SharePanel(context, model, block, parts, "$tag-${block.field}") } }
            else -> null
        }
    }
    if (tiles.isNotEmpty()) {
        // Rows that end even: five or six tiles are rows of three on a desktop, not four and a lone one.
        val columns = byWindow(2, 2, if (tiles.size in 5..6) 3 else 4).coerceAtMost(tiles.size)
        Grid(columns = columns, gap = byWindow(12.dp, 16.dp, 16.dp)) {
            tiles.forEach { (block, metric) ->
                val shape = paired[block.field]?.takeIf { it.size >= 2 }
                Stat(
                    label = metric.label,
                    value = metric.value,
                    hint = metric.hint,
                    tone = metric.tone,
                    modifier = Modifier.testTag("$tag-${block.field}"),
                    extra = shape?.let { points -> { Sparkline(points.map { it.y }, Modifier.fillMaxWidth().height(28.dp), contentDescription = metric.label) } },
                )
            }
        }
    }
    if (others.isEmpty()) return
    // Side by side only where a chart keeps its width: a desktop's window.
    if (LocalWindowClass.current != WindowClass.Expanded || others.size == 1) {
        others.forEach { it() }
    } else {
        Grid(columns = 2, gap = 16.dp) { others.forEach { it() } }
    }
}

private class Metric(
    val label: String,
    val value: String,
    val hint: String?,
    val tone: Tone,
    /** A percent metric's level, 0..1: drawn as a gauge. */
    val fraction: Float? = null,
)

/** A percent metric as the prototype's gauge: its name, the number, a ring of the level and its shape under them. */
@Composable
private fun Gauge(metric: Metric, history: List<SeriesPoint>?, tag: String) {
    val fraction = metric.fraction ?: 0f
    val tone = cx.m42.superizer.kit.meterTone(fraction)
    Panel(modifier = Modifier.testTag(tag), gap = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(metric.label, variant = TextVariant.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    metric.value,
                    style = Kit.textStyles.metric,
                    color = if (tone == Tone.Brand) Kit.colors.foreground else tone.textColor(),
                    maxLines = 1,
                )
            }
            cx.m42.superizer.kit.Ring(fraction, size = 60.dp, color = tone.color(), contentDescription = metric.label)
        }
        if (history != null && history.size >= 2) {
            Sparkline(history.map { it.y }, Modifier.fillMaxWidth().height(36.dp), color = tone.color())
        }
    }
}

private fun blockTitle(context: RenderContext, model: String?, block: SummaryBlock): String =
    block.title?.takeIf { it.isNotBlank() } ?: context.field(model, block.field)?.title ?: block.field

private fun metricOf(context: RenderContext, model: String?, record: JsonObject, block: SummaryBlock): Metric? {
    if (!record.containsKey(block.field)) return null
    val declared = context.field(model, block.field)
    val format = block.format ?: declared?.format ?: Formats.NUMBER
    val field = (declared ?: Field(block.field, block.field)).copy(format = format)
    val shown = showValue(record[block.field], field, context.facts, context.words, context.now)
    val delta = block.delta?.let { (record[it] as? JsonPrimitive)?.doubleOrNull }
    // The record may say how alarming the number is itself: `balanceTone` beside `balance`.
    val own = (record[block.tone ?: (block.field + "Tone")] as? JsonPrimitive)?.contentOrNull?.takeIf { it in cx.m42.restoapp.protocol.Tones.ALL }
    val raw = (record[block.field] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    return Metric(
        label = blockTitle(context, model, block),
        value = shown.text,
        // P11: credits say «≈ 12,35 $» under them, at the system rate of the server.
        hint = delta?.let { (if (it > 0) "+" else "") + percent(it) }
            ?: context.facts?.credits?.takeIf { format == Formats.CREDITS && raw != null }?.let { approxMoney(raw!!, it) },
        tone = when {
            own != null -> toneKit(own)
            delta == null || delta == 0.0 -> Tone.Neutral
            delta > 0 -> Tone.Success
            else -> Tone.Danger
        },
        fraction = if (format == Formats.PERCENT && raw != null) (raw / 100.0).toFloat().coerceIn(0f, 1f) else null,
    )
}

private fun seriesOf(record: JsonObject, block: SummaryBlock): List<SeriesPoint>? {
    val array = record[block.field] as? JsonArray ?: return null
    val points = array.mapNotNull { element ->
        val point = element as? JsonObject ?: return@mapNotNull null
        val x = (point["x"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val y = (point["y"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return@mapNotNull null
        SeriesPoint(x, y.toFloat())
    }
    return points.takeIf { it.isNotEmpty() }
}

private fun linesOf(context: RenderContext, record: JsonObject, block: SummaryBlock): List<ListLine>? =
    linesAt(context, record, block.field)

private fun linesAt(context: RenderContext, record: JsonObject, field: String?): List<ListLine>? {
    val array = field?.let { record[it] } as? JsonArray ?: return null
    fun text(line: JsonObject, key: String) = (line[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val lines = array.mapNotNull { element ->
        val line = element as? JsonObject ?: return@mapNotNull null
        val title = text(line, "title") ?: return@mapNotNull null
        val value = line["value"] as? JsonPrimitive
        val format = text(line, "format") ?: if (value?.isString == false) Formats.NUMBER else Formats.TEXT
        val amount = (line["amount"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        ListLine(
            title = title,
            value = value?.let { showValue(it, Field("value", title, format), context.facts, context.words, context.now).text },
            amount = amount?.let { number(it) + "×" },
            note = text(line, "note"),
            tone = text(line, "tone"),
            strong = (line["strong"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }
    return lines.takeIf { it.isNotEmpty() }
}

private fun sharesOf(record: JsonObject, block: SummaryBlock): List<SharePart>? {
    val array = record[block.field] as? JsonArray ?: return null
    val parts = array.mapNotNull { element ->
        val part = element as? JsonObject ?: return@mapNotNull null
        val title = (part["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val value = (part["value"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return@mapNotNull null
        SharePart(title, value)
    }
    return parts.takeIf { it.isNotEmpty() }
}

/** A series: bars over its points, labels under them; a sparkline when the points are too few for bars to say much. */
@Composable
private fun SeriesPanel(context: RenderContext, model: String?, block: SummaryBlock, points: List<SeriesPoint>, tag: String) {
    val title = blockTitle(context, model, block)
    val last = points.last()
    val format = block.format ?: Formats.NUMBER
    val lastText = showValue(JsonPrimitive(last.y), Field("y", title, format), context.facts, context.words, context.now).text
    Panel(
        modifier = Modifier.testTag(tag),
        title = title,
        action = { Text(lastText, style = Kit.textStyles.label, color = Kit.colors.mutedForeground) },
    ) {
        if (points.size >= 3) {
            BarChart(
                values = points.map { it.y },
                labels = points.map { it.x },
                height = byWindow(150.dp, 170.dp, 180.dp),
                contentDescription = title,
            )
        } else {
            Sparkline(points.map { it.y }, Modifier.fillMaxWidth().height(56.dp), contentDescription = title)
        }
    }
}

@Composable
private fun ListPanel(context: RenderContext, model: String?, block: SummaryBlock, lines: List<ListLine>, totals: List<ListLine>, tag: String) {
    val inset = byWindow(16.dp, 20.dp, 20.dp)
    Panel(modifier = Modifier.testTag(tag), title = blockTitle(context, model, block), flush = true) {
        lines.forEachIndexed { index, line ->
            if (index > 0) Hairline()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                line.amount?.let {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Kit.colors.secondary),
                        contentAlignment = Alignment.Center,
                    ) { Text(it, style = Kit.textStyles.caption.copy(fontWeight = FontWeight.SemiBold)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(line.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    line.note?.let { Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                line.value?.let { ValueText(it, line.tone) }
            }
        }
        if (totals.isNotEmpty()) {
            Hairline()
            Column(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                totals.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (line.strong) {
                            Text(line.title, Modifier.weight(1f), variant = TextVariant.Large)
                            line.value?.let { Text(it, variant = TextVariant.Large) }
                        } else {
                            Text(line.title, Modifier.weight(1f), color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            line.value?.let { ValueText(it, line.tone) }
                        }
                    }
                }
            }
        }
    }
}

/** Shares: one bar split in the palette's series, and the legend under it in the same order and colours. */
@Composable
private fun SharePanel(context: RenderContext, model: String?, block: SummaryBlock, parts: List<SharePart>, tag: String) {
    val total = parts.sumOf { it.value.coerceAtLeast(0.0) }
    val colors = Kit.palette.chart
    Panel(modifier = Modifier.testTag(tag), title = blockTitle(context, model, block), gap = 12.dp) {
        ShareBar(parts.map { it.value.toFloat() }, contentDescription = blockTitle(context, model, block))
        parts.forEachIndexed { index, part ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(colors[index % colors.size]))
                Text(part.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(number(part.value), style = Kit.textStyles.number)
                Text(
                    if (total > 0) percent(kotlin.math.round(part.value.coerceAtLeast(0.0) / total * 100)) else "—",
                    Modifier.width(52.dp),
                    style = Kit.textStyles.caption,
                    color = Kit.colors.mutedForeground,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}
