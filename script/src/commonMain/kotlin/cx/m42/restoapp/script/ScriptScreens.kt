package cx.m42.restoapp.script

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import cx.m42.restoapp.render.ConfirmButton
import cx.m42.restoapp.render.RenderWords
import cx.m42.superizer.kit.Alert
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Crumb
import cx.m42.superizer.kit.EmptyState
import cx.m42.superizer.kit.Field
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.ItemSeparator
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Notice
import cx.m42.superizer.kit.Page
import cx.m42.superizer.kit.PageHeader
import cx.m42.superizer.kit.PageWidth
import cx.m42.superizer.kit.Select
import cx.m42.superizer.kit.SelectOption
import cx.m42.superizer.kit.Skeleton
import cx.m42.superizer.kit.Switch
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.byWindow
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.ui.platform.ScreenSteps
import cx.m42.superizer.ui.platform.SystemBackHandler
import cx.m42.superizer.ui.platform.hostDrawsBack
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The screen a script drew (js-module.plan.md, stage 0): a tree of nodes, drawn by the kit's components
 * in a narrow page with its header — a script gets no components of its own, and an unknown node is a
 * line of text rather than a crash.
 */
@Composable
internal fun ScriptAppScreen(instance: ScriptAppInstance) {
    val runtime = LocalAppRuntime.current
    val lang by runtime.locale.langTag.collectAsState()
    val words = remember(lang) { RenderWords(lang) }
    val own = remember(lang) { ScriptWords(lang) }
    val screen by instance.screen.collectAsState()
    val error by instance.error.collectAsState()
    val notice by instance.notice.collectAsState()
    val chrome by instance.chrome.collectAsState()
    val declared by instance.server.app.collectAsState()
    LaunchedEffect(instance) { instance.start() }

    ScreenSteps(instance.steps)
    if (chrome.canGoBack) SystemBackHandler(enabled = true) { instance.back() }
    Box(modifier = Modifier.fillMaxSize().background(Kit.palette.canvas)) {
        Page(PageWidth.Narrow) {
            val root = declared?.title.orEmpty()
            val title = screen?.title?.takeIf { it.isNotBlank() } ?: root
            PageHeader(
                title = title,
                // A screen standing on another one: the way back is the crumb, where the host draws no bar.
                crumbs = if (chrome.canGoBack && root.isNotBlank()) listOf(Crumb(root, instance::back), Crumb(title, null)) else emptyList(),
            )
            if (chrome.canGoBack) {
                // `script:nested` — a screen standing on another one, which the way back returns from.
                Box(Modifier.testTag("script:nested"))
                // Only where the host has no arrow of its own: the shell's goes through the handler above.
                if (!hostDrawsBack) {
                    Button(
                        text = words.back,
                        onClick = instance::back,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        leadingIcon = Lucide.ChevronLeft,
                        modifier = Modifier.testTag("script:back"),
                    )
                }
            }
            notice?.let { text ->
                Alert(text, Modifier.testTag("script:notice"), icon = Lucide.Info)
            }
            error?.let { text ->
                Notice(
                    Tone.Danger,
                    Lucide.CircleAlert,
                    own.failed,
                    text,
                    Modifier.testTag("script:error"),
                    action = { Button(words.retry, { instance.retry() }, variant = ButtonVariant.Outline) },
                )
            }
            val drawn = screen
            when {
                drawn != null -> ScriptNodeView(drawn.tree, instance, words, own, "script")
                error != null -> Unit // the notice above is the whole screen until a retry works
                else -> Column(Modifier.testTag("script:loading"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(4) { Skeleton(Modifier.fillMaxWidth().height(52.dp)) }
                }
            }
        }
    }
}

/**
 * One node of the tree, drawn by a kit component — the prelude's `ui.*` is this set: a column, a section with its
 * title, a list of rows in one bordered block, a button, a field, a select, an empty state and a line of text.
 * Anything else says so, in place, and keeps the screen.
 */
@Composable
private fun ScriptNodeView(node: ScriptNode, instance: ScriptAppInstance, words: RenderWords, own: ScriptWords, tag: String) {
    when (node.type) {
        "column" -> Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(byWindow(16.dp, 20.dp, 24.dp))) {
            node.children.forEachIndexed { index, child -> ScriptNodeView(child, instance, words, own, "$tag:$index") }
        }

        "section" -> Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Kit.spacing.md)) {
            node.title?.let { title ->
                Text(
                    text = title,
                    style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 4.dp).testTag("$tag:title"),
                )
            }
            node.children.forEachIndexed { index, child -> ScriptNodeView(child, instance, words, own, "$tag:$index") }
        }

        "list" ->
            if (node.items.isEmpty()) {
                EmptyState(Lucide.Inbox, node.empty ?: words.nothingFound, own.emptyHint, Modifier.testTag("$tag:empty"))
            } else {
                ScriptListView(node, instance, words, tag)
            }

        "button" -> ConfirmButton(
            text = node.title.orEmpty(),
            tone = node.tone,
            confirm = node.confirm,
            enabled = node.enabled,
            words = words,
            onClick = { press(node.press, node.onPress, instance) },
            tag = "$tag:button",
            modifier = Modifier.fillMaxWidth(),
        )

        "field" -> ScriptFieldView(node, instance, tag)

        "select" -> ScriptSelectView(node, instance, own, tag)

        "empty" -> EmptyState(Lucide.Inbox, node.title.orEmpty(), own.emptyHint, Modifier.testTag("$tag:empty"))

        "text" -> Text(
            text = node.title.orEmpty(),
            modifier = Modifier.padding(horizontal = 4.dp).testTag("$tag:text"),
        )

        // A script one version ahead of this build: its node is named, not drawn, and nothing breaks.
        else -> Text(
            text = node.title ?: node.type,
            variant = TextVariant.Muted,
            modifier = Modifier.padding(horizontal = 4.dp).testTag("$tag:unknown"),
        )
    }
}

