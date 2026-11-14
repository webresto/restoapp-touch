package cx.m42.restoapp.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.restoapp.protocol.Controls
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.ItemUi
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.Open
import cx.m42.restoapp.protocol.RecordAction
import cx.m42.restoapp.protocol.RecordLink
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.protocol.Views
import cx.m42.restoapp.protocol.propertyNames
import cx.m42.restoapp.protocol.requiredNames
import cx.m42.restoapp.protocol.schemaFormat
import cx.m42.restoapp.protocol.schemaTitle
import cx.m42.restoapp.protocol.schemaType
import cx.m42.restoapp.protocol.actions
import cx.m42.restoapp.protocol.enumValues
import cx.m42.restoapp.protocol.defaultString
import cx.m42.restoapp.protocol.id
import cx.m42.restoapp.protocol.links
import cx.m42.restoapp.protocol.open
import cx.m42.restoapp.protocol.property
import cx.m42.restoapp.protocol.titles
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.components.ToggleRow
import cx.m42.superizer.ui.platform.ScreenSteps
import cx.m42.superizer.ui.platform.SystemBackHandler
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.intOrNull

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
    val tokens = AppTheme
    Box(modifier = Modifier.fillMaxSize().background(tokens.pageBackground)) {
        when {
            declared == null -> EmptyState(words.gone, null, null, null, "server-app:gone")
            // Either the app comes as JS, or it asks for a native screen this build does not have:
            // the universal renderer cannot draw either, and the host only lands here when nothing
            // else can (order-entry.plan.md, «Контракт»).
            declared.script != null || declared.client != null ->
                EmptyState(words.needsNewerApp, words.needsNewerAppHint, null, null, "${declared.id}:needs-update")
            declared.byPlace && places.isEmpty() -> EmptyState(words.noPlaces, null, null, null, "${declared.id}:no-places")
            else -> {
                val context = RenderContext(declared, facts, words, runtime.clock.now())
                val frames by instance.frames.collectAsState()
                val request by instance.native.collectAsState()
                val native = request?.let { NativeActions.of(it.method.ui?.control?.screen)?.to(it) }
                ScreenSteps(frames.size - 1 + if (native != null) 1 else 0)
                val frame = frames.lastOrNull()
                val view = frame?.let { declared.method(it.method)?.ui?.view }
                when {
                    // A screen of the client's own, over everything: it has its own way back.
                    native != null -> {
                        SystemBackHandler(enabled = true) { instance.leave(instance::closeNative) }
                        NativeActionScreen(instance, context, native.second, native.first)
                    }
                    frame == null -> EmptyState(words.unknownView, null, null, null, "${declared.id}:unknown")
                    frames.size > 1 -> {
                        SystemBackHandler(enabled = true) { instance.leave(instance::back) }
                        when (view) {
                            Views.LIST -> ListScreen(instance, context, frame, bottom = false)
                            Views.DETAIL -> DetailScreen(instance, context, frame)
                            else -> EmptyState(words.unknownView, null, null, null, "${declared.id}:unknown")
                        }
                    }
                    view == Views.LIST -> ListScreen(instance, context, frame, bottom = true)
                    view == Views.DETAIL -> HomeRecordScreen(instance, context, frame)
                    else -> EmptyState(words.unknownView, null, null, null, "${declared.id}:unknown")
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

/** «← Назад» of a frame that stands on another one: the shell does not draw it, the stack does. */
@Composable
private fun BackLine(instance: ServerAppInstance, context: RenderContext) {
    AppButton(
        text = "← " + context.words.back,
        onClick = { instance.leave(instance::back) },
        variant = ButtonVariant.Ghost,
        size = ButtonSize.Sm,
        modifier = Modifier.padding(8.dp).testTag("${context.tag}:back"),
    )
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

/** The connection, the host's notice and the last error, as lines across the top. */
@Composable
private fun TopNotices(instance: ServerAppInstance, context: RenderContext) {
    val words = context.words
    val runtime = LocalAppRuntime.current
    val status by instance.server.status.collectAsState()
    val error by instance.error.collectAsState()
    val notice by instance.notice.collectAsState()
    val tag = context.tag
    when (status) {
        ServerStatus.Offline -> Notice(words.offline, Tones.WARNING, words.retry, instance::retry, "$tag:offline")
        ServerStatus.SignedOut -> Notice(words.signedOut + ". " + words.signedOutHint, Tones.DANGER, words.toHome, {
            runtime.scope.launch { runtime.navigation.close() }
        }, "$tag:signed-out")
        ServerStatus.UpdateRequired -> Notice(words.updateRequired, Tones.DANGER, null, null, "$tag:update")
        ServerStatus.Online -> Unit
    }
    notice?.let { Notice(it, Tones.INFO, "OK", instance::dismissNotice, "$tag:notice") }
    error?.takeIf { it.text.isNotEmpty() && it.code != MobileApiException.NETWORK }?.let {
        Notice(it.text, Tones.DANGER, words.retry, instance::retry, "$tag:error")
    }
}

@Composable
private fun PlaceSwitch(
    instance: ServerAppInstance,
    context: RenderContext,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val places by instance.server.places.collectAsState()
    val place by instance.server.place.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    val tag = context.tag
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // One point: nothing to choose, and its name is in the bar already. A frame standing on another
            // one does not offer the switch either: the point belongs to the app, not to a screen of it.
            if (enabled && context.app.byPlace && places.size > 1) {
                place?.let { PlaceLine(it.title, it.address, { choosing = true }, "$tag:place") }
            }
            content()
        }
        ChoiceSheet(
            visible = choosing,
            title = context.words.places,
            choices = places.map { SheetChoice(it.id, it.title, it.address) },
            selected = place?.id,
            onDismiss = { choosing = false },
            onPick = { id ->
                choosing = false
                instance.server.selectPlace(id)
            },
            tag = "$tag:places",
        )
    }
}

@Composable
private fun ListScreen(instance: ServerAppInstance, context: RenderContext, frame: Frame, bottom: Boolean) {
    val method = context.app.method(frame.method) ?: return
    val state = frame.list
    val busy by instance.busy.collectAsState()
    val ui = method.ui!!
    val words = context.words
    val tokens = AppTheme
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

    PlaceSwitch(instance, context, enabled = bottom) {
        if (!bottom) BackLine(instance, context)
        TopNotices(instance, context)
        listAction?.let { action ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AppButton(
                    text = if (adding) words.cancel else "+ ${action.title}",
                    onClick = {
                        if (nativeList != null) instance.openNative(JsonObject(emptyMap()), RecordAction(action.name), action)
                        else if (adding) instance.leave { adding = false }
                        else adding = true
                    },
                    variant = if (adding) ButtonVariant.Ghost else ButtonVariant.Default,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("$tag:add"),
                )
            }
            if (adding && nativeList == null) {
                FormCard(
                    title = action.title,
                    fields = formFields(action),
                    submit = action.title,
                    busy = "list:${action.name}" in busy,
                    words = words,
                    onSubmit = { values, saved ->
                        instance.actOnList(action, JsonObject(values)) {
                            adding = false
                            saved()
                        }
                    },
                    onCancel = { instance.leave { adding = false } },
                    hold = instance::hold,
                    tag = "$tag:form",
                )
            }
        }
        ui.search?.let { SearchBox(state.query, words.search, instance::search, "$tag:search") }
        FrameChips(instance, context, method, state.counts)
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).testTag("$tag:list")) {
            when {
                !state.loaded && state.loading -> Text(
                    text = words.loading,
                    style = tokens.label,
                    color = tokens.muted,
                    modifier = Modifier.padding(20.dp).testTag("$tag:loading"),
                )
                state.loaded && state.items.isEmpty() -> EmptyState(
                    text = if (state.query.isNotBlank()) words.nothingFound else ui.empty ?: words.nothingFound,
                    hint = null, action = null, onAction = null, tag = "$tag:empty",
                )
            }
            var group: String? = null
            state.items.forEachIndexed { index, record ->
                val groupTitle = ui.groupBy?.let { plain(record[it]) }
                if (groupTitle != null && groupTitle != group) {
                    group = groupTitle
                    Text(
                        text = groupTitle,
                        style = tokens.label.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.muted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                } else if (index > 0) {
                    RowDivider()
                }
                RecordRow(instance, context, method, ui.item ?: ItemUi(), record, busy)
            }
            if (state.nextCursor != null) {
                AppButton(
                    text = if (state.loadingMore) words.loading else words.more,
                    onClick = instance::more,
                    enabled = !state.loadingMore,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Default,
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("$tag:more"),
                )
            }
            val total = state.total
            if (total != null && total > state.items.size) {
                Text(
                    text = words.shownOf(state.items.size, total),
                    style = tokens.footnote,
                    color = tokens.muted,
                    modifier = Modifier.padding(horizontal = 20.dp).testTag("$tag:shown"),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The chips of the frame's method: its `enum` parameter, its titles, and the counts the answer brought. */
@Composable
private fun FrameChips(instance: ServerAppInstance, context: RenderContext, method: DeclaredMethod, counts: Map<String, Int>) {
    val chips = method.ui?.chips ?: return
    val schema = method.input.property(chips) ?: return
    Chips(
        values = schema.enumValues(),
        titles = schema.titles(),
        counts = counts,
        selected = instance.currentChip(),
        onSelect = instance::chip,
        tag = "${context.tag}:chip",
    )
}

@Composable
private fun RecordRow(
    instance: ServerAppInstance,
    context: RenderContext,
    method: DeclaredMethod,
    item: ItemUi,
    record: JsonObject,
    busy: Set<String>,
) {
    val id = record.id() ?: return
    val model = method.model
    val tag = "${context.tag}:row-$id"
    val actions = context.actions(record, "row")
    val stepper = actions.firstOrNull { it.second.ui?.control?.type == Controls.STEPPER }
    val value = context.show(record, model, item.value)
    val badge = context.show(record, model, item.badge)
    // The row's own link beats the default one of the list (`client-lookup.plan.md`): a mixed list leads
    // each of its rows where that row belongs.
    val opens = record.open() != null || method.ui?.open?.let { context.app.method(it) } != null

    ListRow(
        title = item.title?.let { plain(record[it]) } ?: id,
        // Under a group's heading its name would only repeat it.
        subtitle = item.subtitle?.takeIf { it != method.ui?.groupBy }?.let { context.show(record, model, it)?.text },
        meta = context.show(record, model, item.meta)?.text,
        // A stepper in the row draws the value between its − and +.
        value = if (stepper != null && stepBase(record, stepper, item) != null) null else value?.text,
        valueTone = value?.tone,
        badge = badge?.text,
        badgeTone = badge?.tone,
        tag = tag,
        onClick = if (opens) ({ instance.openRow(record) }) else null,
    ) {
        actions.forEach { (action, method) ->
            val control = method.ui?.control ?: return@forEach
            val waiting = "$id:${method.name}" in busy
            when (control.type) {
                Controls.STEPPER -> stepBase(record, action to method, item)?.let { base ->
                    val range = stepRange(action, method)
                    StepperRow(
                        display = value?.text ?: base.toString(),
                        tone = value?.tone,
                        onMinus = (base - 1).takeIf { it in range }?.let { next -> { instance.act(record, action, method, JsonPrimitive(next)) } },
                        onPlus = (base + 1).takeIf { it in range }?.let { next -> { instance.act(record, action, method, JsonPrimitive(next)) } },
                        enabled = !waiting,
                        tag = "$tag:${method.name}",
                    )
                }
                Controls.TOGGLE -> {
                    val checked = (record[control.bind ?: ""] as? JsonPrimitive)?.booleanOrNull ?: false
                    SwitchControl(checked, method.title, enabled = !waiting, onChange = { on ->
                        instance.act(record, action, method, JsonPrimitive(on))
                    }, tag = "$tag:${method.name}")
                }
                // Buttons and forms need room: they live on the card.
                else -> Unit
            }
        }
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

/** A record opened from a list: its header, its actions, its fields by section, and where it leads next. */
@Composable
private fun DetailScreen(instance: ServerAppInstance, context: RenderContext, frame: Frame) {
    val method = context.app.method(frame.method)
    val record = frame.record
    val tag = context.tag
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("$tag:detail")) {
        BackLine(instance, context)
        TopNotices(instance, context)
        if (record == null) {
            Text(text = context.words.loading, style = AppTheme.label, color = AppTheme.muted, modifier = Modifier.padding(20.dp))
            return@Column
        }
        // `frame.loading`: the card is drawn from the row of the list while the full record is being
        // read. A screen of the client's own is built from that record — the editor of a setting from
        // the JSON Schema in it — so it must not open on half of one.
        RecordCard(instance, context, method?.model, method?.ui?.item ?: ItemUi(), record, settled = !frame.loading)
    }
}

/** A home method that answers one record — the summary of «Маркетинг»: its chips, its card, its links. */
@Composable
private fun HomeRecordScreen(instance: ServerAppInstance, context: RenderContext, frame: Frame) {
    val method = context.app.method(frame.method) ?: return
    PlaceSwitch(instance, context) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("${context.tag}:detail")) {
            TopNotices(instance, context)
            FrameChips(instance, context, method, emptyMap())
            frame.record?.let { RecordCard(instance, context, method.model, method.ui?.item ?: ItemUi(), it, settled = !frame.loading) }
        }
    }
}

@Composable
private fun RecordCard(
    instance: ServerAppInstance,
    context: RenderContext,
    model: String?,
    item: ItemUi,
    record: JsonObject,
    /** False while this record is still being re-read: an action that reads the record waits for it. */
    settled: Boolean = true,
) {
    val tokens = AppTheme
    val busy by instance.busy.collectAsState()
    val id = record.id().orEmpty()
    val tag = context.tag

    Text(
        text = item.title?.let { plain(record[it]) } ?: id,
        style = tokens.header,
        color = tokens.foreground,
        modifier = Modifier.padding(horizontal = 20.dp).testTag("$tag:title"),
    )
    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        context.show(record, model, item.subtitle)?.let {
            Text(text = it.text, style = tokens.label, color = tokens.muted)
            Spacer(Modifier.width(8.dp))
        }
        context.show(record, model, item.badge)?.let { Badge(it.text, it.tone, Modifier.testTag("$tag:badge")) }
    }
    context.show(record, model, item.value)?.let {
        Text(
            text = it.text,
            style = tokens.subtitle.copy(fontWeight = FontWeight.SemiBold),
            color = tokens.toneColor(it.tone) ?: tokens.foreground,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).testTag("$tag:value"),
        )
    }

    context.actions(record, "detail").forEach { (action, method) ->
        val waiting = "$id:${method.name}" in busy
        when (method.ui?.control?.type) {
            Controls.NATIVE -> if (NativeActions.of(method.ui?.control?.screen) != null) {
                Card {
                    AppButton(
                        text = method.title,
                        onClick = { instance.openNative(record, action, method) },
                        enabled = !waiting && settled,
                        size = ButtonSize.Default,
                        modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("$tag:${method.name}"),
                    )
                }
            }
            Controls.STEPPER -> StepperCard(instance, context, model, record, action, method, waiting)
            Controls.TOGGLE -> Card {
                val control = method.ui?.control
                ToggleRow(
                    label = method.title,
                    checked = (record[control?.bind ?: ""] as? JsonPrimitive)?.booleanOrNull ?: false,
                    onCheckedChange = { on -> instance.act(record, action, method, JsonPrimitive(on)) },
                    enabled = !waiting,
                    modifier = Modifier.testTag("$tag:${method.name}"),
                )
            }
            Controls.BUTTONS -> ButtonsCard(instance, context, record, action, method, waiting)
            // A form on a record — «Настройки» of a service: a button, and under it the fields of the record's own
            // schema (`_actions` input), which start with the record's values (`default`).
            Controls.FORM -> RecordFormCard(instance, context, record, action, method, waiting)
            // Unknown controls are not drawn (plan section 4).
            else -> Unit
        }
    }

    // Fields by section, in the declaration's order; the title is the header already.
    //
    // A field the record does not carry at all is left out: a promotion has either a percentage or a flat
    // amount, and a «—» where the other one would be says something false. A field that is there and empty
    // (`null`) still shows «—» — that is a value nobody has set, which is worth seeing.
    val shownFields = context.app.fields(model).filter { it.name != item.title && record.containsKey(it.name) }
    shownFields.groupBy { it.section }.forEach { (section, fields) ->
        val lines: @Composable () -> Unit = {
            fields.forEachIndexed { index, field ->
                if (index > 0) RowDivider()
                val shown = showValue(record[field.name], field, context.facts, context.words, context.now)
                FieldLine(field.title, shown.text, shown.tone, "$tag:field-${field.name}")
            }
        }
        if (section == null) Card(content = lines) else Section(title = section, content = lines)
    }

    // Where the card leads: the lines the server built, each of them a link it checked the rights for.
    val links = record.links()
    if (links.isNotEmpty()) {
        Card {
            links.forEachIndexed { index, link ->
                if (index > 0) RowDivider()
                LinkRow(instance, context, link, index)
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

/** One line of `_links`: its words, what it says about itself, and a «›» to say it opens. */
@Composable
private fun LinkRow(instance: ServerAppInstance, context: RenderContext, link: RecordLink, index: Int) {
    ListRow(
        title = link.title,
        subtitle = link.meta,
        meta = null,
        // A line that leaves the app says so: «↗» for the browser of the system, «›» for one more screen.
        value = if (link.open.url != null) "↗" else "›",
        valueTone = null,
        badge = null,
        badgeTone = null,
        tag = "${context.tag}:link-${link.open.method ?: "url-$index"}",
        onClick = { instance.openLink(link.open, link.title) },
    )
}

/** A white block on the grey page, untitled: what [Section] is without its heading. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 20.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.background),
    ) { content() }
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

    Section(title = method.title, hint = boundField?.let { "${it.title}: ${shown.text}" }) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = "−",
                    onClick = { current?.let { send(it - 1) } },
                    enabled = !waiting && current != null && (current - 1) in range,
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Default,
                    modifier = Modifier.testTag("$tag:minus"),
                )
                Box(Modifier.weight(1f)) {
                    AppTextField(
                        value = typed,
                        onValueChange = { text -> typed = text.filter { it.isDigit() }.take(9) },
                        placeholder = shown.text,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.testTag("$tag:field"),
                    )
                }
                AppButton(
                    text = "+",
                    onClick = { current?.let { send(it + 1) } },
                    enabled = !waiting && current != null && (current + 1) in range,
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Default,
                    modifier = Modifier.testTag("$tag:plus"),
                )
            }
            val number = typed.toIntOrNull()
            AppButton(
                text = context.words.set,
                onClick = { number?.let(::send) },
                enabled = !waiting && number != null && number in min..max && number != current,
                size = ButtonSize.Default,
                modifier = Modifier.fillMaxWidth().testTag("$tag:set"),
            )
            val specials = control.specials.mapNotNull { special -> special.value.intOrNull?.takeIf { it in min..max }?.let { it to special } }
            if (specials.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    specials.forEach { (value, special) ->
                        AppButton(
                            text = special.title,
                            onClick = { send(value) },
                            enabled = !waiting && value != current,
                            variant = if (special.tone == Tones.DANGER) ButtonVariant.Destructive else ButtonVariant.Outline,
                            size = ButtonSize.Default,
                            modifier = Modifier.weight(1f).testTag("$tag:special-$value"),
                        )
                    }
                }
            }
        }
    }
}

/** A form of one record: closed it is a button, open — the fields, filled with what the record has now. */
@Composable
private fun RecordFormCard(
    instance: ServerAppInstance,
    context: RenderContext,
    record: JsonObject,
    action: RecordAction,
    method: DeclaredMethod,
    waiting: Boolean,
) {
    val id = record.id() ?: return
    val tag = "${context.tag}:${method.name}"
    var open by remember(id, method.name) { mutableStateOf(false) }
    if (!open) {
        Card {
            AppButton(
                text = method.title,
                onClick = { open = true },
                enabled = !waiting,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Default,
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag(tag),
            )
        }
        return
    }
    val input = action.input ?: method.input
    FormCard(
        title = method.title,
        fields = formFields(method, input),
        submit = context.words.save,
        busy = waiting,
        words = context.words,
        onSubmit = { values, saved ->
            val params = buildJsonObject {
                if (input.property("id") != null) put("id", JsonPrimitive(id))
                values.forEach { (key, value) -> put(key, value) }
            }
            instance.actWith(record, action, method, params) {
                open = false
                saved()
            }
        },
        onCancel = { instance.leave { open = false } },
        hold = instance::hold,
        tag = "$tag:form",
    )
}

/**
 * One button per allowed value — the record's own list when the server narrowed it (`_actions`
 * input), the method's otherwise. `confirm` is asked before a `danger` value: REJECT asks, COOKING
 * does not.
 */
@Composable
private fun ButtonsCard(
    instance: ServerAppInstance,
    context: RenderContext,
    record: JsonObject,
    action: RecordAction,
    method: DeclaredMethod,
    waiting: Boolean,
) {
    val control = method.ui?.control ?: return
    val schema = (action.input ?: method.input).property(control.param ?: "") ?: return
    val values = schema.enumValues()
    if (values.isEmpty()) return
    val titles = schema.titles()
    val tag = "${context.tag}:${method.name}"
    Section(title = method.title) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                val tone = control.tones[value]
                ConfirmButton(
                    text = titles[value] ?: value,
                    tone = tone,
                    confirm = method.ui?.confirm?.takeIf { tone == Tones.DANGER },
                    enabled = !waiting,
                    words = context.words,
                    onClick = { instance.act(record, action, method, JsonPrimitive(value)) },
                    tag = "$tag-$value",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
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
