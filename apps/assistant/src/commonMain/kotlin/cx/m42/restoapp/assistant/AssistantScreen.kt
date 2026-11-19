package cx.m42.restoapp.assistant

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.PlugZap
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Wrench
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.EmptyState
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.IconButton
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Notice
import cx.m42.superizer.kit.SideSheet
import cx.m42.superizer.kit.Skeleton
import cx.m42.superizer.kit.Spinner
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.byWindow
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.ui.platform.SystemBackHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** How wide the conversation reads: wider is a line nobody can follow back to its start. */
private val ChatWidth = 780.dp

/**
 * The chat, as the prototype's «Ассистент» draws it (`cloudApp/…/server/Assistant.kt`): on a wide window the
 * conversations in a 260 dp pane at the left and the chat beside it; narrower, the chat alone with the list in a
 * side sheet behind «История». The person's words in a tinted bubble at the right, the reply beside the assistant's
 * mark — Markdown with tables, tool calls as quiet cards, a link alone in a paragraph as a button — the typing dots
 * while it thinks, the welcome with suggestions of an empty conversation, and the composer pinned to the bottom.
 */
@Composable
internal fun AssistantScreen(instance: AssistantInstance) {
    val runtime = LocalAppRuntime.current
    val lang by runtime.locale.langTag.collectAsState()
    val words = remember(lang) { AssistantWords(lang) }
    val state by instance.state.collectAsState()
    LaunchedEffect(instance) { instance.start() }
    val wide = LocalWindowClass.current == WindowClass.Expanded
    SystemBackHandler(enabled = state.listOpen && !wide) { instance.openList(false) }

    Box(Modifier.fillMaxSize().background(Kit.colors.background).testTag("assistant")) {
        val status = state.status
        when {
            state.loading && status == null -> Loading()
            status == null -> Centered {
                EmptyState(
                    Lucide.PlugZap,
                    words.notConnected,
                    state.error ?: words.notConnectedHint,
                    Modifier.testTag("assistant:offline"),
                    action = { Button(words.retry, { instance.reload() }, variant = ButtonVariant.Outline) },
                )
            }
            !status.ready -> Centered {
                EmptyState(
                    Lucide.Sparkles,
                    status.screenTitle ?: words.notConnected,
                    status.screenText ?: status.lastError ?: words.notConnectedHint,
                    Modifier.testTag("assistant:setup"),
                    action = { Button(words.retry, { instance.reload() }, variant = ButtonVariant.Outline) },
                )
            }
            else -> Row(Modifier.fillMaxSize()) {
                if (wide) ConversationPane(state, words, instance)
                Column(Modifier.weight(1f).fillMaxHeight().imePadding()) {
                    ChatHeader(state, words, instance, wide)
                    state.error?.let { error ->
                        Box(Modifier.fillMaxWidth().padding(horizontal = byWindow(16.dp, 24.dp, 32.dp), vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
                            Notice(
                                Tone.Danger,
                                Lucide.TriangleAlert,
                                words.failedTitle,
                                error,
                                Modifier.widthIn(max = ChatWidth).testTag("assistant:error"),
                                action = { Button(words.close, { instance.dismissError() }, variant = ButtonVariant.Outline) },
                            )
                        }
                    }
                    Conversation(state, words, instance, Modifier.weight(1f))
                    Composer(state, words, instance)
                }
            }
        }
        if (!wide && state.listOpen && status?.ready == true) {
            SideSheet(
                onDismiss = { instance.openList(false) },
                title = words.conversations,
                closeLabel = words.close,
                footer = {
                    Button(
                        words.newConversation,
                        { instance.newConversation() },
                        Modifier.fillMaxWidth().testTag("assistant:new"),
                        variant = ButtonVariant.Outline,
                        enabled = !state.running && !state.busy,
                        leadingIcon = Lucide.SquarePen,
                    )
                },
            ) {
                ConversationRows(state, words, instance)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(byWindow(16.dp, 24.dp, 32.dp)), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 520.dp)) { content() }
    }
}

/** The first frame: the shape of a chat, not a spinner over nothing. */
@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize().padding(byWindow(16.dp, 24.dp, 32.dp)).testTag("assistant:loading"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Skeleton(Modifier.width(220.dp).height(32.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { Skeleton(Modifier.width(260.dp).height(40.dp)) }
        Skeleton(Modifier.fillMaxWidth(0.8f).height(64.dp))
        Skeleton(Modifier.fillMaxWidth(0.6f).height(40.dp))
    }
}

/** The assistant's mark: the sparkles on the accent, as the prototype draws its avatar. */
@Composable
private fun AssistantMark(size: Dp) {
    val brand = Kit.palette.brand
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(lerp(brand, Color.White, 0.08f), lerp(brand, Color.Black, 0.28f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Lucide.Sparkles, contentDescription = null, size = size * 0.52f, tint = Kit.palette.onBrand)
    }
}

/** The bar over the chat: the assistant, what it has spent, and — narrower — the history and a new conversation. */
@Composable
private fun ChatHeader(state: AssistantState, words: AssistantWords, instance: AssistantInstance, wide: Boolean) {
    val border = Kit.colors.border
    val idle = !state.running && !state.busy
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .drawBehind { drawLine(border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(start = byWindow(16.dp, 24.dp, 24.dp), end = byWindow(8.dp, 16.dp, 24.dp), top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AssistantMark(32.dp)
        Column(Modifier.weight(1f)) {
            Text(state.title.ifBlank { words.welcome }, style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.meta?.takeIf { it.totalTokens > 0 }?.let {
                Text(
                    listOfNotNull(it.model, words.tokens(it.totalTokens)).joinToString(" · "),
                    style = Kit.textStyles.caption,
                    color = Kit.colors.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("assistant:usage"),
                )
            }
        }
        if (state.loading || state.busy) Spinner(size = 16.dp, modifier = Modifier.testTag("assistant:busy"))
        if (!wide) {
            IconButton(
                Lucide.History,
                contentDescription = words.history,
                onClick = { instance.openList(true) },
                enabled = idle,
                modifier = Modifier.testTag("assistant:conversations"),
            )
            // While the sheet is up, «Новый диалог» is its own footer's.
            if (!state.listOpen) {
                IconButton(
                    Lucide.SquarePen,
                    contentDescription = words.newConversation,
                    onClick = { instance.newConversation() },
                    enabled = idle,
                    modifier = Modifier.testTag("assistant:new"),
                )
            }
        }
    }
}

/** The 260 dp pane of a wide window: a new conversation on top, then the panel's conversations. */
@Composable
private fun ConversationPane(state: AssistantState, words: AssistantWords, instance: AssistantInstance) {
    val border = Kit.colors.border
    Column(
        Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(Kit.palette.canvas)
            .drawBehind { drawLine(border, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx()) }
            .padding(12.dp)
            .testTag("assistant:pane"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            words.newConversation,
            { instance.newConversation() },
            Modifier.fillMaxWidth().testTag("assistant:new"),
            variant = ButtonVariant.Outline,
            enabled = !state.running && !state.busy,
            leadingIcon = Lucide.SquarePen,
        )
        Text(words.conversationsOverline, style = Kit.textStyles.overline, color = Kit.colors.mutedForeground, modifier = Modifier.padding(start = 10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { ConversationRows(state, words, instance) }
    }
}

/** The rows of the conversations, the active one lit; the others can be deleted. */
@Composable
private fun ConversationRows(state: AssistantState, words: AssistantWords, instance: AssistantInstance) {
    if (state.conversations.isEmpty()) {
        EmptyState(
            Lucide.MessageSquare,
            words.noConversations,
            words.noConversationsHint,
            Modifier.testTag("assistant:no-conversations"),
        )
        return
    }
    Column(Modifier.fillMaxWidth().testTag("assistant:list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        state.conversations.forEach { c ->
            val active = c.id == state.activeId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) Kit.colors.accent else Color.Transparent)
                    .kitClickable(RoundedCornerShape(9.dp), enabled = !state.running && !state.busy, hover = !active) { instance.select(c.id) }
                    .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                    .heightIn(min = 40.dp)
                    .testTag("assistant:conversation-${c.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Lucide.MessageSquare, null, size = 14.dp, tint = Kit.colors.mutedForeground)
                Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        c.title.ifBlank { words.untitled },
                        style = Kit.textStyles.label.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val stamp = if (active) words.active else c.updatedAt?.let(::shortDate)
                    stamp?.let { Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1) }
                }
                if (!active) {
                    // The panel deletes only another than the active conversation.
                    IconButton(
                        contentDescription = words.delete,
                        onClick = { instance.delete(c.id) },
                        modifier = Modifier.testTag("assistant:delete-${c.id}"),
                    ) { Icon(Lucide.Trash2, contentDescription = null, size = 16.dp, tint = Kit.colors.mutedForeground) }
                }
            }
        }
    }
}

/** `2026-11-18T10:05:00Z` → `18.11 10:05`: when, without the noise of an ISO stamp. */
private fun shortDate(iso: String): String {
    val date = iso.take(10).split('-')
    val time = iso.drop(11).take(5)
    return if (date.size == 3) "${date[2]}.${date[1]}" + if (time.length == 5) " $time" else "" else iso.take(16)
}

@Composable
private fun Conversation(state: AssistantState, words: AssistantWords, instance: AssistantInstance, modifier: Modifier) {
    val list = rememberLazyListState()
    val items = state.messages + listOfNotNull(state.reply)
    val tail = state.reply?.parts?.let { parts -> parts.size * 1000 + ((parts.lastOrNull() as? Part.Text)?.text?.length ?: 0) } ?: 0
    LaunchedEffect(items.size, tail) { if (items.isNotEmpty()) list.scrollToItem(items.lastIndex, Int.MAX_VALUE / 2) }
    val gutter = byWindow(16.dp, 32.dp, 32.dp)
    if (items.isEmpty()) {
        Box(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Welcome(state, words, instance, Modifier.padding(horizontal = gutter))
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth().testTag("assistant:messages"),
        state = list,
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items, key = { it.id }) { message ->
            Box(Modifier.fillMaxWidth().padding(horizontal = gutter), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = ChatWidth).fillMaxWidth()) {
                    if (message.role == Role.User) {
                        UserBubble(message)
                    } else {
                        Reply(message, words, running = state.running && message === state.reply, onLink = instance::open)
                    }
                }
            }
        }
    }
}

/** The empty conversation: the mark, what to ask, and the panel's suggestions as rows to tap. */
@Composable
private fun Welcome(state: AssistantState, words: AssistantWords, instance: AssistantInstance, modifier: Modifier) {
    val compact = LocalWindowClass.current == WindowClass.Compact
    Column(
        modifier.widthIn(max = ChatWidth).fillMaxWidth().padding(top = if (compact) 32.dp else 64.dp, bottom = 24.dp).testTag("assistant:welcome"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AssistantMark(56.dp)
        Text(
            state.title.ifBlank { words.welcome },
            Modifier.padding(top = 6.dp),
            variant = if (compact) TextVariant.H3 else TextVariant.H2,
            textAlign = TextAlign.Center,
        )
        state.status?.welcomeHint?.let { Text(it, Modifier.widthIn(max = 460.dp), variant = TextVariant.Muted, textAlign = TextAlign.Center) }
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.status?.suggestions.orEmpty().forEachIndexed { i, s ->
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Kit.colors.card)
                        .border(1.dp, Kit.colors.border, shape)
                        .kitClickable(shape, enabled = !state.busy && !state.running) { instance.send(s) }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("assistant:suggestion-$i"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(s, Modifier.weight(1f), style = Kit.typography.p)
                    Icon(Lucide.ChevronRight, contentDescription = null, size = 16.dp, tint = Kit.colors.mutedForeground)
                }
            }
        }
    }
}

