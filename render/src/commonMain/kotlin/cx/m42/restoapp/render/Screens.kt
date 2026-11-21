package cx.m42.restoapp.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import cx.m42.superizer.kit.ActionBar
import cx.m42.superizer.kit.Dialog
import cx.m42.superizer.kit.Menu
import cx.m42.superizer.kit.MenuItem
import cx.m42.superizer.kit.MenuLabel
import cx.m42.superizer.kit.MenuSeparator
import cx.m42.superizer.kit.Pill
import cx.m42.superizer.kit.Tone
import com.composables.icons.lucide.Ellipsis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import cx.m42.superizer.kit.color
import cx.m42.superizer.kit.palette
import com.composables.icons.lucide.LayoutDashboard
import com.composables.icons.lucide.ArrowUpRight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SearchX
import cx.m42.restoapp.protocol.Controls
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.Formats
import cx.m42.restoapp.protocol.ItemUi
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.RecordAction
import cx.m42.restoapp.protocol.RecordLink
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.protocol.Views
import cx.m42.restoapp.protocol.actions
import cx.m42.restoapp.protocol.defaultString
import cx.m42.restoapp.protocol.enumValues
import cx.m42.restoapp.protocol.id
import cx.m42.restoapp.protocol.links
import cx.m42.restoapp.protocol.open
import cx.m42.restoapp.protocol.property
import cx.m42.restoapp.protocol.propertyNames
import cx.m42.restoapp.protocol.requiredNames
import cx.m42.restoapp.protocol.schemaFormat
import cx.m42.restoapp.protocol.schemaTitle
import cx.m42.restoapp.protocol.schemaType
import cx.m42.restoapp.protocol.titles
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Crumb
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.IconButton
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Page
import cx.m42.superizer.kit.PageHeader
import cx.m42.superizer.kit.Panel
import cx.m42.superizer.kit.Skeleton
import cx.m42.superizer.kit.SwitchItem
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.ui.platform.ScreenSteps
import cx.m42.superizer.ui.platform.SystemBackHandler
import cx.m42.superizer.ui.platform.hostDrawsBack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Everything a screen needs to turn a record into text, in one place. */
internal class RenderContext(
    val app: DeclaredApp,
    val facts: ServerFacts?,
    val words: RenderWords,
    val now: Long,
) {
    val tag: String = app.id

    fun field(model: String?, name: String?): Field? = name?.let { app.fields(model).firstOrNull { it.name == name } }

    /**
     * The value a row or the head of a card names. The same rule as for the lines of a card: a field the record
     * does not carry at all is not drawn — a «—» pill says nothing — and one that is there and empty is a «—».
     */
    fun show(record: JsonObject, model: String?, name: String?): Shown? =
        name?.takeIf(record::containsKey)?.let { showValue(record[it], field(model, it), facts, words, now) }

    /** The record's actions this app declares, drawn at [placement]. */
    fun actions(record: JsonObject, placement: String): List<Pair<RecordAction, DeclaredMethod>> =
        record.actions().mapNotNull { action ->
            app.method(action.method)
                ?.takeIf { it.kind == DeclaredMethod.KIND_MUTATION && placement in it.ui?.placement.orEmpty() }
                ?.let { action to it }
        }
}

/*
 * Every frame is a kit `Page` (03-render.md): the page keeps the gutters and the width, the shell keeps the
 * background. The header is the kit's `PageHeader`: the app or the record, its state, its point, its main
 * actions, and — wider than a phone, where an adaptive shell draws no bar — the crumbs of the stack.
 */

@Composable
internal fun ServerAppScreen(instance: ServerAppInstance) {
    val runtime = LocalAppRuntime.current
    val lang by runtime.locale.langTag.collectAsState()
    val words = remember(lang) { RenderWords(lang) }
    val app by instance.server.app.collectAsState()
    val facts by instance.server.facts.collectAsState()
    val places by instance.server.places.collectAsState()
    LaunchedEffect(instance) { instance.start() }

    val declared = app
    // No background of its own: the frame is the shell's, and a page of another grey on it is a stripe.
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            declared == null -> StatePage(Lucide.Inbox, words.gone, words.goneHint, "server-app:gone")
            // Either the app comes as JS, or it asks for a native screen this build does not have:
            // the universal renderer cannot draw either, and the host only lands here when nothing
            // else can (order-entry.plan.md, «Контракт»).
            declared.script != null || declared.client != null ->
                StatePage(Lucide.Download, words.needsNewerApp, words.needsNewerAppHint, "${declared.id}:needs-update")
            declared.byPlace && places.isEmpty() -> StatePage(Lucide.MapPin, words.noPlaces, words.noPlacesHint, "${declared.id}:no-places")
            else -> {
                val context = RenderContext(declared, facts, words, runtime.clock.now())
                val frames by instance.frames.collectAsState()
                val request by instance.native.collectAsState()
                val native = request?.let { NativeActions.of(it.method.ui?.control?.screen)?.to(it) }
                ScreenSteps(frames.size - 1 + if (native != null) 1 else 0)
                val frame = frames.lastOrNull()
                val view = frame?.let { declared.method(it.method)?.ui?.view }
                val crumbs = crumbsOf(instance, context, frames)
                // A screen of the client's own that presents itself as a dialog or a side sheet is laid over
                // the frame, which stays drawn under it; any other one takes the frame's place.
                val overlay = native != null &&
                    NativeActions.presentationOf(native.second.method.ui?.control?.screen) == NativePresentation.Overlay
                // P5: the sections of the app — a bar at the bottom of a phone, tabs under the header wider. Wider,
                // only over the section's own screen: a record opened from it has its crumbs for the way back.
                val sections = remember(declared) { sectionsOf(declared) }
                val bottomMethod = frames.firstOrNull()?.method
                val inSections = sections.isNotEmpty() && (native == null || overlay) &&
                    sections.any { it.name == bottomMethod } && (compact || frames.size == 1)
                SectionsFrame(
                    sections = if (inSections) sections else emptyList(),
                    // A section reached by a link from another one («Промокоды» from the summary) is the one lit.
                    selected = frames.lastOrNull { f -> sections.any { it.name == f.method } }?.method ?: bottomMethod,
                    onSelect = instance::openSection,
                ) {
                when {
                    // A screen of the client's own, over everything: it has its own way back.
                    native != null && !overlay -> {
                        SystemBackHandler(enabled = true) { instance.leave(instance::closeNative) }
                        NativeActionScreen(instance, context, native.second, native.first)
                    }
                    frame == null -> StatePage(Lucide.Inbox, words.unknownView, null, "${declared.id}:unknown")
                    // `:nested` — a frame standing on another one, which the way back returns from.
                    frames.size > 1 -> Box(Modifier.fillMaxSize().testTag("${declared.id}:nested")) {
                        SystemBackHandler(enabled = true) { instance.leave(instance::back) }
                        when (view) {
                            Views.LIST -> ListScreen(instance, context, frame, crumbs, bottom = false)
                            Views.DETAIL -> DetailScreen(instance, context, frame, crumbs)
                            else -> StatePage(Lucide.Inbox, words.unknownView, null, "${declared.id}:unknown")
                        }
                    }
                    view == Views.LIST -> ListScreen(instance, context, frame, crumbs, bottom = true)
                    view == Views.DETAIL -> HomeRecordScreen(instance, context, frame)
                    else -> StatePage(Lucide.Inbox, words.unknownView, null, "${declared.id}:unknown")
                }
                }
                if (native != null && overlay) {
                    // After the frame, so its way back is the one claimed last — the one back takes.
                    SystemBackHandler(enabled = true) { instance.leave(instance::closeNative) }
                    NativeActionScreen(instance, context, native.second, native.first)
                }
            }
        }
        val leaving by instance.leaving.collectAsState()
        val unsaved by instance.unsaved.collectAsState()
        if (leaving != null) {
            UnsavedQuestion(
                words = words,
                canSave = unsaved?.save != null,
                onSave = instance::saveAndLeave,
                onLeave = instance::leaveWithoutSaving,
                onStay = instance::stay,
            )
        }
    }
}

/** The kit's `AppSections` around [content] when there are [sections]; [content] alone when there are none. */
@Composable
private fun SectionsFrame(sections: List<DeclaredMethod>, selected: String?, onSelect: (String) -> Unit, content: @Composable () -> Unit) {
    if (sections.isEmpty()) {
        content()
        return
    }
    val items = sections.mapIndexed { index, method -> sectionItem(method, home = index == 0) }
    cx.m42.superizer.kit.AppSections(items = items, selected = selected ?: items.first().id, onSelect = onSelect, content = content)
}

/** A page that is only a state: the app is gone, needs a newer client, has no points, or a view it cannot draw. */
@Composable
private fun StatePage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String?, tag: String) {
    Page(width = cx.m42.superizer.kit.PageWidth.Narrow) {
        Box(Modifier.height(24.dp))
        EmptyBlock(icon, title, hint.orEmpty(), tag)
    }
}

/**
 * The stack as crumbs: the app, then each frame by the words of the link that opened it, the last one —
 * where you are — without a click. One frame is no path; the header then has none.
 */
private fun crumbsOf(instance: ServerAppInstance, context: RenderContext, frames: List<Frame>): List<Crumb> {
    if (frames.size < 2) return emptyList()
    return frames.indices.map { index ->
        val label = instance.titleAt(context.app, frames, index).orEmpty()
        Crumb(label, if (index == frames.lastIndex) null else ({ instance.leave { instance.backTo(index + 1) } }))
    }
}

