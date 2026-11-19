package cx.m42.restoapp.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import cx.m42.superizer.kit.Chip
import cx.m42.superizer.kit.IconButton
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronsUpDown
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import cx.m42.restoapp.protocol.Tones
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Dialog
import cx.m42.superizer.kit.EmptyState
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Menu
import cx.m42.superizer.kit.MenuItem
import cx.m42.superizer.kit.Skeleton
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.color
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.softColor
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles
import cx.m42.superizer.ui.platform.SystemBackHandler

/*
 * The renderer's screens in the kit's words (03-render.md): what the screens of `Screens.kt` are built
 * of. Kept apart from `Components.kt`, whose public pieces the native apps still draw with until they
 * move to the kit themselves — those go at the clean-up, these stay.
 */

/** A tone of the contract as the kit's tone: the colour of a status, a notice, a value. */
internal fun toneKit(tone: String?): Tone = when (tone) {
    Tones.DANGER -> Tone.Danger
    Tones.WARNING -> Tone.Warn
    Tones.SUCCESS -> Tone.Success
    Tones.INFO -> Tone.Running
    Tones.MUTED -> Tone.Muted
    else -> Tone.Neutral
}

/** One column of a [RecordTable]: like the kit's `TableColumn`, keyed by the row's id for its tags. */
internal class RecordColumn<T>(
    val title: String,
    val weight: Float = 1f,
    val width: Dp? = null,
    val alignEnd: Boolean = false,
    val cell: @Composable (T) -> Unit,
)

/**
 * The kit's `DataTable`, with one thing it lacks: a [tag] per row, on the row itself, so a test (and a
 * person with a screen reader) finds the row as one target. The look is the kit's, line for line.
 */
@Composable
internal fun <T> RecordTable(
    rows: List<T>,
    columns: List<RecordColumn<T>>,
    tag: (T) -> String,
    onRow: ((T) -> Unit)?,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 52.dp,
    /** False for a row that leads nowhere: no hover, no click. */
    opens: (T) -> Boolean = { true },
    /** False: no head over the columns. */
    headed: Boolean = true,
) {
    @Composable
    fun RowScope.cell(column: RecordColumn<T>, content: @Composable BoxScope.() -> Unit) {
        Box(
            (if (column.width != null) Modifier.width(column.width) else Modifier.weight(column.weight)).padding(horizontal = 10.dp),
            contentAlignment = if (column.alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
            content = content,
        )
    }
    Column(modifier.fillMaxWidth()) {
        if (headed) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(Kit.palette.canvas.copy(alpha = 0.6f)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                columns.forEach { column ->
                    cell(column) {
                        Text(column.title, style = Kit.textStyles.caption.copy(fontWeight = FontWeight.Medium), color = Kit.colors.mutedForeground, maxLines = 1)
                    }
                }
            }
        }
        rows.forEachIndexed { index, row ->
            if (index > 0 || headed) Hairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = rowHeight)
                    .then(if (onRow != null && opens(row)) Modifier.kitClickable { onRow(row) } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag(tag(row)),
                verticalAlignment = Alignment.CenterVertically,
            ) { columns.forEach { column -> cell(column) { column.cell(row) } } }
        }
    }
}

/** The hairline between two rows of a flush panel, edge to edge. */
@Composable
internal fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Kit.colors.border))
}

/**
 * A line over the page — the connection, a notice, an error — in the kit's notice look, but one
 * sentence long: the renderer's notices are one sentence, and the kit's `Notice` wants two.
 */