/** The person's words: a tinted bubble at the right, its tail corner at the bottom right. */
@Composable
private fun UserBubble(message: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .padding(start = 48.dp)
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(Kit.palette.brandSoft)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("assistant:user"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (part in message.parts) {
                when (part) {
                    is Part.Text -> Text(part.text, style = Kit.typography.p, color = Kit.colors.foreground)
                    is Part.Attachment -> AttachmentLine(part)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun AttachmentLine(part: Part.Attachment) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Lucide.Paperclip, contentDescription = null, size = 14.dp, tint = Kit.colors.mutedForeground)
        Text(part.name, variant = TextVariant.Small)
    }
}

/** The reply beside the assistant's mark: tool cards, the reasoning folded, the text in Markdown. */
@Composable
private fun Reply(message: ChatMessage, words: AssistantWords, running: Boolean, onLink: (String) -> Unit) {
    val compact = LocalWindowClass.current == WindowClass.Compact
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!compact) AssistantMark(28.dp)
        Column(Modifier.weight(1f).testTag("assistant:reply"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (message.parts.isEmpty() && running) TypingDots(Modifier.testTag("assistant:thinking"), words.thinking)
            for (part in message.parts) {
                when (part) {
                    is Part.Text -> Markdown(part.text, Kit.colors.foreground, onLink)
                    is Part.Reasoning -> Folded(words.reasoning, null, Lucide.Sparkles, tag = "assistant:reasoning") {
                        Text(part.text, variant = TextVariant.Muted)
                    }
                    is Part.Tool -> Folded(
                        part.name,
                        when {
                            !part.finished -> words.running
                            part.isError -> words.failed
                            else -> null
                        },
                        Lucide.Wrench,
                        tag = "assistant:tool-${part.name}",
                        busy = !part.finished,
                        failed = part.isError,
                    ) {
                        part.args?.takeIf { it !is JsonNull }?.let { Code(words.input, it) }
                        part.result?.let { Code(words.output, it) }
                    }
                    is Part.Attachment -> AttachmentLine(part)
                }
            }
        }
    }
}