/**
 * «‹ Назад» of a frame that stands on another one — only where nothing else offers the way back. Inside
 * the shell its arrow or the crumbs do (both go through the same `SystemBackHandler`), and a second
 * «Назад» on the screen would be one too many.
 */
@Composable
private fun BackLine(instance: ServerAppInstance, context: RenderContext) {
    if (hostDrawsBack) return
    Button(
        onClick = { instance.leave(instance::back) },
        variant = ButtonVariant.Ghost,
        size = ButtonSize.Sm,
        modifier = Modifier.testTag("${context.tag}:back"),
    ) {
        Icon(Lucide.ChevronLeft, contentDescription = null, size = 16.dp)
        Text(context.words.back, variant = TextVariant.Small)
    }
}

/** The screen of one action the client draws itself, and what it is given (`NativeActions`). */
@Composable
private fun NativeActionScreen(
    instance: ServerAppInstance,
    context: RenderContext,
    request: NativeRequest,
    content: NativeActionContent,
) {
    val busy by instance.busy.collectAsState()
    val failure by instance.nativeFailure.collectAsState()
    val key = (request.record.id() ?: "list") + ":" + request.method.name
    Column(modifier = Modifier.fillMaxSize().testTag("${context.tag}:native-${request.method.ui?.control?.screen}")) {
        content(
            NativeActionScope(
                record = request.record,
                method = request.method,
                facts = context.facts,
                lang = context.words.lang,
                busy = key in busy,
                failure = failure,
                call = { method, params -> instance.callForNative(method, params) },
                submit = { params -> submitNative(instance, request, params) {} },
                submitThen = { params, saved -> submitNative(instance, request, params, saved) },
                notice = instance::showNotice,
                close = instance::closeNative,
                leave = { instance.leave(instance::closeNative) },
                hold = instance::hold,
            ),
        )
    }
}

/**
 * What a native screen's «Сохранить» does: run the action and close, with the refusal left on the
 * screen itself (`native = true`) — it covers the notices, and the person is looking at the very
 * value that was refused.
 */
private fun submitNative(instance: ServerAppInstance, request: NativeRequest, params: JsonObject, saved: () -> Unit) {
    val done = {
        saved()
        instance.closeNative()
    }
    if (request.record.id() == null) {
        instance.actOnList(request.method, params, native = true, onDone = done)
    } else {
        instance.actWith(request.record, request.action, request.method, params, native = true, onDone = done)
    }
}

/** The connection, the host's notice and the last error, as lines under the header. */
@Composable
private fun TopNotices(instance: ServerAppInstance, context: RenderContext) {
    val words = context.words
    val runtime = LocalAppRuntime.current
    val status by instance.server.status.collectAsState()
    val error by instance.error.collectAsState()
    val notice by instance.notice.collectAsState()
    val tag = context.tag
    when (status) {
        ServerStatus.Offline -> NoticeBar(words.offline, Tones.WARNING, words.retry, instance::retry, "$tag:offline")
        ServerStatus.SignedOut -> NoticeBar(words.signedOut + ". " + words.signedOutHint, Tones.DANGER, words.toHome, {
            runtime.scope.launch { runtime.navigation.close() }
        }, "$tag:signed-out")
        ServerStatus.UpdateRequired -> NoticeBar(words.updateRequired, Tones.DANGER, null, null, "$tag:update")
        ServerStatus.Online -> Unit
    }
    notice?.let { NoticeBar(it, Tones.INFO, "OK", instance::dismissNotice, "$tag:notice") }
    // A failed re-read is a line over the list; what was on screen stays under it.
    error?.takeIf { it.text.isNotEmpty() && it.code != MobileApiException.NETWORK }?.let {
        NoticeBar(it.text, Tones.DANGER, words.retry, instance::retry, "$tag:error")
    }
}

/**
 * The point of an app by points, in its header (В3): the choice when there is one — a frame standing on
 * another one does not offer it, the point belongs to the app, not to a screen of it — and only its name
 * when there is not.
 */
@Composable
private fun RowScope.PlaceMeta(instance: ServerAppInstance, context: RenderContext, choosable: Boolean) {
    if (!context.app.byPlace) return
    val places by instance.server.places.collectAsState()
    val place by instance.server.place.collectAsState()
    if (choosable && places.size > 1) {
        TaggedSelect(
            choices = places.map { PickChoice(it.id, it.title, it.address) },
            selected = place?.id,
            title = context.words.places,
            onPick = instance.server::selectPlace,
            tag = "${context.tag}:place",
            itemTag = { "${context.tag}:places:item-$it" },
            sheetTag = "${context.tag}:places",
            leadingIcon = Lucide.MapPin,
            small = true,
        )
    } else {
        place?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Lucide.MapPin, contentDescription = null, size = 14.dp, tint = Kit.colors.mutedForeground)
                Text(it.title, variant = TextVariant.Muted, maxLines = 1)
            }
        }
    }
}

private val compact: Boolean
    @Composable get() = LocalWindowClass.current == WindowClass.Compact

/** What the shell's bar says over the top frame: the app's name over the first one, the frame's words over the rest. */
@Composable
private fun barTitle(instance: ServerAppInstance, context: RenderContext): String? {
    val frames by instance.frames.collectAsState()
    return if (frames.size > 1) instance.titleAt(context.app, frames, frames.lastIndex) else context.app.title
}

/**
 * The kit's `PageHeader`: the title, the line under it, the state, the point and the actions. [bar] is what the
 * shell's bar says over the frame — kept for the callers, the page draws its title regardless.
 */
@Composable
private fun Header(
    title: String,
    @Suppress("UNUSED_PARAMETER") bar: String?,
    description: String? = null,
    crumbs: List<Crumb> = emptyList(),
    badge: (@Composable () -> Unit)? = null,
    meta: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    // The shell's bar over an app says the server and the way back, not the app's name (_plan/07): the page
    // has its own title, as the prototype's.
    PageHeader(title = title, description = description, crumbs = crumbs, badge = badge, meta = meta, actions = actions)
}

// ───────────────────────────── a list ─────────────────────────────

