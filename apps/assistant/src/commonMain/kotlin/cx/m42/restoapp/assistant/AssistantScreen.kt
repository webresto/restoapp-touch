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
import com.composeunstyled.Text
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.render.EmptyState
import cx.m42.restoapp.render.Notice
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.platform.SystemBackHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The chat, laid out as the panel's assistant (adminizer's ai-assistant bundle): the conversation with the reply
 * as it streams, tool calls and reasoning folded under their names, the welcome hint and suggestions of an empty
 * conversation, the composer with Stop while a reply runs, and the list of conversations behind «Диалоги».
 */
@Composable
internal fun AssistantScreen(instance: AssistantInstance) {
    val runtime = LocalAppRuntime.current
    val lang by runtime.locale.langTag.collectAsState()
    val words = remember(lang) { AssistantWords(lang) }
    val state by instance.state.collectAsState()
    LaunchedEffect(instance) { instance.start() }
    SystemBackHandler(enabled = state.listOpen) { instance.openList(false) }

    val tokens = AppTheme
    Box(Modifier.fillMaxSize().background(AppTheme.pageBackground).testTag("assistant")) {
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
        if ((state.loading || state.busy) && state.status != null) {
            Text(words.loading, style = tokens.footnote, color = tokens.muted, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).testTag("assistant:busy"))
        }
    }
}

@Composable
private fun Toolbar(state: AssistantState, words: AssistantWords, instance: AssistantInstance) {
    val tokens = AppTheme
    if (state.status?.ready != true) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppButton(
            text = if (state.listOpen) words.close else words.conversations,
            onClick = { instance.openList(!state.listOpen) },
            variant = ButtonVariant.Outline, size = ButtonSize.Sm,
            modifier = Modifier.testTag("assistant:conversations"),
            enabled = !state.running && !state.busy,
        )
        AppButton(
            text = words.newConversation,
            onClick = { instance.newConversation() },
            variant = ButtonVariant.Ghost, size = ButtonSize.Sm,
            modifier = Modifier.testTag("assistant:new"),
            enabled = !state.running && !state.busy,
        )
        Spacer(Modifier.weight(1f))
        state.meta?.takeIf { it.totalTokens > 0 }?.let {
            Text(words.tokens(it.totalTokens), style = tokens.footnote, color = tokens.muted, modifier = Modifier.testTag("assistant:usage"))
        }
    }
}

