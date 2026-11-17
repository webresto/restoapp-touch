package cx.m42.restoapp.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.render.EmptyState
import cx.m42.restoapp.render.Notice
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.platform.SystemBackHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.IconButton
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Spinner
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Input

/**
 * The chat, laid out as the panel's assistant (adminizer's ai-assistant bundle) and drawn the way a phone's chat
 * is (ChatGPT on the iPhone): the conversation on white, the person's words in a grey bubble on the right and the
 * reply as plain text across the screen, tool calls and reasoning folded under their names, the welcome and the
 * suggestions of an empty conversation, a rounded composer with a round send (Stop while a reply runs), and the
 * list of conversations behind the icon at the top left.
 */
@Composable
internal fun AssistantScreen(instance: AssistantInstance) {
    val runtime = LocalAppRuntime.current
    val lang by runtime.locale.langTag.collectAsState()
    val words = remember(lang) { AssistantWords(lang) }
    val state by instance.state.collectAsState()
    LaunchedEffect(instance) { instance.start() }
    SystemBackHandler(enabled = state.listOpen) { instance.openList(false) }

    Box(Modifier.fillMaxSize().background(Kit.colors.background).testTag("assistant")) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Toolbar(state, words, instance)
            state.error?.let { Notice(it, Tones.DANGER, words.close, { instance.dismissError() }, "assistant:error") }
            val status = state.status
            when {
                state.loading && status == null -> EmptyState(words.loading, null, null, null, "assistant:loading")
                status == null -> EmptyState(words.notConnected, null, words.retry, { instance.reload() }, "assistant:offline")
                !status.ready -> EmptyState(
                    status.screenTitle ?: words.notConnected,
                    status.screenText ?: status.lastError,
                    words.retry, { instance.reload() }, "assistant:setup",
                )
                state.listOpen -> ConversationList(state, words, instance, Modifier.weight(1f))
                else -> {
                    Conversation(state, words, instance, Modifier.weight(1f))
                    Composer(state, words, instance)
                }
            }
        }
    }
}

/** The conversations at the left, the usage and a new conversation at the right — ChatGPT's bar, under the shell's. */
@Composable
private fun Toolbar(state: AssistantState, words: AssistantWords, instance: AssistantInstance) {
    if (state.status?.ready != true) return
    val idle = !state.running && !state.busy
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            if (state.listOpen) Lucide.X else Lucide.PanelLeft,
            contentDescription = if (state.listOpen) words.close else words.conversations,
            onClick = { instance.openList(!state.listOpen) },
            enabled = idle,
            modifier = Modifier.testTag("assistant:conversations"),
        )
        Text(
            text = if (state.listOpen) words.conversations else state.title.ifBlank { words.welcome },
            style = Kit.typography.p.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        if ((state.loading || state.busy) && state.status != null) {
            Spinner(size = 16.dp, modifier = Modifier.padding(end = 8.dp).testTag("assistant:busy"))
        }
        state.meta?.takeIf { it.totalTokens > 0 && !state.listOpen }?.let {
            Text(words.tokens(it.totalTokens), variant = TextVariant.Muted, modifier = Modifier.padding(end = 4.dp).testTag("assistant:usage"))
        }
        IconButton(
            Lucide.SquarePen,
            contentDescription = words.newConversation,
            onClick = { instance.newConversation() },
            enabled = idle,
            modifier = Modifier.testTag("assistant:new"),
        )
    }
}

@Composable
private fun Conversation(state: AssistantState, words: AssistantWords, instance: AssistantInstance, modifier: Modifier) {
    val list = rememberLazyListState()
    val items = state.messages + listOfNotNull(state.reply)
    val tail = state.reply?.parts?.let { parts -> parts.size * 1000 + ((parts.lastOrNull() as? Part.Text)?.text?.length ?: 0) } ?: 0
    LaunchedEffect(items.size, tail) { if (items.isNotEmpty()) list.scrollToItem(items.lastIndex, Int.MAX_VALUE / 2) }
    if (items.isEmpty()) {
        Column(
            modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp).testTag("assistant:welcome"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Kit.colors.muted), contentAlignment = Alignment.Center) {
                Icon(Lucide.Sparkles, contentDescription = null, size = 24.dp)
            }
            Text(state.title.ifBlank { words.welcome }, variant = TextVariant.H3, textAlign = TextAlign.Center)
            state.status?.welcomeHint?.let { Text(it, variant = TextVariant.Muted, textAlign = TextAlign.Center) }
            Spacer(Modifier.size(4.dp))
            state.status?.suggestions.orEmpty().forEachIndexed { i, s ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Kit.colors.border, RoundedCornerShape(16.dp))
                        .clickable(enabled = !state.busy) { instance.send(s) }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("assistant:suggestion-$i"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Lucide.MessageSquare, contentDescription = null, size = 16.dp, tint = Kit.colors.mutedForeground)
                    Text(s, variant = TextVariant.Small, modifier = Modifier.weight(1f))
                }
            }
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth().testTag("assistant:messages"), state = list) {
        items(items, key = { it.id }) { message ->
            Bubble(message, words, running = state.running && message === state.reply, onLink = instance::open)
        }
        item { Spacer(Modifier.size(12.dp)) }
    }
}