@Composable
private fun ListScreen(instance: ServerAppInstance, context: RenderContext, frame: Frame, crumbs: List<Crumb>, bottom: Boolean) {
    val method = context.app.method(frame.method) ?: return
    val state = frame.list
    val busy by instance.busy.collectAsState()
    val frames by instance.frames.collectAsState()
    val ui = method.ui!!
    val words = context.words
    val tag = context.tag

    // An action on the list itself — `ui.on: "list"`, placement `list`: «+ Новый клиент».
    val listAction = context.app.methods.firstOrNull { method ->
        method.ui?.view == Views.ACTION && method.ui?.on == "list" && method.ui?.placement?.contains("list") == true
    }
    var adding by remember(listAction?.name) { mutableStateOf(false) }
    // An action of the list may ask for a screen of the client's own, exactly as an action of a record
    // may: the transfer of the settings is one of those («Настройки», slice D). Without a screen for
    // it registered, the declared form is drawn — which is what every other list action gets.
    val nativeList = listAction
        ?.takeIf { it.ui?.control?.type == Controls.NATIVE }
        ?.let { action -> NativeActions.of(action.ui?.control?.screen)?.let { action } }
    val startAdding: (() -> Unit)? = listAction?.let { action ->
        {
            if (nativeList != null) instance.openNative(JsonObject(emptyMap()), RecordAction(action.name), action)
            else adding = true
        }
    }

    // P6: a board on a wide window when the records are on it; a list with tabs of its values on a phone.
    val board = boardOf(context, method)
    val onBoard = board?.let { (b, _) -> state.items.filter { enumKey(it[b.field]) in b.order } }.orEmpty()
    val boardShown = board != null && !compact && onBoard.isNotEmpty()
    var boardTab by remember(frame.method, board?.first) { mutableStateOf<String?>(null) }

    Page(width = if (boardShown) cx.m42.superizer.kit.PageWidth.Wide else cx.m42.superizer.kit.PageWidth.Default, modifier = Modifier.testTag("$tag:list")) {
        if (!bottom) BackLine(instance, context)
        // A tab of a record («События» of a service): the card's name and address over it, then its tabs.
        val card = frame.card
        val cardItem = frame.cardMethod?.let { context.app.method(it) }?.ui?.item
        Header(
            title = card?.let { c -> cardItem?.title?.let { plain(c[it]) } } ?: if (bottom) context.app.title else instance.titleAt(context.app, frames, frames.lastIndex) ?: method.title,
            bar = barTitle(instance, context),
            description = card?.let { c -> cardItem?.subtitle?.let { plain(c[it]) } } ?: state.description,
            badge = card?.let { c ->
                val model = frame.cardMethod?.let { context.app.method(it) }?.model
                context.show(c, model, cardItem?.badge)?.let { b -> { Badge(b.text, b.tone) } }
            },
            crumbs = crumbs,
            // Inside the shell the point is chosen in its bar, for every app at once (_plan/07, item 7).
            meta = if (context.app.byPlace && bottom && !hostDrawsBack) ({ PlaceMeta(instance, context, choosable = true) }) else null,
            actions = if (listAction == null) null else ({
                Button(
                    text = listAction.title,
                    onClick = { startAdding?.invoke() },
                    leadingIcon = Lucide.Plus,
                    modifier = Modifier.testTag("$tag:add"),
                )
            }),
        )
        if (card != null) RecordTabsBar(instance, context, frame)
        TopNotices(instance, context)
        // The form of the list's action is a dialog over the list — a sheet on a phone — as the prototype's.
        if (listAction != null && adding && nativeList == null) {
            ListActionDialog(instance, context, listAction, busy = "list:${listAction.name}" in busy, onClose = { adding = false })
        }
        FilterRow(instance, context, method, state)

        val item = ui.item ?: ItemUi()
        when {
            !state.loaded && state.loading -> SkeletonRows("$tag:loading")
            state.loaded && state.items.isEmpty() -> if (state.query.isNotBlank()) {
                EmptyBlock(Lucide.SearchX, words.nothingFound, words.nothingFoundHint, "$tag:empty", words.resetSearch, { instance.search("") })
            } else {
                EmptyBlock(Lucide.Inbox, ui.empty ?: words.nothingFound, words.emptyHint, "$tag:empty", listAction?.title, startAdding, primary = true)
            }
        }
        if (board != null && compact && state.items.isNotEmpty()) {
            BoardTabs(context, board.first, board.second, state.items, boardTab) { boardTab = it }
        }
        val listed = when {
            board == null || !compact -> state.items
            boardTab == null -> state.items
            else -> state.items.filter { enumKey(it[board.first.field]) == boardTab }
        }
        if (boardShown && board != null) {
            val (b, field) = board
            val cards = onBoard.mapNotNull { record -> rowOf(context, method, item, record)?.let { record to it } }
                .groupBy({ (record, _) -> enumKey(record[b.field]).orEmpty() }) { (_, row) ->
                    BoardCard(
                        tag = row.tag,
                        title = row.title,
                        subtitle = row.subtitle,
                        meta = row.meta,
                        value = row.value,
                        onClick = if (row.opens) ({ instance.openRow(row.record) }) else null,
                        detail = row.detail,
                        note = row.note,
                    )
                }
            BoardView(tag, field, b.order, cards)
        }
        // One panel per group when the list is grouped, its name as the panel's title.
        val groups = (if (boardShown) emptyList() else listed).fold(mutableListOf<Pair<String?, MutableList<JsonObject>>>()) { acc, record ->
            val groupTitle = ui.groupBy?.let { plain(record[it]) }
            if (acc.isEmpty() || acc.last().first != groupTitle) acc += groupTitle to mutableListOf(record)
            else acc.last().second += record
            acc
        }
        val rows = groups.map { (title, records) -> title to records.mapNotNull { rowOf(context, method, item, it) } }
        if (compact && board != null) {
            // The records of a board on a phone are its cards, one under another, each with its column's stripe.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.flatMap { it.second }.forEach { row ->
                    BoardCardView(
                        BoardCard(
                            tag = row.tag,
                            title = row.title,
                            subtitle = row.subtitle,
                            meta = row.meta,
                            value = row.value,
                            onClick = if (row.opens) ({ instance.openRow(row.record) }) else null,
                            detail = row.detail,
                            note = row.note,
                            state = enumKey(row.record[board.first.field])?.let { boardTitle(board.second, it) },
                        ),
                        toneKit(boardTone(board.second, enumKey(row.record[board.first.field]).orEmpty())),
                    )
                }
            }
        } else if (item.series != null && LocalWindowClass.current != WindowClass.Expanded && rows.any { it.second.isNotEmpty() }) {
            // P8 on a phone and a tablet: the prototype's cards — who it is, the shape of its load, its state and price.
            rows.forEach { (groupTitle, models) ->
                if (groupTitle != null) cx.m42.superizer.kit.SectionTitle(groupTitle)
                cx.m42.superizer.kit.Grid(columns = if (compact) 1 else 2, gap = if (compact) 12.dp else 16.dp) {
                    models.forEach { row -> SeriesCard(instance, context, row, busy) }
                }
            }
        } else if (compact) {
            rows.forEach { (groupTitle, models) ->
                Panel(title = groupTitle, flush = true) {
                    models.forEachIndexed { index, row ->
                        if (index > 0) Hairline()
                        CompactRow(instance, context, row, busy)
                    }
                }
            }
        } else {
            val (columns, headed) = tableColumns(instance, context, method, item, rows.flatMap { it.second }, busy)
            rows.forEach { (groupTitle, models) ->
                Panel(title = groupTitle, flush = true) {
                    RecordTable(
                        rows = models,
                        columns = columns,
                        tag = { it.tag },
                        onRow = { if (it.opens) instance.openRow(it.record) },
                        opens = { it.opens },
                        headed = headed,
                    )
                }
            }
        }
        if (state.nextCursor != null) {
            Button(
                text = if (state.loadingMore) words.loading else words.more,
                onClick = instance::more,
                enabled = !state.loadingMore,
                loading = state.loadingMore,
                variant = ButtonVariant.Outline,
                modifier = (if (compact) Modifier.fillMaxWidth() else Modifier.align(Alignment.CenterHorizontally)).testTag("$tag:more"),
            )
        }
        val total = state.total
        if (total != null && total > state.items.size) {
            Text(
                text = words.shownOf(state.items.size, total),
                variant = TextVariant.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("$tag:shown"),
            )
        }
    }
}

/**
 * The form of an action of the list («+ Новый клиент») in the kit's dialog: its fields, «Отмена» and the
 * action's own button in the footer. Closing it with something typed asks first, as leaving a page does.
 */
@Composable
private fun ListActionDialog(instance: ServerAppInstance, context: RenderContext, action: DeclaredMethod, busy: Boolean, onClose: () -> Unit) {
    val tag = "${context.tag}:form"
    val form = remember(action.name) { FormState(formFields(action), tag) }
    val cancel = { instance.leave(onClose) }
    fun send(saved: () -> Unit) = instance.actOnList(action, JsonObject(form.collect())) {
        onClose()
        saved()
    }
    HoldUnsaved(form.dirty, instance::hold) { saved -> if (form.filled && !busy) send(saved) }
    SystemBackHandler(enabled = true, onBack = cancel)
    Dialog(
        onDismiss = cancel,
        title = action.title,
        width = 520.dp,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FormButtons(form, action.title, busy, context.words, onSubmit = { send {} }, onCancel = cancel, fill = compact)
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FormFields(form, busy, context.words)
        }
    }
}

/** The tabs of a board on a phone (P6): «Все» and a chip per value of its order, with the count of each and its tone's dot. */
@Composable
private fun BoardTabs(
    context: RenderContext,
    board: cx.m42.restoapp.protocol.Board,
    field: Field,
    items: List<JsonObject>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val tag = "${context.tag}:board-tab"
    val counts = items.groupingBy { enumKey(it[board.field]) }.eachCount()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("${context.tag}:board-tabs"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cx.m42.superizer.kit.Chip(
            text = context.words.all,
            selected = selected == null,
            onClick = { onSelect(null) },
            count = items.size,
            modifier = Modifier.testTag("$tag-all"),
        )
        board.order.forEach { value ->
            cx.m42.superizer.kit.Chip(
                text = boardTitle(field, value),
                selected = selected == value,
                onClick = { onSelect(value) },
                count = counts[value] ?: 0,
                dot = toneKit(boardTone(field, value)),
                modifier = Modifier.testTag("$tag-$value"),
            )
        }
    }
}

/** The search and the chips of a list, always in view: one line on a wide window, two on a phone. */
@Composable
private fun FilterRow(instance: ServerAppInstance, context: RenderContext, method: DeclaredMethod, state: ListState) {
    val ui = method.ui ?: return
    val hasChips = ui.chips?.let { method.input.property(it) } != null
    if (ui.search == null && !hasChips) return
    val tag = context.tag
    val search: @Composable (Modifier) -> Unit = { modifier ->
        Input(
            value = state.query,
            onValueChange = instance::search,
            placeholder = context.words.search,
            leadingIcon = Lucide.Search,
            modifier = modifier.testTag("$tag:search"),
        )
    }
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.search != null) search(Modifier)
            FrameChips(instance, context, method, state.counts)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.search != null) search(Modifier.width(320.dp))
            Box(Modifier.weight(1f)) { FrameChips(instance, context, method, state.counts) }
        }
    }
}

/** The chips of the frame's method: its `enum` parameter, its titles, and the counts the answer brought. */
@Composable
private fun FrameChips(instance: ServerAppInstance, context: RenderContext, method: DeclaredMethod, counts: Map<String, Int>) {
    val chips = method.ui?.chips ?: return
    val schema = method.input.property(chips) ?: return
    ChipsRow(
        values = schema.enumValues(),
        titles = schema.titles(),
        counts = counts,
        selected = instance.currentChip(),
        onSelect = instance::chip,
        tag = "${context.tag}:chip",
    )
}

/** One record of a list as the screen shows it, whatever the window: what is in its row, and what it does. */
private class RowModel(
    val record: JsonObject,
    val item: ItemUi,
    val id: String,
    val tag: String,
    val title: String,
    val subtitle: String?,
    val meta: String?,
    val value: Shown?,
    val badge: Shown?,
    val opens: Boolean,
    val controls: List<Pair<RecordAction, DeclaredMethod>>,
    /** The row's buttons (`buttons` placed on `row`): behind its `⋯`, a row has no room for them. */
    val menu: List<Pair<RecordAction, DeclaredMethod>>,
    /** True when a stepper in the row draws the value between its − and +. */
    val stepped: Boolean,
    val detail: String? = null,
    val note: String? = null,
    /** P8: the numbers of the record's `item.series` field — a sparkline in its card and its row; null without the key. */
    val series: List<Float>? = null,
)

/** P8: the array of numbers in [field] of [record]; anything that is not a number in it is dropped. */
internal fun seriesValues(record: JsonObject, field: String?): List<Float>? {
    val array = field?.let { record[it] } as? kotlinx.serialization.json.JsonArray ?: return null
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toFloatOrNull() }
}

