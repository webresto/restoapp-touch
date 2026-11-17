package cx.m42.restoapp.script

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cx.m42.restoapp.render.ChoiceSheet
import cx.m42.restoapp.render.ConfirmButton
import cx.m42.restoapp.render.EmptyState
import cx.m42.restoapp.render.ListRow
import cx.m42.restoapp.render.Notice
import cx.m42.restoapp.render.RenderWords
import cx.m42.restoapp.render.SheetChoice
import cx.m42.restoapp.render.SwitchControl
import cx.m42.superizer.runtime.LocalAppRuntime
import com.composeunstyled.Text
import cx.m42.superizer.ui.platform.ScreenSteps
import cx.m42.superizer.ui.platform.SystemBackHandler
import cx.m42.superizer.ui.platform.hostDrawsBack
import cx.m42.superizer.theme.AppTheme
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Input

/**
 * The screen a script drew (js-module.plan.md, stage 0): a tree of nodes, drawn by the components
 * `:render` already has — a script gets no components of its own, and an unknown node is a line of
 * text rather than a crash.
 */
@Composable
internal fun ScriptAppScreen(instance: ScriptAppInstance) {
    val runtime = LocalAppRuntime.current
    val lang by runtime.locale.langTag.collectAsState()
    val words = remember(lang) { RenderWords(lang) }
    val screen by instance.screen.collectAsState()
    val error by instance.error.collectAsState()
    val notice by instance.notice.collectAsState()
    val busy by instance.busy.collectAsState()
    val chrome by instance.chrome.collectAsState()
    LaunchedEffect(instance) { instance.start() }

    val tokens = AppTheme
    Box(modifier = Modifier.fillMaxSize().background(tokens.pageBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            notice?.let { text ->
                Notice(text, null, null, null, "script:notice")
            }
            error?.let { text ->
                Notice(text, "danger", words.retry, { instance.retry() }, "script:error")
            }
            ScreenSteps(instance.steps)
            if (chrome.canGoBack) {
                SystemBackHandler(enabled = true) { instance.back() }
                // `script:nested` — a screen standing on another one, which the way back returns from.
                Box(Modifier.testTag("script:nested"))
                // Only where the host has no arrow of its own: the shell's goes through the handler above.
                if (!hostDrawsBack) Button(
                    text = "‹ " + words.back,
                    onClick = instance::back,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.padding(8.dp).testTag("script:back"),
                )
            }
            val drawn = screen
            when {
                drawn != null -> ScriptNodeView(drawn.tree, instance, words, "script")
                error != null -> Unit // the notice above is the whole screen until a retry works
                else -> EmptyState(words.loading, null, null, null, "script:loading")
            }
        }
    }
}