@Composable
internal fun NoticeBar(text: String, tone: String?, action: String?, onAction: (() -> Unit)?, tag: String, modifier: Modifier = Modifier) {
    val kitTone = if (tone == null) Tone.Neutral else toneKit(tone)
    val icon = when (tone) {
        Tones.DANGER -> Lucide.CircleAlert
        Tones.WARNING -> Lucide.TriangleAlert
        Tones.SUCCESS -> Lucide.CircleCheck
        else -> Lucide.Info
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Kit.colors.card)
            .background(kitTone.softColor())
            .border(1.dp, (if (kitTone == Tone.Neutral) Kit.colors.border else kitTone.color().copy(alpha = 0.28f)), shape)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, size = 18.dp, tint = if (kitTone == Tone.Neutral) Kit.colors.mutedForeground else kitTone.textColor())
        Text(text, Modifier.weight(1f).padding(vertical = 4.dp), style = Kit.textStyles.label.copy(fontWeight = FontWeight.Normal))
        if (action != null && onAction != null) {
            Button(text = action, onClick = onAction, variant = ButtonVariant.Ghost, size = ButtonSize.Sm, modifier = Modifier.testTag("$tag:action"))
        }
    }
}

/** The kit's empty state, tagged: «nothing yet» and «nothing matches» are both this, with their own words. */
@Composable
internal fun EmptyBlock(
    icon: ImageVector,
    title: String,
    description: String,
    tag: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    primary: Boolean = false,
) {
    EmptyState(
        icon = icon,
        title = title,
        description = description,
        modifier = Modifier.testTag(tag),
        action = if (action != null && onAction != null) {
            { Button(text = action, onClick = onAction, variant = if (primary) ButtonVariant.Default else ButtonVariant.Outline, modifier = Modifier.testTag("$tag:action")) }
        } else {
            null
        },
    )
}

/** Rows of grey bars where the rows will be: the list's first read. */
@Composable
internal fun SkeletonRows(tag: String, rows: Int = 6) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Kit.colors.card)
            .border(1.dp, Kit.colors.border, RoundedCornerShape(14.dp))
            .testTag(tag),
    ) {
        repeat(rows) { index ->
            if (index > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skeleton(Modifier.fillMaxWidth(if (index % 2 == 0) 0.45f else 0.6f).height(14.dp))
                    Skeleton(Modifier.fillMaxWidth(if (index % 2 == 0) 0.7f else 0.5f).height(11.dp))
                }
                Skeleton(Modifier.padding(start = 24.dp).width(56.dp).height(14.dp))
            }
        }
    }
}

/** One choice of a [TaggedSelect]. */
internal data class PickChoice(val id: String, val title: String, val hint: String?)

/**
 * The kit's `Select`, with what it lacks: its options tagged ([itemTag]) — the kit's own has no way to name
 * them for a test — inside a column tagged [sheetTag], and a quiet [placeholder] while nothing is chosen.
 * A dropdown on a wide window, a sheet on a phone, as the kit's. [small] is the 36 dp field of a header.
 */
@Composable
internal fun TaggedSelect(
    choices: List<PickChoice>,
    selected: String?,
    title: String,
    onPick: (String) -> Unit,
    tag: String,
    itemTag: (String) -> String,
    modifier: Modifier = Modifier,
    sheetTag: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    small: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(0) }
    val current = choices.firstOrNull { it.id == selected }
    val shape = Kit.shapes.md
    Box(modifier.onSizeChanged { widthPx = it.width }) {
        Row(
            (if (small) Modifier.widthIn(min = 160.dp, max = 320.dp) else Modifier.fillMaxWidth())
                .height(if (small) 36.dp else 44.dp)
                .clip(shape)
                .background(Kit.colors.background)
                .border(if (open) 2.dp else 1.dp, if (open) Kit.colors.ring else Kit.colors.input, shape)
                .kitClickable(hover = false) { open = true }
                .semantics { contentDescription = title }
                .padding(horizontal = 12.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, size = 16.dp, tint = Kit.colors.mutedForeground)
            Text(
                current?.title ?: placeholder.orEmpty(),
                if (small) Modifier.weight(1f, fill = false) else Modifier.weight(1f),
                style = if (small) Kit.textStyles.label else Kit.typography.p,
                color = if (current == null) Kit.colors.mutedForeground else Kit.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Lucide.ChevronsUpDown, contentDescription = null, size = 14.dp, tint = Kit.colors.mutedForeground)
        }
        val items: @Composable () -> Unit = {
            Column(if (sheetTag != null) Modifier.testTag(sheetTag) else Modifier) {
                choices.forEach { choice ->
                    Box(Modifier.testTag(itemTag(choice.id))) {
                        MenuItem(
                            text = choice.title,
                            onClick = {
                                open = false
                                onPick(choice.id)
                            },
                            selected = choice.id == selected,
                            hint = choice.hint?.takeIf { it.isNotBlank() && it != choice.title },
                        )
                    }
                }
            }
        }
        if (LocalWindowClass.current == WindowClass.Compact) {
            if (open) {
                SystemBackHandler(enabled = true) { open = false }
                Dialog(onDismiss = { open = false }, title = title, content = { items() })
            }
        } else {
            val width = with(LocalDensity.current) { widthPx.toDp() }
            Menu(open, { open = false }, alignEnd = false, width = maxOf(width, if (small) 280.dp else 200.dp)) { items() }
        }
    }
}