private fun rowOf(context: RenderContext, method: DeclaredMethod, item: ItemUi, record: JsonObject): RowModel? {
    val id = record.id() ?: return null
    val model = method.model
    val controls = context.actions(record, "row").filter { it.second.ui?.control?.type in setOf(Controls.STEPPER, Controls.TOGGLE) }
    val stepper = controls.firstOrNull { it.second.ui?.control?.type == Controls.STEPPER }
    val menu = context.actions(record, "row").filter { it.second.ui?.control?.type == Controls.BUTTONS }
    return RowModel(
        record = record,
        item = item,
        id = id,
        tag = "${context.tag}:row-$id",
        title = item.title?.let { plain(record[it]) } ?: id,
        // Under a group's heading its name would only repeat it.
        subtitle = item.subtitle?.takeIf { it != method.ui?.groupBy }?.let { context.show(record, model, it)?.text },
        meta = context.show(record, model, item.meta)?.text,
        value = context.show(record, model, item.value),
        badge = context.show(record, model, item.badge),
        // The row's own link beats the default one of the list (`client-lookup.plan.md`): a mixed list leads
        // each of its rows where that row belongs.
        opens = record.open() != null || method.ui?.open?.let { context.app.method(it) } != null,
        controls = controls,
        menu = menu,
        stepped = stepper != null && stepBase(record, stepper, item) != null,
        detail = item.detail?.let { context.show(record, model, it)?.text },
        note = item.note?.let { context.show(record, model, it)?.text },
        series = seriesValues(record, item.series),
    )
}

/**
 * A row on a phone: title and state, a line under it, the value at the end. A row with controls takes two
 * lines, as the prototype's stop-list does — the switch beside the title, the state and the stepper under
 * it — so a tap on the row's middle opens it rather than hitting a «−».
 */
@Composable
private fun CompactRow(instance: ServerAppInstance, context: RenderContext, row: RowModel, busy: Set<String>) {
    if (row.controls.isNotEmpty()) {
        val stepper = row.controls.any { it.second.ui?.control?.type == Controls.STEPPER } && row.stepped
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (row.opens) Modifier.kitClickable { instance.openRow(row.record) } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(row.tag),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.title, Modifier.weight(1f, fill = false), style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!stepper) row.badge?.let { Badge(it.text, it.tone, Modifier.testTag("${row.tag}:badge")) }
                    }
                    val second = listOfNotNull(row.subtitle, row.meta).filter { it.isNotBlank() }.joinToString(" · ")
                    if (second.isNotEmpty()) Text(second, variant = TextVariant.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!stepper && !row.stepped) row.value?.let { ValueText(it.text, it.tone, Modifier.padding(start = 12.dp).testTag("${row.tag}:value")) }
                RowControls(instance, context, row, busy, Controls.TOGGLE)
                RowMenu(instance, context, row, busy)
            }
            if (stepper) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { row.badge?.let { Badge(it.text, it.tone, Modifier.testTag("${row.tag}:badge")) } }
                    RowControls(instance, context, row, busy, Controls.STEPPER)
                }
            }
        }
        return
    }
    ListRow(
        title = row.title,
        subtitle = row.subtitle,
        meta = row.meta,
        value = if (row.stepped) null else row.value?.text,
        valueTone = row.value?.tone,
        badge = row.badge?.text,
        badgeTone = row.badge?.tone,
        tag = row.tag,
        onClick = if (row.opens) ({ instance.openRow(row.record) }) else null,
        chevron = false,
    ) {
        RowControls(instance, context, row, busy)
        RowMenu(instance, context, row, busy)
    }
}

/**
 * A record of a list with `item.series` (P8) as the prototype's service card: its mark, title and line, the
 * sparkline of its numbers (a dashed baseline when it has none), then its state, its meta and its value.
 */