/** One node of the tree. Stage 0 draws six; anything else says so, in place, and keeps the screen. */
@Composable
private fun ScriptNodeView(node: ScriptNode, instance: ScriptAppInstance, words: RenderWords, tag: String) {
    when (node.type) {
        "column" -> Column(modifier = Modifier.fillMaxWidth()) {
            node.children.forEachIndexed { index, child -> ScriptNodeView(child, instance, words, "$tag:$index") }
        }

        "section" -> Column(modifier = Modifier.fillMaxWidth()) {
            node.title?.let { title ->
                Text(
                    text = title,
                    style = AppTheme.label.copy(fontWeight = FontWeight.Medium),
                    color = AppTheme.muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp).testTag("$tag:title"),
                )
            }
            node.children.forEachIndexed { index, child -> ScriptNodeView(child, instance, words, "$tag:$index") }
        }

        "list" ->
            if (node.items.isEmpty()) {
                EmptyState(node.empty ?: words.nothingFound, null, null, null, "$tag:empty")
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )

        "field" -> ScriptFieldView(node, instance, tag)

        "select" -> ScriptSelectView(node, instance, words, tag)

        "empty" -> EmptyState(node.title.orEmpty(), null, null, null, "$tag:empty")

        "text" -> Text(
            text = node.title.orEmpty(),
            style = AppTheme.body,
            color = AppTheme.foreground,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("$tag:text"),
        )

        // A script one version ahead of this build: its node is named, not drawn, and nothing breaks.
        else -> Text(
            text = node.title ?: node.type,
            style = AppTheme.label,
            color = AppTheme.muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("$tag:unknown"),
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
        Column(modifier = Modifier.fillMaxWidth()) {
            node.items.forEach { row -> ScriptRowView(row, instance, words, "$tag:row-${row.key ?: row.title}", null) }
        }
        return
    }

    var dragging by remember(node.items.size) { mutableStateOf<Int?>(null) }
    var offset by remember(node.items.size) { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableStateOf(1f) }

    /** Where the dragged row would land if it were dropped now. */
    fun target(from: Int): Int =
        (from + (offset / rowHeight).roundToInt()).coerceIn(0, node.items.lastIndex)

    Column(modifier = Modifier.fillMaxWidth().testTag("$tag:list")) {
        node.items.forEachIndexed { index, row ->
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

/** A row of a list: its switch is drawn beside it, its handle after it, and its actions under it. */
@Composable
private fun ScriptRowView(row: ScriptRow, instance: ScriptAppInstance, words: RenderWords, tag: String, drag: DragSpec?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListRow(
            title = row.title.orEmpty(),
            subtitle = row.subtitle,
            meta = null,
            value = row.value,
            valueTone = row.tone,
            badge = null,
            badgeTone = null,
            tag = tag,
            onClick = if (row.press != null || row.onPress != null) {
                { press(row.press, row.onPress, instance) }
            } else {
                null
            },
            trailing = {
                row.toggle?.let { checked ->
                    SwitchControl(
                        checked = checked,
                        label = row.title.orEmpty(),
                        enabled = row.onToggle != null,
                        onChange = { value -> row.onToggle?.let { instance.handle(it, JsonPrimitive(value)) } },
                        tag = "$tag:toggle",
                    )
                }
                drag?.let { DragHandle(it, words, "$tag:drag") }
            },
        )
        if (row.actions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.actions.forEachIndexed { index, action ->
                    Button(
                        text = action.title.orEmpty(),
                        onClick = { press(action.press, action.onPress, instance) },
                        variant = if (action.tone == "danger") ButtonVariant.Destructive else ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag("$tag:action-$index"),
                    )
                }
            }
        }
    }
}

/**
 * The handle a row is dragged by: three lines, and the pointer is taken only on it, so the row's own
 * tap, its switch and its actions keep working while the list is reorderable.
 */
@Composable
private fun DragHandle(drag: DragSpec, words: RenderWords, tag: String) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(if (drag.active) tokens.muted.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp)
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
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(3) {
            Box(modifier = Modifier.size(width = 14.dp, height = 2.dp).background(tokens.muted, RoundedCornerShape(1.dp)))
        }
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

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        node.title?.let { Text(text = it, style = AppTheme.footnote, color = AppTheme.muted) }
        Input(
            value = typed,
            onValueChange = { value ->
                typed = value
                sent = value
                node.onChange?.let { instance.handle(it, JsonPrimitive(value)) }
            },
            placeholder = node.placeholder ?: node.title.orEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("$tag:field-$key"),
        )
    }
}

/** One of a list, picked in a sheet — the shape the point switcher has. */
@Composable
private fun ScriptSelectView(node: ScriptNode, instance: ScriptAppInstance, words: RenderWords, tag: String) {
    val key = node.id ?: tag
    var open by remember(key) { mutableStateOf(false) }
    val chosen = node.options.firstOrNull { it.value == node.value }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        node.title?.let { Text(text = it, style = AppTheme.footnote, color = AppTheme.muted) }
        Button(
            text = chosen?.title ?: node.value ?: node.placeholder.orEmpty(),
            onClick = { open = true },
            variant = ButtonVariant.Outline,
            size = ButtonSize.Lg,
            modifier = Modifier.fillMaxWidth().testTag("$tag:select-$key"),
        )
    }
    if (open) {
        Box(modifier = Modifier.fillMaxSize()) {
            ChoiceSheet(
                visible = true,
                title = node.title ?: words.set,
                choices = node.options.map { SheetChoice(it.value, it.title, it.subtitle) },
                selected = node.value,
                onDismiss = { open = false },
                onPick = { value ->
                    open = false
                    node.onChange?.let { instance.handle(it, JsonPrimitive(value)) }
                },
                tag = "$tag:sheet-$key",
            )
        }
    }
}

/** A tap: either an action the host performs itself, or a handler back in the script. */
private fun press(action: ScriptAction?, handler: Int?, instance: ScriptAppInstance) {
    when {
        action != null -> instance.perform(action)
        handler != null -> instance.handle(handler)
    }
}