/** The person's words in a grey bubble at the right; the reply as plain text across the screen, as ChatGPT draws it. */
@Composable
private fun Bubble(message: ChatMessage, words: AssistantWords, running: Boolean, onLink: (String) -> Unit) {
    val mine = message.role == Role.User
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (mine) 8.dp else 6.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            (if (mine) Modifier.padding(start = 48.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(Kit.colors.muted).padding(horizontal = 14.dp, vertical = 9.dp)
            else Modifier.widthIn(max = 720.dp).fillMaxWidth())
                .testTag(if (mine) "assistant:user" else "assistant:reply"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val color = Kit.colors.foreground
            if (message.parts.isEmpty() && running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(size = 14.dp)
                    Text(words.thinking, variant = TextVariant.Muted, modifier = Modifier.testTag("assistant:thinking"))
                }
            }
            for (part in message.parts) {
                when (part) {
                    is Part.Text -> if (mine) Text(part.text) else Markdown(part.text, color, onLink)
                    is Part.Reasoning -> Folded(words.reasoning, null, tag = "assistant:reasoning") {
                        Text(part.text, variant = TextVariant.Muted)
                    }
                    is Part.Tool -> Folded(
                        "${words.tool}: ${part.name}",
                        when { !part.finished -> words.running; part.isError -> words.failed; else -> null },
                        tag = "assistant:tool-${part.name}",
                        busy = !part.finished,
                    ) {
                        part.args?.takeIf { it !is JsonNull }?.let { Code(words.input, it) }
                        part.result?.let { Code(words.output, it) }
                    }
                    is Part.Attachment -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Lucide.Paperclip, contentDescription = null, size = 14.dp, tint = Kit.colors.mutedForeground)
                        Text(part.name, variant = TextVariant.Small)
                    }
                }
            }
        }
    }
}

/** A tool call or the reasoning: one quiet line with its name, opened by a tap. */
@Composable
private fun Folded(title: String, badge: String?, tag: String, busy: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(tag + title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (busy) Spinner(size = 12.dp)
            Text(title, variant = TextVariant.Muted)
            badge?.let { Text("· $it", variant = TextVariant.Muted) }
            Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, contentDescription = null, size = 14.dp, tint = Kit.colors.mutedForeground)
        }
        if (open) Column(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@Composable
private fun Code(title: String, value: JsonElement) {
    val text = remember(value) { if (value is JsonPrimitive && value.isString) value.content else PrettyJson.encodeToString(JsonElement.serializer(), value) }
    Text(title, variant = TextVariant.Small)
    Text(
        text.take(4000),
        variant = TextVariant.Mono,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Kit.colors.muted)
            .horizontalScroll(rememberScrollState()).padding(10.dp),
    )
}

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

/** One rounded field with the round button inside it: send when idle, stop while a reply runs. */
@Composable
private fun Composer(state: AssistantState, words: AssistantWords, instance: AssistantInstance) {
    val canSend = state.draft.isNotBlank() && !state.busy
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Kit.colors.background)
            .border(1.dp, Kit.colors.border, RoundedCornerShape(26.dp))
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.weight(1f)) {
            ComposerField(state, words, instance)
        }
        val (icon, label, tag, enabled, onClick) = if (state.running) {
            Send(Lucide.Square, words.stop, "assistant:stop", true) { instance.stop() }
        } else {
            Send(Lucide.ArrowUp, words.send, "assistant:send", canSend) { instance.send() }
        }
        Box(
            Modifier.padding(4.dp).size(36.dp).clip(CircleShape)
                .background(if (enabled) Kit.colors.primary else Kit.colors.muted)
                .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, size = 18.dp, tint = if (enabled) Kit.colors.onPrimary else Kit.colors.mutedForeground)
        }
    }
}

private data class Send(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val tag: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

/** The field of the composer: no frame of its own, the composer is the frame. */
@Composable
private fun ComposerField(state: AssistantState, words: AssistantWords, instance: AssistantInstance) {
    val style = Kit.typography.p.copy(color = Kit.colors.foreground)
    androidx.compose.foundation.text.BasicTextField(
        value = state.draft,
        onValueChange = instance::edit,
        textStyle = style,
        maxLines = 6,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Kit.colors.foreground),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp).testTag("assistant:input"),
        decorationBox = { field ->
            Box {
                if (state.draft.isEmpty()) {
                    Text(
                        state.status?.composerPlaceholder ?: words.placeholder,
                        style = style,
                        color = Kit.colors.mutedForeground,
                        maxLines = 1,
                        // Not the field's text: what was typed is.
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                field()
            }
        },
    )
}

@Composable
private fun ConversationList(state: AssistantState, words: AssistantWords, instance: AssistantInstance, modifier: Modifier) {
    if (state.conversations.isEmpty()) {
        Box(modifier) { EmptyState(words.noConversations, null, words.newConversation, { instance.newConversation() }, "assistant:no-conversations") }
        return
    }
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("assistant:list")) {
        items(state.conversations, key = { it.id }) { c ->
            val active = c.id == state.activeId
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Kit.colors.muted else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { instance.select(c.id) }
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                    .testTag("assistant:conversation-${c.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        c.title.ifBlank { words.untitled },
                        style = Kit.typography.p.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 2,
                    )
                    c.updatedAt?.let { Text(it.take(16).replace('T', ' '), variant = TextVariant.Muted) }
                }
                if (active) {
                    Text(words.active, variant = TextVariant.Muted, modifier = Modifier.padding(end = 12.dp))
                } else {
                    IconButton(
                        Lucide.Trash2,
                        contentDescription = words.delete,
                        onClick = { instance.delete(c.id) },
                        modifier = Modifier.testTag("assistant:delete-${c.id}"),
                    )
                }
            }
        }
    }
}