@Composable
private fun SeriesCard(instance: ServerAppInstance, context: RenderContext, row: RowModel, busy: Set<String>) {
    val muted = row.badge?.tone == Tones.MUTED
    Panel(
        modifier = Modifier.testTag(row.tag),
        onClick = if (row.opens) ({ instance.openRow(row.record) }) else null,
        gap = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cx.m42.superizer.kit.NameMark(row.title, 40.dp, dimmed = muted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(row.title, style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            RowMenu(instance, context, row, busy)
        }
        cx.m42.superizer.kit.Sparkline(
            row.series.orEmpty(),
            Modifier.fillMaxWidth().height(40.dp).testTag("${row.tag}:series"),
            color = if (row.series.isNullOrEmpty()) Kit.colors.mutedForeground else Kit.palette.brand,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.badge?.let { Badge(it.text, it.tone, Modifier.testTag("${row.tag}:badge")) }
            row.meta?.takeIf { it.isNotBlank() }?.let { Pill(it, Tone.Neutral) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            row.value?.let { ValueText(it.text, it.tone, Modifier.testTag("${row.tag}:value")) }
        }
    }
}

/** The columns of a list on a wide window, from what its rows show: the name, the state, the line, the value, the controls. */
@Composable
private fun tableColumns(
    instance: ServerAppInstance,
    context: RenderContext,
    method: DeclaredMethod,
    item: ItemUi,
    rows: List<RowModel>,
    busy: Set<String>,
): Pair<List<RecordColumn<RowModel>>, Boolean> {
    val model = method.model
    val words = context.words
    // A column is never headed by nothing: the field's title, or — where the server gave the field none —
    // what the column holds. The first one is the list's noun, the title of the record the rows open.
    fun header(name: String?, fallback: String) = context.field(model, name)?.title?.takeIf { it.isNotBlank() } ?: fallback
    fun declared(name: String?) = !context.field(model, name)?.title.isNullOrBlank()
    val noun = method.ui?.open?.let { context.app.method(it)?.title }?.takeIf { it.isNotBlank() && it.length <= 24 } ?: words.name
    val columns = mutableListOf<RecordColumn<RowModel>>()
    columns += RecordColumn(header(item.title, noun), weight = 2.4f) { row ->
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.title, style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    // P1: the server's columns after the title — those its model declares, in its order. None of them
    // declared: the columns of `item`, as without the key.
    val declaredColumns = method.ui?.columns.orEmpty()
        .filter { it.field != item.title && context.field(model, it.field) != null }
        .distinctBy { it.field }
    if (declaredColumns.isNotEmpty()) {
        declaredColumns.forEach { column ->
            val field = context.field(model, column.field)!!
            val numeric = field.format in setOf(Formats.MONEY, Formats.CREDITS, Formats.NUMBER, Formats.PERCENT)
            val end = when (column.align) {
                cx.m42.restoapp.protocol.Column.Align.END -> true
                cx.m42.restoapp.protocol.Column.Align.START -> false
                else -> numeric
            }
            val weight = when (column.width) {
                cx.m42.restoapp.protocol.Column.Width.NARROW -> 0.8f
                cx.m42.restoapp.protocol.Column.Width.WIDE -> 2.2f
                else -> 1.2f
            }
            // A stepped value is the stepper's: the controls' column draws it between − and +.
            val stepped = column.field == item.value
            // A badge is never cut: its column is as wide as its longest pill, the rest goes to the others.
            val badges = if (field.format == Formats.ENUM) rows.mapNotNull { row -> context.show(row.record, model, column.field)?.takeIf { it.tone != null || column.field == item.badge }?.text } else emptyList()
            val fixed = if (badges.isNotEmpty()) badgeColumnWidth(badges, field.title.ifBlank { column.field }) else null
            columns += RecordColumn(field.title.ifBlank { column.field }, weight = weight, width = fixed, alignEnd = end) { row ->
                if (stepped && row.stepped) return@RecordColumn
                val shown = context.show(row.record, model, column.field) ?: return@RecordColumn
                val cellTag = Modifier.testTag("${row.tag}:col-${column.field}")
                when {
                    field.format == Formats.ENUM && (shown.tone != null || column.field == item.badge) -> Badge(shown.text, shown.tone, cellTag)
                    numeric -> ValueText(shown.text, shown.tone, cellTag)
                    else -> Text(
                        shown.text,
                        cellTag,
                        variant = TextVariant.P,
                        color = toneOf(shown.tone) ?: androidx.compose.ui.graphics.Color.Unspecified,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (declaredColumns.isEmpty() && rows.any { it.badge != null }) {
        val title = header(item.badge, words.status)
        columns += RecordColumn(title, width = badgeColumnWidth(rows.mapNotNull { it.badge?.text }, title)) { row ->
            row.badge?.let { Badge(it.text, it.tone, Modifier.testTag("${row.tag}:badge")) }
        }
    }
    if (declaredColumns.isEmpty() && rows.any { !it.meta.isNullOrBlank() }) {
        columns += RecordColumn(header(item.meta, words.info), weight = 1.5f) { row ->
            row.meta?.let { Text(it, variant = TextVariant.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    if (declaredColumns.isEmpty() && rows.any { it.value != null && !it.stepped }) {
        columns += RecordColumn(header(item.value, words.value), weight = 1.1f, alignEnd = true) { row ->
            if (!row.stepped) row.value?.let { ValueText(it.text, it.tone, Modifier.testTag("${row.tag}:value")) }
        }
    }
    // P8: the shape of the record's numbers, after its columns, in the tone of its state.
    if (item.series != null && rows.any { it.series != null }) {
        columns += RecordColumn(header(item.series, words.trend), weight = 1.2f) { row ->
            val values = row.series
            if (values.isNullOrEmpty()) Text("—", variant = TextVariant.Muted)
            else cx.m42.superizer.kit.Sparkline(
                values, Modifier.width(110.dp).height(26.dp).testTag("${row.tag}:series"),
                color = row.badge?.tone?.let { toneKit(it).color() } ?: Kit.palette.brand,
            )
        }
    }
    val controlled = rows.filter { it.controls.isNotEmpty() }
    if (controlled.isNotEmpty()) {
        // The controls' own names: «Изменить остаток · Продаётся на точке».
        val names = controlled.flatMap { row -> row.controls.map { it.second.title } }.distinct().filter { it.isNotBlank() }
        columns += RecordColumn(names.joinToString(" · ").ifEmpty { words.actions }, weight = 1.4f, alignEnd = true) { row ->
            Row(verticalAlignment = Alignment.CenterVertically) { RowControls(instance, context, row, busy) }
        }
    }
    if (rows.any { it.menu.isNotEmpty() }) {
        // `⋯` needs no words over it, but a column without a head reads as a gap: a narrow one, said for a reader.
        columns += RecordColumn("", width = 56.dp, alignEnd = true) { row ->
            Row(verticalAlignment = Alignment.CenterVertically) { RowMenu(instance, context, row, busy) }
        }
    }
    // A list whose columns besides the name the server titled none of — a stop-list under its groups — has
    // no head: the rows say all a head would.
    val headed = declaredColumns.isNotEmpty() || listOf(item.badge, item.meta, item.value).any(::declared)
    return columns to headed
}

/**
 * The width of a column of [Badge]s: the widest pill of [texts] (or the column's [title]), its dot and padding,
 * and the cell's own padding — measured in the badge's style, so a long state («Запланирован») is not cut.
 */
@Composable
private fun badgeColumnWidth(texts: List<String>, title: String): androidx.compose.ui.unit.Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pill = Kit.textStyles.caption.copy(fontWeight = FontWeight.Medium)
    val head = Kit.textStyles.caption.copy(fontWeight = FontWeight.Medium)
    val widest = with(density) {
        maxOf(
            texts.distinct().maxOfOrNull { measurer.measure(it, pill, maxLines = 1).size.width.toDp() + 27.dp } ?: 0.dp,
            measurer.measure(title, head, maxLines = 1).size.width.toDp(),
        )
    }
    return widest + 20.dp + 2.dp
}

/** The stepper and the switch of a row; buttons and forms need room and live on the record's page. */
@Composable
private fun RowScope.RowControls(instance: ServerAppInstance, context: RenderContext, row: RowModel, busy: Set<String>, only: String? = null) {
    val item = row.item
    row.controls.forEach { (action, method) ->
        val control = method.ui?.control ?: return@forEach
        if (only != null && control.type != only) return@forEach
        val waiting = "${row.id}:${method.name}" in busy
        when (control.type) {
            Controls.STEPPER -> stepBase(row.record, action to method, item)?.let { base ->
                val range = stepRange(action, method)
                StepperRow(
                    display = row.value?.text ?: base.toString(),
                    tone = row.value?.tone,
                    onMinus = (base - 1).takeIf { it in range }?.let { next -> { instance.act(row.record, action, method, JsonPrimitive(next)) } },
                    onPlus = (base + 1).takeIf { it in range }?.let { next -> { instance.act(row.record, action, method, JsonPrimitive(next)) } },
                    enabled = !waiting,
                    tag = "${row.tag}:${method.name}",
                )
            }
            Controls.TOGGLE -> {
                val checked = (row.record[control.bind ?: ""] as? JsonPrimitive)?.booleanOrNull ?: false
                SwitchControl(checked, method.title, enabled = !waiting, onChange = { on ->
                    instance.act(row.record, action, method, JsonPrimitive(on))
                }, tag = "${row.tag}:${method.name}")
            }
            else -> Unit
        }
    }
}

/**
 * The row's buttons behind its `⋯` (`<row>:more`): the kit's menu, one line per allowed value
 * (`<row>:<method>-<value>`), the dangerous ones in red and asked about first, as on the record's page.
 */
@Composable
private fun RowMenu(instance: ServerAppInstance, context: RenderContext, row: RowModel, busy: Set<String>) {
    if (row.menu.isEmpty()) return
    var open by remember(row.id) { mutableStateOf(false) }
    var asking by remember(row.id) { mutableStateOf<Triple<RecordAction, DeclaredMethod, String>?>(null) }
    val waiting = row.menu.any { "${row.id}:${it.second.name}" in busy }
    Box {
        IconButton(
            Lucide.Ellipsis,
            contentDescription = context.words.moreActions,
            onClick = { open = true },
            enabled = !waiting,
            size = ButtonSize.Sm,
            modifier = Modifier.padding(start = 4.dp).testTag("${row.tag}:more"),
        )
        Menu(open, { open = false }, width = 240.dp) {
            row.menu.forEachIndexed { index, (action, method) ->
                val control = method.ui?.control ?: return@forEachIndexed
                val schema = (action.input ?: method.input).property(control.param ?: "") ?: return@forEachIndexed
                val titles = schema.titles()
                if (index > 0) MenuSeparator()
                if (row.menu.size > 1) MenuLabel(method.title)
                schema.enumValues().sortedBy { if (control.tones[it] == Tones.DANGER) 1 else 0 }.forEach { value ->
                    val danger = control.tones[value] == Tones.DANGER
                    Box(Modifier.testTag("${row.tag}:${method.name}-$value")) {
                        MenuItem(
                            text = titles[value] ?: value,
                            onClick = {
                                open = false
                                if (danger && method.ui?.confirm != null) asking = Triple(action, method, value)
                                else instance.act(row.record, action, method, JsonPrimitive(value))
                            },
                            destructive = danger,
                        )
                    }
                }
            }
        }
    }
    asking?.let { (action, method, value) ->
        val schema = (action.input ?: method.input).property(method.ui?.control?.param ?: "")
        AskDialog(
            question = method.ui?.confirm.orEmpty(),
            confirm = schema?.titles()?.get(value) ?: value,
            cancel = context.words.cancel,
            destructive = true,
            onConfirm = {
                asking = null
                instance.act(row.record, action, method, JsonPrimitive(value))
            },
            onDismiss = { asking = null },
            tag = "${row.tag}:${method.name}-$value:confirm",
            answers = "${row.tag}:${method.name}-$value",
        )
    }
}

/**
 * Where − and + start from: the bound field when it holds an ordinary number, else the row's value
 * when that does — a manual balance not set yet steps from what is available. Null: nothing to step
 * (unlimited); the card sets such a value.
 */
private fun stepBase(record: JsonObject, stepper: Pair<RecordAction, DeclaredMethod>, item: ItemUi): Int? {
    val control = stepper.second.ui?.control ?: return null
    val range = stepRange(stepper.first, stepper.second)
    val bound = (record[control.bind ?: ""] as? JsonPrimitive)?.intOrNull
    val shown = (record[item.value ?: ""] as? JsonPrimitive)?.intOrNull
    return bound?.takeIf { it in range } ?: shown?.takeIf { it in range }
}

/** The numbers − and + may reach: the schema's range, but never below zero — specials there are set by name. */
private fun stepRange(action: RecordAction, method: DeclaredMethod): IntRange {
    val param = method.ui?.control?.param ?: return IntRange.EMPTY
    val schema = (action.input ?: method.input).property(param)
    val min = (schema?.get("minimum") as? JsonPrimitive)?.intOrNull ?: 0
    val max = (schema?.get("maximum") as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
    return maxOf(min, 0)..max
}

// ───────────────────────────── a record ─────────────────────────────

/** A form of a record open for editing: which action, and what is typed into it. */
private class Editing(val action: RecordAction, val method: DeclaredMethod, val form: FormState)

/**
 * The frame of a record: its page, and — on a phone, while a form of it is open — the kit's `ActionBar`
 * under the page with the form's «Отмена» and «Сохранить», where a thumb is. Wider, the two are in the header.
 */
@Composable
private fun RecordFrame(
    instance: ServerAppInstance,
    context: RenderContext,
    record: JsonObject?,
    page: @Composable ColumnScope.(MutableState<Editing?>) -> Unit,
) {
    val editing = remember(record?.id()) { mutableStateOf<Editing?>(null) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Page(modifier = Modifier.testTag("${context.tag}:detail")) { page(editing) }
        }
        val open = editing.value
        if (compact && open != null && record != null) {
            ActionBar { EditButtons(instance, context, record, open, editing, fill = true) }
        }
    }
}

/** «Отмена» and «Сохранить» of the record's open form. */
@Composable
private fun EditButtons(instance: ServerAppInstance, context: RenderContext, record: JsonObject, open: Editing, editing: MutableState<Editing?>, fill: Boolean) {
    val busy by instance.busy.collectAsState()
    val waiting = "${record.id()}:${open.method.name}" in busy
    FormButtons(
        form = open.form,
        submit = context.words.save,
        busy = waiting,
        words = context.words,
        onSubmit = { submitEdit(instance, record, open, editing) {} },
        onCancel = { instance.leave { editing.value = null } },
        fill = fill,
    )
}

/** Sends the record's form: its id, when the method takes one, and what was typed. */
private fun submitEdit(instance: ServerAppInstance, record: JsonObject, open: Editing, editing: MutableState<Editing?>, saved: () -> Unit) {
    val input = open.action.input ?: open.method.input
    val params = buildJsonObject {
        if (input.property("id") != null) record.id()?.let { put("id", JsonPrimitive(it)) }
        open.form.collect().forEach { (key, value) -> put(key, value) }
    }
    instance.actWith(record, open.action, open.method, params) {
        editing.value = null
        saved()
    }
}

/** A record opened from a list: its header, its actions, its fields by section, and where it leads next. */
@Composable
private fun DetailScreen(instance: ServerAppInstance, context: RenderContext, frame: Frame, crumbs: List<Crumb>) {
    val method = context.app.method(frame.method)
    val record = frame.record
    val frames by instance.frames.collectAsState()
    RecordFrame(instance, context, record) { editing ->
        BackLine(instance, context)
        if (record == null) {
            Header(title = instance.titleAt(context.app, frames, frames.lastIndex).orEmpty(), bar = barTitle(instance, context), crumbs = crumbs)
            TopNotices(instance, context)
            Skeleton(Modifier.fillMaxWidth().height(220.dp), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            return@RecordFrame
        }
        // P14: a record about to change (a service starting) is read again while it is on screen.
        val refresh = (record["_refresh"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 2..300 }
        if (refresh != null && !frame.loading) {
            // Keyed by the record's id, not its content: an answer equal to the last one keeps the loop going.
            LaunchedEffect(record.id(), refresh) {
                while (true) {
                    delay(refresh * 1000L)
                    instance.quietReload()
                }
            }
        }
        // `frame.loading`: the card is drawn from the row of the list while the full record is being
        // read. A screen of the client's own is built from that record — the editor of a setting from
        // the JSON Schema in it — so it must not open on half of one.
        // A tab of a record («Логи», «Расходы»): the card's header over it, its own record under the tabs.
        val cardMethod = frame.cardMethod?.let { context.app.method(it) }
        val head = frame.card?.let { card -> TabHead(card, cardMethod?.model, cardMethod?.ui?.item ?: ItemUi()) }
        RecordPage(
            instance, context, method?.model, method?.ui?.item ?: ItemUi(), record, crumbs, settled = !frame.loading, home = null, editing = editing,
            summary = method?.ui?.summary.orEmpty(),
            head = head,
            tabs = { RecordTabsBar(instance, context, frame) },
            onlyAction = frame.tab,
        )
    }
}

/** The header of a record's tab: the card's record, not the tab's own («Логи»). */
internal class TabHead(val record: JsonObject, val model: String?, val item: ItemUi)

/** The tabs of the record on top, under its header — the prototype's «Обзор | События | Логи | Расходы | Настройки». */
@Composable
private fun RecordTabsBar(instance: ServerAppInstance, context: RenderContext, frame: Frame) {
    val cardMethod = frame.cardMethod ?: frame.method
    val tabs = remember(context.app, cardMethod) { recordTabsOf(context.app, cardMethod) }
    if (tabs.isEmpty()) return
    val selected = frame.tab ?: frame.method
    cx.m42.superizer.kit.LineTabs(
        items = tabs.map { it.ui?.section.orEmpty() },
        selected = tabs.indexOfFirst { it.name == selected }.coerceAtLeast(0),
        onSelect = { index -> instance.openTab(tabs[index].name) },
        modifier = Modifier.testTag("${context.tag}:tabs"),
    )
}

/** A home method that answers one record — the summary of «Маркетинг»: its chips, its card, its links. */
@Composable
private fun HomeRecordScreen(instance: ServerAppInstance, context: RenderContext, frame: Frame) {
    val method = context.app.method(frame.method) ?: return
    val record = frame.record
    RecordFrame(instance, context, record) { editing ->
        if (record == null) {
            Header(title = context.app.title, bar = barTitle(instance, context), meta = if (context.app.byPlace) ({ PlaceMeta(instance, context, choosable = true) }) else null)
            TopNotices(instance, context)
            FrameChips(instance, context, method, emptyMap())
            return@RecordFrame
        }
        RecordPage(instance, context, method.model, method.ui?.item ?: ItemUi(), record, emptyList(), settled = !frame.loading, home = method, editing = editing, summary = method.ui?.summary.orEmpty())
    }
}

/**
 * A record as a page: the header (its name, its state, its one number, its main actions), and under it
 * the body — on a wide window in two columns, the main one and a rail with its dates and its links.
 */
@Composable
private fun ColumnScope.RecordPage(
    instance: ServerAppInstance,
    context: RenderContext,
    model: String?,
    item: ItemUi,
    record: JsonObject,
    crumbs: List<Crumb>,
    /** False while this record is still being re-read: an action that reads the record waits for it. */
    settled: Boolean,
    /** The home method, when the record is the app's own screen: its chips go under the header, its point in it. */
    home: DeclaredMethod?,
    /** The record's form open for editing, if any: its buttons take the header's place wider than a phone. */
    editing: MutableState<Editing?>,
    /** P7: the blocks over the card; empty — the card as it is. */
    summary: List<cx.m42.restoapp.protocol.SummaryBlock> = emptyList(),
    /** A tab of a record: the card's header instead of this record's own, and no actions in it. */
    head: TabHead? = null,
    /** The record's tabs, drawn under the header. */
    tabs: (@Composable () -> Unit)? = null,
    /** «Настройки» as a tab: only this action's form, nothing else of the card. */
    onlyAction: String? = null,
) {
    val busy by instance.busy.collectAsState()
    val id = record.id().orEmpty()
    val tag = context.tag
    val window = LocalWindowClass.current
    // The record's tabs take the actions that are tabs themselves («Настройки») out of the card.
    val tabNames = remember(context.app, record) {
        recordTabsOf(context.app, context.app.methods.firstOrNull { it.model == model && it.ui?.view == Views.DETAIL && !it.ui?.section.isNullOrBlank() }?.name ?: "")
            .map { it.name }.toSet()
    }
    val actions = context.actions(record, "detail").let { all ->
        when {
            head != null -> emptyList()
            onlyAction != null -> all.filter { (_, method) -> method.name == onlyAction }
            else -> all.filter { (_, method) -> method.name !in tabNames || method.kind == DeclaredMethod.KIND_QUERY }
        }
    }
    // P3: the record's main action — the first one marked; null when none is, and every action looks as before.
    val primary = actions.firstOrNull { it.second.ui?.primary == true }?.second?.name
    // P9: «Открыть управление» — a server of the account the host can open now is the record's main button, and
    // the record's own actions step back; one it cannot (not running) is not drawn at all.
    val allLinks = if (head != null || onlyAction != null) emptyList() else record.links()
    val serverLinks = allLinks.filter { link -> link.open.server?.let { instance.server.canOpenServer(it) } == true }
    fun emphasis(method: DeclaredMethod): Boolean? = if (serverLinks.isNotEmpty()) false else primary?.let { it == method.name }
    // Wider than a phone the buttons of a record's flow and its own screens sit in the header, the way an
    // issue's buttons do on GitHub; on a phone they are a block of their own under it, where a thumb is.
    val inHeader = window != WindowClass.Compact
    // On a phone the record's main flow is right under its title (the prototype's order): «Готовить» and «⋯».
    val phoneFlow = if (inHeader) null else actions.firstOrNull { (_, method) ->
        method.ui?.control?.type == Controls.BUTTONS && (primary == null || primary == method.name)
    }
    val headerActions = actions.filter { (_, method) ->
        when (method.ui?.control?.type) {
            Controls.BUTTONS -> inHeader || (phoneFlow != null && method.name == phoneFlow.second.name)
            Controls.NATIVE -> inHeader && NativeActions.of(method.ui?.control?.screen) != null
            else -> false
        }
    }

    // «Настройки» as a tab: its form is the whole page, so it is open from the start (and again after a save).
    val tabForm = if (onlyAction == null) null else actions.firstOrNull { (_, m) -> m.name == onlyAction && m.ui?.control?.type == Controls.FORM }
    LaunchedEffect(tabForm?.second?.name, editing.value == null, settled) {
        val (action, method) = tabForm ?: return@LaunchedEffect
        if (editing.value == null && settled) {
            editing.value = Editing(action, method, FormState(formFields(method, action.input ?: method.input), "$tag:${method.name}:form"))
        }
    }
    val headRecord = head?.record ?: record
    val headModel = head?.model ?: model
    val headItem = head?.item ?: item
    val subtitle = context.show(headRecord, headModel, headItem.subtitle)
    val badge = context.show(headRecord, headModel, headItem.badge)
    val value = if (head != null) null else context.show(record, model, item.value)
    val open = editing.value
    Box(Modifier.testTag("$tag:title")) {
        Header(
            title = headItem.title?.let { plain(headRecord[it]) } ?: id,
            bar = barTitle(instance, context),
            description = subtitle?.text,
            crumbs = crumbs,
            badge = badge?.let { { Badge(it.text, it.tone, Modifier.testTag("$tag:badge")) } },
            meta = if (value != null || (home != null && context.app.byPlace)) ({
                value?.let {
                    // A sum says what it is by its ₽; a bare number («3») needs its field's name over it.
                    val field = context.field(model, item.value)
                    val label = field?.title?.takeIf { t -> t.isNotBlank() && field.format != Formats.MONEY && field.format != Formats.CREDITS }
                    Column {
                        label?.let { l -> Text(l, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1) }
                        Text(
                            text = it.text,
                            style = Kit.textStyles.metric,
                            color = if (it.tone == null) Kit.colors.foreground else toneKit(it.tone).textColor(),
                            modifier = Modifier.testTag("$tag:value"),
                        )
                    }
                }
                if (home != null) PlaceMeta(instance, context, choosable = true)
            }) else null,
            actions = if (open != null && !compact) ({
                EditButtons(instance, context, record, open, editing, fill = false)
            }) else if (head != null || onlyAction != null || (headerActions.isEmpty() && serverLinks.isEmpty())) null else ({
                headerActions.forEach { (action, method) ->
                    val waiting = "$id:${method.name}" in busy
                    when (method.ui?.control?.type) {
                        Controls.BUTTONS -> FlowButtons(
                            instance, context, record, action, method, waiting, stacked = false, menu = !inHeader, emphasis = emphasis(method),
                            // A phone: «Открыть управление» across the width, the power behind «⋯».
                            lead = if (!inHeader && serverLinks.isNotEmpty()) ({ ServerLinkButton(instance, serverLinks.first(), tag, Modifier.weight(1f)) }) else null,
                        )
                        Controls.NATIVE -> Button(
                            text = method.title,
                            onClick = { instance.openNative(record, action, method) },
                            enabled = !waiting && settled,
                            loading = waiting,
                            variant = if (emphasis(method) == true) ButtonVariant.Default else ButtonVariant.Outline,
                            modifier = Modifier.testTag("$tag:${method.name}"),
                        )
                    }
                }
                // Drawn by the flow's row on a phone when there is one; otherwise here.
                if (inHeader || phoneFlow == null) serverLinks.forEachIndexed { index, link ->
                    ServerLinkButton(instance, link, tag, if (compact) Modifier.weight(1f) else Modifier, index)
                }
            }),
        )
    }
    tabs?.invoke()
    TopNotices(instance, context)
    home?.let { FrameChips(instance, context, it, emptyMap()) }
    // The way of the record along its board — an order's «Заказан → Готовится → В пути → Выполнен».
    if (home == null) context.app.methods.firstNotNullOfOrNull { m -> if (m.model == model) boardOf(context, m) else null }?.let { (board, field) ->
        if (record.containsKey(board.field)) WayPanel(field, board.order, enumKey(record[board.field]), "$tag:way")
    }
    if (summary.isNotEmpty() && onlyAction == null) {
        SummaryView(
            context, model, record, summary,
            onNoticeAction = { action -> instance.noticeAction(record, action.method, action.input) },
            busy = busy.filter { it.startsWith("$id:") }.map { it.removePrefix("$id:") }.toSet(),
        )
    }

    // Fields by section, in the declaration's order; the title is the header already.
    //
    // A field the record does not carry at all is left out: a promotion has either a percentage or a flat
    // amount, and a «—» where the other one would be says something false. A field that is there and empty
    // (`null`) still shows «—» — that is a value nobody has set, which is worth seeing.
    val drawnAbove = summaryFields(summary) + coveredFields(summary, record)
    val shownFields = if (onlyAction != null) emptyList() else context.app.fields(model).filter { it.name != item.title && it.name !in drawnAbove && record.containsKey(it.name) }
    // The lines of the card: not the servers (a button above, or nothing) and not the quiet ones (at the foot).
    val links = allLinks.filter { it.open.server == null && it.quiet != true }
    val quietLinks = allLinks.filter { it.open.server == null && it.quiet == true }
    // The rail of a wide window: when the record happened, and where it leads.
    val twoColumns = window == WindowClass.Expanded
    val railFields = if (twoColumns) shownFields.filter { it.format == Formats.DATETIME || it.format == Formats.RELATIVE } else emptyList()
    val mainFields = shownFields - railFields.toSet()
    val hasRail = railFields.isNotEmpty() || links.isNotEmpty()

    val main: @Composable ColumnScope.() -> Unit = {
        actions.forEach { (action, method) ->
            if (action to method in headerActions) return@forEach
            val waiting = "$id:${method.name}" in busy
            when (method.ui?.control?.type) {
                Controls.NATIVE -> if (NativeActions.of(method.ui?.control?.screen) != null) {
                    Button(
                        text = method.title,
                        onClick = { instance.openNative(record, action, method) },
                        enabled = !waiting && settled,
                        loading = waiting,
                        size = ButtonSize.Lg,
                        variant = if (emphasis(method) == false) ButtonVariant.Outline else ButtonVariant.Default,
                        modifier = Modifier.fillMaxWidth().testTag("$tag:${method.name}"),
                    )
                }
                Controls.STEPPER -> StepperCard(instance, context, model, record, action, method, waiting)
                Controls.TOGGLE -> Panel(flush = true) {
                    val control = method.ui?.control
                    SwitchItem(
                        title = method.title,
                        checked = (record[control?.bind ?: ""] as? JsonPrimitive)?.booleanOrNull ?: false,
                        onCheckedChange = { on -> instance.act(record, action, method, JsonPrimitive(on)) },
                        enabled = !waiting,
                        modifier = Modifier.testTag("$tag:${method.name}"),
                    )
                }
                Controls.BUTTONS -> Panel(title = method.title) {
                    FlowButtons(instance, context, record, action, method, waiting, stacked = true, emphasis = emphasis(method))
                }
                // A form on a record — «Настройки» of a service: a button, and under it the fields of the record's own
                // schema (`_actions` input), which start with the record's values (`default`).
                Controls.FORM -> RecordFormCard(instance, context, record, action, method, waiting, editing)
                // Unknown controls are not drawn (plan section 4).
                else -> Unit
            }
        }
        FieldPanels(context, record, mainFields)
        if (!twoColumns) LinksPanel(instance, context, links)
    }
    if (twoColumns && hasRail) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp), content = main)
            Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                if (railFields.isNotEmpty()) {
                    Panel(title = context.words.details) {
                        railFields.forEach { field -> FieldFact(context, record, field) }
                    }
                }
                LinksPanel(instance, context, links)
            }
        }
    } else {
        main()
    }
    QuietLinks(instance, context, quietLinks)
}

/** P9: «Открыть управление» — the record's server, opened in the app by the host. */
@Composable
private fun ServerLinkButton(instance: ServerAppInstance, link: RecordLink, tag: String, modifier: Modifier, index: Int = 0) {
    Button(
        text = link.title,
        onClick = { instance.openLink(link.open, link.title) },
        leadingIcon = Lucide.LayoutDashboard,
        modifier = modifier.testTag("$tag:link-server" + if (index > 0) "-$index" else ""),
    )
}

/** P9: the by-the-way lines of a card — the web admin of a service: small grey links at its foot, not buttons. */
@Composable
private fun QuietLinks(instance: ServerAppInstance, context: RenderContext, links: List<RecordLink>) {
    if (links.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        links.forEachIndexed { index, link ->
            Row(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .kitClickable(hover = false) { instance.openLink(link.open, link.title) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("${context.tag}:link-quiet-$index"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(link.title, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1)
                if (link.open.url != null) Icon(Lucide.ArrowUpRight, contentDescription = context.words.opensInBrowser, size = 12.dp, tint = Kit.colors.mutedForeground)
            }
        }
    }
}

/** The fields of a record, one panel per section. */
@Composable
private fun FieldPanels(context: RenderContext, record: JsonObject, fields: List<Field>) {
    fields.groupBy { it.section }.forEach { (section, group) ->
        Panel(title = section, gap = 12.dp) {
            group.forEach { field -> FieldFact(context, record, field) }
        }
    }
}

@Composable
private fun FieldFact(context: RenderContext, record: JsonObject, field: Field) {
    val shown = showValue(record[field.name], field, context.facts, context.words, context.now)
    FactLine(field.title, shown.text, shown.tone, "${context.tag}:field-${field.name}")
}

/** Where the record leads: the lines the server built, each of them a link it checked the rights for. */
@Composable
private fun LinksPanel(instance: ServerAppInstance, context: RenderContext, links: List<RecordLink>) {
    if (links.isEmpty()) return
    Panel(flush = true) {
        links.forEachIndexed { index, link ->
            if (index > 0) Hairline()
            // A line that leaves the app says so: «↗» for the browser of the system, «›» for one more screen.
            LinkItem(
                title = link.title,
                subtitle = link.meta,
                external = link.open.url != null,
                onClick = { instance.openLink(link.open, link.title) },
                tag = "${context.tag}:link-${link.open.method ?: "url-$index"}",
                externalHint = context.words.opensInBrowser,
            )
        }
    }
}

/** The card form of a stepper: −, the number, +; a number typed in; and the specials by name. */
@Composable
private fun StepperCard(
    instance: ServerAppInstance,
    context: RenderContext,
    model: String?,
    record: JsonObject,
    action: RecordAction,
    method: DeclaredMethod,
    waiting: Boolean,
) {
    val control = method.ui?.control ?: return
    val range = stepRange(action, method)
    val schema = (action.input ?: method.input).property(control.param ?: "")
    val min = (schema?.get("minimum") as? JsonPrimitive)?.intOrNull ?: Int.MIN_VALUE
    val max = (schema?.get("maximum") as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
    val boundField = context.field(model, control.bind)
    val bound = record[control.bind ?: ""]
    val current = (bound as? JsonPrimitive)?.intOrNull
    val shown = showValue(bound, boundField ?: Field(control.bind ?: "", "", specials = control.specials), context.facts, context.words, context.now)
    val tag = "${context.tag}:${method.name}"
    var typed by remember(record.id(), current) { mutableStateOf(current?.takeIf { it in range }?.toString().orEmpty()) }
    fun send(value: Int) = instance.act(record, action, method, JsonPrimitive(value))

    Panel(title = method.title, description = boundField?.let { "${it.title}: ${shown.text}" }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                Lucide.Minus,
                contentDescription = "−",
                onClick = { current?.let { send(it - 1) } },
                enabled = !waiting && current != null && (current - 1) in range,
                variant = ButtonVariant.Outline,
                modifier = Modifier.testTag("$tag:minus"),
            )
            Box(if (compact) Modifier.weight(1f) else Modifier.width(160.dp)) {
                Input(
                    value = typed,
                    onValueChange = { text -> typed = text.filter { it.isDigit() }.take(9) },
                    placeholder = shown.text,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = Kit.typography.p.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.testTag("$tag:field"),
                )
            }
            IconButton(
                Lucide.Plus,
                contentDescription = "+",
                onClick = { current?.let { send(it + 1) } },
                enabled = !waiting && current != null && (current + 1) in range,
                variant = ButtonVariant.Outline,
                modifier = Modifier.testTag("$tag:plus"),
            )
            if (!compact) SetButton(context, typed, current, min, max, waiting, tag, Modifier, ::send)
        }
        if (compact) SetButton(context, typed, current, min, max, waiting, tag, Modifier.fillMaxWidth(), ::send)
        val specials = control.specials.mapNotNull { special -> special.value.intOrNull?.takeIf { it in min..max }?.let { it to special } }
        if (specials.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                specials.forEach { (value, special) ->
                    // A named value is a shortcut, not the main action: outlined, a dangerous one in red words.
                    AskFirstButton(
                        text = special.title,
                        tone = special.tone,
                        confirm = null,
                        enabled = !waiting && value != current,
                        words = context.words,
                        onClick = { send(value) },
                        tag = "$tag:special-$value",
                        modifier = if (compact) Modifier.weight(1f) else Modifier,
                        variant = ButtonVariant.Outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetButton(context: RenderContext, typed: String, current: Int?, min: Int, max: Int, waiting: Boolean, tag: String, modifier: Modifier, send: (Int) -> Unit) {
    val number = typed.toIntOrNull()
    Button(
        text = context.words.set,
        onClick = { number?.let(send) },
        enabled = !waiting && number != null && number in min..max && number != current,
        loading = waiting,
        modifier = modifier.testTag("$tag:set"),
    )
}

/**
 * A form of one record: closed it is a row that opens it; open — the fields, filled with what the record
 * has now. Its buttons are the page's: in the header, or in the `ActionBar` of a phone ([RecordFrame]).
 */
@Composable
private fun RecordFormCard(
    instance: ServerAppInstance,
    context: RenderContext,
    record: JsonObject,
    action: RecordAction,
    method: DeclaredMethod,
    waiting: Boolean,
    editing: MutableState<Editing?>,
) {
    record.id() ?: return
    val tag = "${context.tag}:${method.name}"
    val open = editing.value?.takeIf { it.method.name == method.name }
    if (open == null) {
        Panel(flush = true) {
            ListRow(
                title = method.title,
                subtitle = null, meta = null, value = null, valueTone = null, badge = null, badgeTone = null,
                tag = tag,
                onClick = if (waiting) null else ({
                    editing.value = Editing(action, method, FormState(formFields(method, action.input ?: method.input), "$tag:form"))
                }),
            )
        }
        return
    }
    val form = open.form
    HoldUnsaved(form.dirty, instance::hold) { saved -> if (form.filled && !waiting) submitEdit(instance, record, open, editing, saved) }
    // Filled in, the form is a step of its own: back closes it, not the screen under it.
    SystemBackHandler(enabled = form.dirty) { instance.leave { editing.value = null } }
    Panel(
        modifier = Modifier.fillMaxWidth().testTag(form.tag),
        title = method.title,
        action = if (form.dirty) ({ Pill(context.words.unsaved, Tone.Warn) }) else null,
        gap = 14.dp,
    ) {
        FormFields(form, waiting, context.words)
    }
}

/**
 * One button per allowed value — the record's own list when the server narrowed it (`_actions`
 * input), the method's otherwise. `confirm` is asked before a `danger` value: REJECT asks, COOKING
 * does not.
 *
 * One main step, the rest quieter, the dangerous one last and apart: four equal buttons make a person
 * read all four; this makes the usual next step the obvious one. [stacked] — a block of full-width
 * buttons under the header of a phone; otherwise a row in the header, the main step last, at the edge.
 */
@Composable
private fun FlowButtons(
    instance: ServerAppInstance,
    context: RenderContext,
    record: JsonObject,
    action: RecordAction,
    method: DeclaredMethod,
    waiting: Boolean,
    stacked: Boolean,
    /** On a phone: the main step as a wide button, the other values in the menu of «⋯». */
    menu: Boolean = false,
    /**
     * P3: null — no action of the record is the main one, and the usual next step of this one is filled; true —
     * this is the main action, its first value filled; false — another one is, nothing of this one is filled.
     */
    emphasis: Boolean? = null,
    /** On a phone, the button that takes the main step's place across the width — «Открыть управление»; all values go behind «⋯». */
    lead: (@Composable RowScope.() -> Unit)? = null,
) {
    val control = method.ui?.control ?: return
    val schema = (action.input ?: method.input).property(control.param ?: "") ?: return
    val values = schema.enumValues()
    if (values.isEmpty()) return
    val titles = schema.titles()
    val tag = "${context.tag}:${method.name}"
    // The server lists the values in the order of the flow, so the first that is not a refusal is the
    // usual next step — «Готовить» for a new order, not «Выполнен».
    val main = mainValueOf(values, control.tones, emphasis)
    val ordered = values.sortedBy { if (control.tones[it] == Tones.DANGER) 1 else 0 }
    @Composable
    fun one(value: String, modifier: Modifier) {
        val tone = control.tones[value]
        AskFirstButton(
            text = titles[value] ?: value,
            tone = tone,
            confirm = method.ui?.confirm?.takeIf { tone == Tones.DANGER },
            enabled = !waiting,
            words = context.words,
            onClick = { instance.act(record, action, method, JsonPrimitive(value)) },
            tag = "$tag-$value",
            modifier = modifier,
            variant = when {
                value == main -> ButtonVariant.Default
                tone == Tones.DANGER -> ButtonVariant.Ghost
                else -> ButtonVariant.Outline
            },
        )
    }
    if (menu && (main != null || lead != null)) {
        // A phone, as the prototype's order: the next step across the width, the rest behind «⋯».
        var open by remember(record.id(), method.name) { mutableStateOf(false) }
        var asking by remember(record.id(), method.name) { mutableStateOf<String?>(null) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lead != null) lead() else one(main!!, Modifier.weight(1f))
            val rest = if (lead != null) ordered else ordered.filter { it != main }
            if (rest.isNotEmpty()) Box {
                IconButton(
                    Lucide.Ellipsis,
                    contentDescription = context.words.moreActions,
                    onClick = { open = true },
                    enabled = !waiting,
                    variant = ButtonVariant.Outline,
                    modifier = Modifier.testTag("$tag:more"),
                )
                Menu(open, { open = false }, width = 240.dp) {
                    rest.forEach { value ->
                        val danger = control.tones[value] == Tones.DANGER
                        if (danger && rest.first() != value) MenuSeparator()
                        Box(Modifier.testTag("$tag-$value")) {
                            MenuItem(
                                text = titles[value] ?: value,
                                onClick = {
                                    open = false
                                    if (danger && method.ui?.confirm != null) asking = value
                                    else instance.act(record, action, method, JsonPrimitive(value))
                                },
                                destructive = danger,
                            )
                        }
                    }
                }
            }
        }
        asking?.let { value ->
            AskDialog(
                question = method.ui?.confirm.orEmpty(),
                confirm = titles[value] ?: value,
                cancel = context.words.cancel,
                destructive = true,
                onConfirm = {
                    asking = null
                    instance.act(record, action, method, JsonPrimitive(value))
                },
                onDismiss = { asking = null },
                tag = "$tag-$value:confirm",
                answers = "$tag-$value",
            )
        }
    } else if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ordered.forEach { value -> one(value, Modifier.fillMaxWidth()) }
        }
    } else {
        // Read left to right, a header ends with its main action: the refusal first, the main step last.
        ordered.sortedBy { if (control.tones[it] == Tones.DANGER) 0 else if (it == main) 2 else 1 }.forEach { value -> one(value, Modifier) }
    }
}

/**
 * The filled button of a `buttons` control (P3): without a main action on the record, the usual next step — the
 * first value that is not a refusal; on the main action its first value; on any other action none.
 */
internal fun mainValueOf(values: List<String>, tones: Map<String, String>, emphasis: Boolean?): String? = when (emphasis) {
    null -> values.firstOrNull { tones[it] != Tones.DANGER }
    true -> values.firstOrNull()
    false -> null
}

/**
 * The fields of a form from the schema of its method: the properties in the schema's order, their
 * `title` from the server, `enum` as chips. A property shape v1 cannot draw — an object, an array —
 * is left out, so a method that grows one still shows the rest instead of nothing.
 */
internal fun formFields(method: DeclaredMethod, input: JsonObject = method.input): List<FormField> {
    val required = input.requiredNames()
    return input.propertyNames().mapNotNull { name ->
        // The id of the record is the record's, not a field to type in.
        if (name == "id" && input !== method.input) return@mapNotNull null
        val schema = input.property(name) ?: return@mapNotNull null
        val type = schema.schemaType() ?: "string"
        if (type !in setOf("string", "integer", "number", "boolean")) return@mapNotNull null
        FormField(
            name = name,
            title = schema.schemaTitle() ?: name,
            type = type,
            required = name in required,
            values = schema.enumValues(),
            titles = schema.titles(),
            format = schema.schemaFormat(),
            initial = schema.defaultString(),
        )
    }
}