@Composable
private fun Conversation(state: AssistantState, words: AssistantWords, instance: AssistantInstance, modifier: Modifier) {
    val tokens = AppTheme
    val list = rememberLazyListState()
    val items = state.messages + listOfNotNull(state.reply)
    val tail = state.reply?.parts?.let { parts -> parts.size * 1000 + ((parts.lastOrNull() as? Part.Text)?.text?.length ?: 0) } ?: 0
    LaunchedEffect(items.size, tail) { if (items.isNotEmpty()) list.scrollToItem(items.lastIndex, Int.MAX_VALUE / 2) }
    if (items.isEmpty()) {
        Column(
            modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("assistant:welcome"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.title.ifBlank { words.welcome }, style = tokens.header, color = tokens.foreground)
            state.status?.welcomeHint?.let { Text(it, style = tokens.body, color = tokens.muted) }
            state.status?.suggestions.orEmpty().forEachIndexed { i, s ->
                Text(
                    s,
                    style = tokens.label, color = tokens.foreground,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(tokens.radius)).border(1.dp, tokens.border, RoundedCornerShape(tokens.radius))
                        .clickable(enabled = !state.busy) { instance.send(s) }.padding(horizontal = 14.dp, vertical = 10.dp).testTag("assistant:suggestion-$i"),
                )
            }
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth().testTag("assistant:messages"), state = list) {
        items(items, key = { it.id }) { message ->
            Bubble(message, words, running = state.running && message === state.reply, onLink = instance::open)
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, words: AssistantWords, running: Boolean, onLink: (String) -> Unit) {
    val tokens = AppTheme
    val mine = message.role == Role.User
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (mine) tokens.primary else tokens.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(if (mine) "assistant:user" else "assistant:reply"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val color = if (mine) tokens.primaryForeground else tokens.foreground
            if (message.parts.isEmpty() && running) Text(words.thinking, style = tokens.body, color = tokens.muted, modifier = Modifier.testTag("assistant:thinking"))
            for (part in message.parts) {
                when (part) {
                    is Part.Text -> if (mine) Text(part.text, style = tokens.body, color = color) else Markdown(part.text, color, onLink)
                    is Part.Reasoning -> Folded(words.reasoning, null, tag = "assistant:reasoning") {
                        Text(part.text, style = tokens.label, color = tokens.muted)
                    }
                    is Part.Tool -> Folded(
                        "${words.tool}: ${part.name}",
                        when { !part.finished -> words.running; part.isError -> words.failed; else -> null },
                        tag = "assistant:tool-${part.name}",
                    ) {
                        part.args?.takeIf { it !is JsonNull }?.let { Code(words.input, it) }
                        part.result?.let { Code(words.output, it) }
                    }
                    is Part.Attachment -> Text("📎 ${part.name}", style = tokens.label, color = color)
                }
            }
        }
    }
}

@Composable
private fun Folded(title: String, badge: String?, tag: String, content: @Composable () -> Unit) {
    val tokens = AppTheme
    var open by rememberSaveable(tag + title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, tokens.border, RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "▾ " else "▸ ", style = tokens.label, color = tokens.muted)
            Text(title, style = tokens.label.copy(fontWeight = FontWeight.Medium), color = tokens.foreground, modifier = Modifier.weight(1f))
            badge?.let { Text(it, style = tokens.footnote, color = tokens.muted) }
        }
        if (open) Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Code(title: String, value: JsonElement) {
    val tokens = AppTheme
    val text = remember(value) { if (value is JsonPrimitive && value.isString) value.content else PrettyJson.encodeToString(JsonElement.serializer(), value) }
    Text(title, style = tokens.footnote, color = tokens.muted)
    Text(
        text.take(4000),
        style = tokens.mono, color = tokens.foreground,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(tokens.muted.copy(alpha = 0.10f))
            .horizontalScroll(rememberScrollState()).padding(8.dp),
    )
}

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

@Composable
private fun Composer(state: AssistantState, words: AssistantWords, instance: AssistantInstance) {
    Row(
        Modifier.fillMaxWidth().background(AppTheme.background).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // AppTextField puts the modifier on the field itself and fills the width around it: the weight is the box's.
        Box(Modifier.weight(1f)) {
            AppTextField(
                value = state.draft,
                onValueChange = instance::edit,
                placeholder = state.status?.composerPlaceholder ?: words.placeholder,
                label = "",
                singleLine = false,
                minHeight = 44.dp,
                modifier = Modifier.testTag("assistant:input"),
            )
        }
        if (state.running) {
            AppButton(words.stop, { instance.stop() }, variant = ButtonVariant.Outline, size = ButtonSize.Default, modifier = Modifier.testTag("assistant:stop"))
        } else {
            AppButton(words.send, { instance.send() }, enabled = state.draft.isNotBlank() && !state.busy, size = ButtonSize.Default, modifier = Modifier.testTag("assistant:send"))
        }
    }
}

@Composable
private fun ConversationList(state: AssistantState, words: AssistantWords, instance: AssistantInstance, modifier: Modifier) {
    val tokens = AppTheme
    if (state.conversations.isEmpty()) {
        Box(modifier) { EmptyState(words.noConversations, null, words.newConversation, { instance.newConversation() }, "assistant:no-conversations") }
        return
    }
    LazyColumn(modifier.fillMaxWidth().testTag("assistant:list")) {
        items(state.conversations, key = { it.id }) { c ->
            val active = c.id == state.activeId
            Row(
                Modifier.fillMaxWidth().clickable { instance.select(c.id) }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("assistant:conversation-${c.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.title.ifBlank { words.untitled }, style = tokens.body.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal), color = tokens.foreground, maxLines = 2)
                    c.updatedAt?.let { Text(it.take(16).replace('T', ' '), style = tokens.footnote, color = tokens.muted) }
                }
                if (active) Text(words.active, style = tokens.footnote, color = tokens.primary)
                else Text(words.delete, style = tokens.footnote, color = tokens.danger, modifier = Modifier.clickable { instance.delete(c.id) }.padding(8.dp).testTag("assistant:delete-${c.id}"))
            }
        }
    }
}