/**
 * A question before a step that cannot be taken back — the kit's `Dialog` with the kit's two answers,
 * tagged `<answers>:yes` and `<answers>:no`, under [tag] (the kit's `ConfirmDialog` does not name its buttons).
 */
@Composable
internal fun AskDialog(
    question: String,
    confirm: String,
    cancel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tag: String,
    answers: String = tag,
) {
    SystemBackHandler(enabled = true, onBack = onDismiss)
    Dialog(
        onDismiss = onDismiss,
        title = question,
        icon = if (destructive) Lucide.TriangleAlert else null,
        tone = if (destructive) Tone.Danger else Tone.Brand,
        footer = {
            Box(Modifier.testTag(tag)) {
                DialogButtons(
                    listOf(
                        DialogButton(confirm, if (destructive) ButtonVariant.Destructive else ButtonVariant.Default, "$answers:yes", onConfirm),
                        DialogButton(cancel, ButtonVariant.Outline, "$answers:no", onDismiss),
                    ),
                )
            }
        },
    )
}

/** One answer of a dialog: its words, its weight, its tag. The first of a list is the main one. */
internal class DialogButton(val text: String, val variant: ButtonVariant, val tag: String, val onClick: () -> Unit)

/**
 * The kit's `DialogActions` for any number of answers: stacked full-width on a phone, the main one on
 * top; in a row at the right on a wider window, the main one last.
 */
@Composable
internal fun DialogButtons(buttons: List<DialogButton>) {
    if (LocalWindowClass.current == WindowClass.Compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEachIndexed { index, button ->
                Button(
                    text = button.text,
                    onClick = button.onClick,
                    variant = if (index == 0 || button.variant == ButtonVariant.Destructive) button.variant else ButtonVariant.Ghost,
                    size = ButtonSize.Lg,
                    modifier = Modifier.fillMaxWidth().testTag(button.tag),
                )
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            buttons.asReversed().forEach { button ->
                Button(text = button.text, onClick = button.onClick, variant = button.variant, modifier = Modifier.testTag(button.tag))
            }
        }
    }
}

/**
 * A button that may ask first: with [confirm] set, a tap opens [AskDialog] and only its answer acts. A
 * quiet button of a dangerous value still says it is one — red words, no red block.
 */
@Composable
internal fun AskFirstButton(
    text: String,
    tone: String?,
    confirm: String?,
    enabled: Boolean,
    words: RenderWords,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = buttonVariantOf(tone),
    loading: Boolean = false,
) {
    var asking by remember(text, confirm) { mutableStateOf(false) }
    Button(
        onClick = { if (confirm != null) asking = true else onClick() },
        enabled = enabled,
        loading = loading,
        variant = variant,
        modifier = modifier.testTag(tag),
    ) {
        Text(
            text = text,
            style = Kit.typography.small,
            color = if (tone == Tones.DANGER && variant != ButtonVariant.Destructive) Kit.colors.destructive else androidx.compose.ui.graphics.Color.Unspecified,
            maxLines = 1,
        )
    }
    if (asking && confirm != null) {
        AskDialog(
            question = confirm,
            confirm = text,
            cancel = words.cancel,
            destructive = tone == Tones.DANGER,
            onConfirm = {
                asking = false
                onClick()
            },
            onDismiss = { asking = false },
            tag = "$tag:confirm",
            answers = tag,
        )
    }
}