/**
 * A list, dragged into order when the script asked for it (`ui.list({ onReorder })`).
 *
 * The drag is the host's whole business: the row follows the finger, its neighbours make room, and
 * JS hears about it once — on release, with `{from, to}`. So a long list costs the script nothing
 * while it is being dragged, and a drop that changes nothing is not even a call.
 *
 * A list without `onReorder` is a plain column of rows, exactly as before.
 */
@Composable
private fun ScriptListView(node: ScriptNode, instance: ScriptAppInstance, words: RenderWords, tag: String) {
    val reorder = node.onReorder
    if (reorder == null) {
        Rows(Modifier) {
            node.items.forEachIndexed { index, row ->
                if (index > 0) ItemSeparator()
                ScriptRowView(row, instance, words, "$tag:row-${row.key ?: row.title}", null)
            }
        }
        return
    }

    var dragging by remember(node.items.size) { mutableStateOf<Int?>(null) }
    var offset by remember(node.items.size) { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableStateOf(1f) }

    /** Where the dragged row would land if it were dropped now. */
    fun target(from: Int): Int =
        (from + (offset / rowHeight).roundToInt()).coerceIn(0, node.items.lastIndex)

    Rows(Modifier.testTag("$tag:list")) {
        node.items.forEachIndexed { index, row ->
            if (index > 0) ItemSeparator()
            val from = dragging
            val shift = when {
                from == null -> 0f
                index == from -> offset
                // The row the dragged one is passing steps aside by exactly one row.
                from < index && index <= target(from) -> -rowHeight
                from > index && index >= target(from) -> rowHeight
                else -> 0f
            }
            Box(
                modifier = Modifier
                    .zIndex(if (index == from) 1f else 0f)
                    .graphicsLayer { translationY = shift }
                    .onGloballyPositioned { placed -> if (placed.size.height > 0) rowHeight = placed.size.height.toFloat() },
            ) {
                ScriptRowView(
                    row = row,
                    instance = instance,
                    words = words,
                    tag = "$tag:row-${row.key ?: row.title}",
                    drag = DragSpec(
                        active = index == from,
                        onStart = { dragging = index; offset = 0f },
                        onDrag = { delta -> offset += delta },
                        onEnd = {
                            val to = target(index)
                            dragging = null
                            offset = 0f
                            if (to != index) {
                                instance.handle(
                                    reorder,
                                    buildJsonObject {
                                        put("from", JsonPrimitive(index))
                                        put("to", JsonPrimitive(to))
                                    },
                                )
                            }
                        },
                        onCancel = { dragging = null; offset = 0f },
                    ),
                )
            }
        }
    }
}

/** What a row needs to be dragged: the handle reports to the list, and the list moves the rows. */
private data class DragSpec(
    val active: Boolean,
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

/** Rows in one bordered card, as the kit's groups are; hairlines between them are the caller's. */
@Composable
private fun Rows(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Kit.shapes.xl)
            .background(Kit.colors.card)
            .border(1.dp, Kit.colors.border, Kit.shapes.xl),
        content = content,
    )
}