/** Three dots that take turns: the assistant is writing. */
@Composable
private fun TypingDots(modifier: Modifier, label: String) {
    val t by rememberInfiniteTransition().animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart))
    Row(
        modifier
            .clip(CircleShape)
            .background(Kit.colors.muted)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i -> Box(Modifier.size(7.dp).alpha(if (t.toInt() == i) 1f else 0.35f).clip(CircleShape).background(Kit.colors.mutedForeground)) }
    }
}

/**
 * A tool call or the reasoning: a quiet card with its name — a spinner while it runs, red when it failed — that
 * opens to what it was given and what it answered.
 */
@Composable
private fun Folded(
    title: String,
    badge: String?,
    icon: ImageVector,
    tag: String,
    busy: Boolean = false,
    failed: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable(tag + title) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .clip(shape)
                .background(Kit.colors.muted)
                .kitClickable(shape, hover = false) { open = !open }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) Spinner(size = 13.dp) else Icon(if (failed) Lucide.CircleAlert else icon, null, size = 13.dp, tint = if (failed) Tone.Danger.textColor() else Kit.colors.mutedForeground)
            Text(title, style = Kit.textStyles.caption.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            badge?.let { Text(it, style = Kit.textStyles.caption, color = if (failed) Tone.Danger.textColor() else Kit.colors.mutedForeground) }
            Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, contentDescription = null, size = 13.dp, tint = Kit.colors.mutedForeground)
        }
        if (open) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
}