/**
 * «Label ........ value» of a record, as the kit's `FactRow` draws it, with the value tagged
 * `<tag>:value` — what a test reads.
 */
@Composable
internal fun FactLine(label: String, value: String, tone: String?, tag: String) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 24.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(Kit.spacing.lg),
    ) {
        Text(label, Modifier.weight(0.45f).alignByBaseline(), variant = TextVariant.Muted)
        Text(
            value,
            Modifier.weight(0.55f).alignByBaseline().testTag("$tag:value"),
            style = Kit.typography.p.copy(fontFeatureSettings = "tnum"),
            color = if (tone == null) Kit.colors.foreground else toneKit(tone).textColor(),
            textAlign = TextAlign.End,
        )
    }
}

/** A line of a flush panel that leads somewhere: one more screen («›»), or out of the app («↗»). */
@Composable
internal fun LinkItem(title: String, subtitle: String?, external: Boolean, onClick: () -> Unit, tag: String, externalHint: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .kitClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Kit.textStyles.label)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, variant = TextVariant.Muted) }
        }
        Icon(
            if (external) Lucide.ArrowUpRight else Lucide.ChevronRight,
            contentDescription = if (external) externalHint else null,
            size = 16.dp,
            tint = Kit.colors.mutedForeground,
            modifier = Modifier.testTag("$tag:value"),
        )
    }
}

// ───────────────────────────── moved from Components.kt: the renderer's own small parts ─────────────────────────────

/** A tone of the contract as text colour: the kit's tone made readable; null — the ordinary text colour. */
@Composable
internal fun toneOf(tone: String?): Color? = when (tone) {
    Tones.DANGER, Tones.WARNING, Tones.SUCCESS, Tones.INFO, Tones.MUTED -> toneKit(tone).textColor()
    else -> null
}


@Composable
internal fun ValueText(text: String, tone: String?, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Kit.textStyles.number.copy(fontWeight = FontWeight.Medium),
        color = toneOf(tone) ?: Kit.colors.foreground,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier.widthIn(min = 40.dp),
    )
}

/** −, the value, + — the row form of a stepper. A null [onMinus] / [onPlus] greys that side out. */
@Composable
internal fun StepperRow(
    display: String,
    tone: String?,
    onMinus: (() -> Unit)?,
    onPlus: (() -> Unit)?,
    enabled: Boolean,
    tag: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Kit.colors.input, RoundedCornerShape(10.dp)),
    ) {
        IconButton(
            Lucide.Minus,
            contentDescription = "−",
            onClick = { onMinus?.invoke() },
            enabled = enabled && onMinus != null,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("$tag:minus"),
        )
        ValueText(display, tone, Modifier.widthIn(min = 44.dp).testTag("$tag:value"))
        IconButton(
            Lucide.Plus,
            contentDescription = "+",
            onClick = { onPlus?.invoke() },
            enabled = enabled && onPlus != null,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("$tag:plus"),
        )
    }
}


/** The kit's chips in a row that scrolls sideways rather than wraps: the filters of a list. */
@Composable
internal fun ChipsRow(
    values: List<String>,
    titles: Map<String, String>,
    counts: Map<String, Int>,
    selected: String?,
    onSelect: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).then(modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        values.forEach { value ->
            Chip(
                text = titles[value] ?: value,
                selected = value == selected,
                onClick = { onSelect(value) },
                count = counts[value],
                modifier = Modifier.testTag("$tag-$value"),
            )
        }
    }
}


/** A tone as a button's variant: danger is red, success and info are the main action, the rest outlined. */
internal fun buttonVariantOf(tone: String?): ButtonVariant = when (tone) {
    Tones.DANGER -> ButtonVariant.Destructive
    Tones.SUCCESS, Tones.INFO -> ButtonVariant.Default
    else -> ButtonVariant.Outline
}