/** A row of a list: its value or switch beside it, its handle after it, and its actions under it. */
@Composable
private fun ScriptRowView(row: ScriptRow, instance: ScriptAppInstance, words: RenderWords, tag: String, drag: DragSpec?) {
    val tappable = row.press != null || row.onPress != null
    Column(modifier = Modifier.fillMaxWidth().background(if (drag?.active == true) Kit.colors.accent else Color.Transparent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = byWindow(56.dp, 48.dp, 48.dp))
                .then(
                    if (tappable) {
                        Modifier.semantics(mergeDescendants = true) {}.kitClickable { press(row.press, row.onPress, instance) }
                    } else {
                        Modifier
                    },
                )
                .padding(start = Kit.spacing.lg, end = if (drag != null) 4.dp else Kit.spacing.md, top = 10.dp, bottom = 10.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Kit.spacing.md),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title.orEmpty(), style = Kit.textStyles.label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("$tag:title"))
                row.subtitle?.let { Text(it, variant = TextVariant.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("$tag:subtitle")) }
            }
            row.value?.let {
                Text(
                    it,
                    style = Kit.textStyles.number,
                    color = toneOfScript(row.tone)?.textColor() ?: Kit.colors.foreground,
                    maxLines = 1,
                    modifier = Modifier.testTag("$tag:value"),
                )
            }
            row.toggle?.let { checked ->
                Switch(
                    checked = checked,
                    onCheckedChange = row.onToggle?.let { handler -> { value: Boolean -> instance.handle(handler, JsonPrimitive(value)) } },
                    enabled = row.onToggle != null,
                    contentDescription = row.title.orEmpty(),
                    modifier = Modifier.testTag("$tag:toggle"),
                )
            }
            if (tappable && row.toggle == null && drag == null) {
                Icon(Lucide.ChevronRight, contentDescription = null, size = 16.dp, tint = Kit.colors.mutedForeground)
            }
            drag?.let { DragHandle(it, words, "$tag:drag") }
        }
        if (row.actions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Kit.spacing.lg, end = Kit.spacing.md, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.actions.forEachIndexed { index, action ->
                    Button(
                        text = action.title.orEmpty(),
                        onClick = { press(action.press, action.onPress, instance) },
                        // Red words for a dangerous action in a row; the red fill is for the question that follows.
                        variant = if (action.tone == "danger") ButtonVariant.Ghost else ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag("$tag:action-$index"),
                    )
                }
            }
        }
    }
}

/** The script's tone of a value, in the kit's: the same closed set the server's declarations use. */
private fun toneOfScript(tone: String?): Tone? = when (tone) {
    "success" -> Tone.Success
    "warning" -> Tone.Warn
    "danger" -> Tone.Danger
    "info" -> Tone.Running
    "muted" -> Tone.Muted
    else -> null
}

/**
 * The handle a row is dragged by: three lines, and the pointer is taken only on it, so the row's own
 * tap, its switch and its actions keep working while the list is reorderable.
 */
@Composable
private fun DragHandle(drag: DragSpec, words: RenderWords, tag: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(Kit.shapes.md)
            .background(if (drag.active) Kit.colors.muted else Color.Transparent)
            .semantics { contentDescription = words.reorder }
            .testTag(tag)
            .pointerInput(drag.onEnd) {
                detectDragGestures(
                    onDragStart = { drag.onStart() },
                    onDragEnd = { drag.onEnd() },
                    onDragCancel = { drag.onCancel() },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.onDrag(amount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Lucide.GripVertical, contentDescription = null, size = 18.dp, tint = Kit.colors.mutedForeground)
    }
}

/**
 * A line of text the person types.
 *
 * The letters and the cursor live here, in Compose, and the script is told the whole value — that is
 * the division of labour the plan asks for, and it is also why typing never waits for JS: the field
 * does not take back what the script drew while it is being typed into.
 */
@Composable
private fun ScriptFieldView(node: ScriptNode, instance: ScriptAppInstance, tag: String) {
    val key = node.id ?: tag
    var typed by remember(key) { mutableStateOf(node.value.orEmpty()) }
    var sent by remember(key) { mutableStateOf(node.value.orEmpty()) }
    // The script changed the value itself (it reset the form, say): take its word for it.
    val fromScript = node.value.orEmpty()
    if (fromScript != sent && fromScript != typed) {
        typed = fromScript
        sent = fromScript
    }

    val field: @Composable () -> Unit = {
        Input(
            value = typed,
            onValueChange = { value ->
                typed = value
                sent = value
                node.onChange?.let { instance.handle(it, JsonPrimitive(value)) }
            },
            placeholder = node.placeholder ?: node.title.orEmpty(),
            label = node.title ?: node.placeholder.orEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("$tag:field-$key"),
        )
    }
    val title = node.title
    if (title != null) Field(title) { field() } else field()
}

/** One of a list: the kit's select — a dropdown on a wide window, a sheet on a phone. */
@Composable
private fun ScriptSelectView(node: ScriptNode, instance: ScriptAppInstance, own: ScriptWords, tag: String) {
    val key = node.id ?: tag
    val select: @Composable () -> Unit = {
        Select(
            value = node.value ?: node.placeholder.orEmpty(),
            options = node.options.map { SelectOption(it.value, it.title, it.subtitle) },
            onSelect = { value -> node.onChange?.let { instance.handle(it, JsonPrimitive(value)) } },
            title = node.title ?: own.choose,
            modifier = Modifier.fillMaxWidth().testTag("$tag:select-$key"),
        )
    }
    val title = node.title
    if (title != null) Field(title) { select() } else select()
}

/** A tap: either an action the host performs itself, or a handler back in the script. */
private fun press(action: ScriptAction?, handler: Int?, instance: ScriptAppInstance) {
    when {
        action != null -> instance.perform(action)
        handler != null -> instance.handle(handler)
    }
}