@Composable
private fun Code(title: String, value: JsonElement) {
    val text = remember(value) { if (value is JsonPrimitive && value.isString) value.content else PrettyJson.encodeToString(JsonElement.serializer(), value) }
    Text(title, variant = TextVariant.Small)
    Text(
        text.take(4000),
        style = Kit.textStyles.monoSmall,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Kit.colors.border, RoundedCornerShape(8.dp))
            .background(Kit.palette.canvas)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    )
}

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

/**
 * The composer, pinned under the chat: a field as the kit draws one, with the round button inside it — send when
 * idle, stop while a reply runs — and, wider, the line that the assistant only prepares what it does.
 */
@Composable
private fun Composer(state: AssistantState, words: AssistantWords, instance: AssistantInstance) {
    val border = Kit.colors.border
    val compact = LocalWindowClass.current == WindowClass.Compact
    Box(
        Modifier
            .fillMaxWidth()
            .background(Kit.palette.canvas)
            .drawBehind { drawLine(border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = byWindow(12.dp, 32.dp, 32.dp), vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = ChatWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val canSend = state.draft.isNotBlank() && !state.busy
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Kit.shapes.lg)
                    .background(Kit.colors.background)
                    .border(1.dp, Kit.colors.input, Kit.shapes.lg)
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ComposerField(state, words, instance, Modifier.weight(1f))
                if (state.running) {
                    SendButton(Lucide.Square, words.stop, "assistant:stop", enabled = true) { instance.stop() }
                } else {
                    SendButton(Lucide.ArrowUp, words.send, "assistant:send", enabled = canSend) { instance.send() }
                }
            }
            if (!compact) {
                Text(words.disclaimer, Modifier.fillMaxWidth(), style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SendButton(icon: ImageVector, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled) Kit.palette.brand else Kit.colors.muted)
            .kitClickable(CircleShape, enabled = enabled, hover = false, role = SemanticsRole.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, size = 16.dp, tint = if (enabled) Kit.palette.onBrand else Kit.colors.mutedForeground)
    }
}

/** The field of the composer: no frame of its own — the composer is the frame — and up to six lines. */
@Composable
private fun ComposerField(state: AssistantState, words: AssistantWords, instance: AssistantInstance, modifier: Modifier) {
    val style = Kit.typography.p.copy(color = Kit.colors.foreground)
    BasicTextField(
        value = state.draft,
        onValueChange = instance::edit,
        textStyle = style,
        maxLines = 6,
        cursorBrush = SolidColor(Kit.colors.foreground),
        modifier = modifier.padding(vertical = 6.dp).testTag("assistant:input"),
        decorationBox = { field ->
            Box {
                if (state.draft.isEmpty()) {
                    Text(
                        state.status?.composerPlaceholder ?: words.placeholder,
                        style = style,
                        color = Kit.colors.mutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Not the field's text: what was typed is.
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                field()
            }
        },
    )
}
