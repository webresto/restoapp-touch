package cx.m42.restoapp.script

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cx.m42.restoapp.render.ConfirmButton
import cx.m42.restoapp.render.EmptyState
import cx.m42.restoapp.render.ListRow
import cx.m42.restoapp.render.Notice
import cx.m42.restoapp.render.RenderWords
import cx.m42.restoapp.render.SwitchControl
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import com.composeunstyled.Text
import cx.m42.superizer.ui.platform.SystemBackHandler
import cx.m42.superizer.theme.AppTheme
import kotlinx.serialization.json.JsonPrimitive

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
            if (chrome.canGoBack) {
                SystemBackHandler(enabled = true) { instance.back() }
                AppButton(
                    text = "← " + words.back,
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
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(node.items, key = { it.key ?: it.title.orEmpty() }) { row ->
                        ScriptRowView(row, instance, words, "$tag:row-${row.key ?: row.title}")
                    }
                }
            }

        "button" -> ConfirmButton(
            text = node.title.orEmpty(),
            tone = node.tone,
            confirm = null,
            enabled = true,
            words = words,
            onClick = { press(node.press, node.onPress, instance) },
            tag = "$tag:button",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )

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

/** A row of a list: its switch is drawn beside it, and its actions under it. */
@Composable
private fun ScriptRowView(row: ScriptRow, instance: ScriptAppInstance, words: RenderWords, tag: String) {
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
            },
        )
        if (row.actions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.actions.forEachIndexed { index, action ->
                    AppButton(
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

/** A tap: either an action the host performs itself, or a handler back in the script. */
private fun press(action: ScriptAction?, handler: Int?, instance: ScriptAppInstance) {
    when {
        action != null -> instance.perform(action)
        handler != null -> instance.handle(handler)
    }
}
