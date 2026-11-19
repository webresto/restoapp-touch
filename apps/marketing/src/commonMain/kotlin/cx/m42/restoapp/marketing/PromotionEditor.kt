package cx.m42.restoapp.marketing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Banknote
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Gift
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Percent
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.render.HoldUnsaved
import cx.m42.restoapp.render.NativeActionScope
import cx.m42.restoapp.render.money
import cx.m42.superizer.kit.ActionBar
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Checkbox
import cx.m42.superizer.kit.Chip
import cx.m42.superizer.kit.Dialog
import cx.m42.superizer.kit.DialogActions
import cx.m42.superizer.kit.Field
import cx.m42.superizer.kit.Grid
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.IconTile
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Notice
import cx.m42.superizer.kit.Page
import cx.m42.superizer.kit.PageHeader
import cx.m42.superizer.kit.PageWidth
import cx.m42.superizer.kit.Panel
import cx.m42.superizer.kit.Pill
import cx.m42.superizer.kit.Select
import cx.m42.superizer.kit.SelectOption
import cx.m42.superizer.kit.Separator
import cx.m42.superizer.kit.Skeleton
import cx.m42.superizer.kit.StatusBadge
import cx.m42.superizer.kit.SwitchItem
import cx.m42.superizer.kit.Tabs
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.byWindow
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The editor of a promotion — the screen `control: {type: "native", screen: "promotion-editor"}` asks for
 * (`marketing.plan.md`, Q43).
 *
 * It exists because the universal renderer cannot draw it: products and groups chosen from the menu, a gift,
 * the ways of receiving and the hours are no list of fields. Everything else about a promotion stays declared —
 * the row, the card and both switches — and this screen only builds the params of one action and sends them.
 *
 * What it reads back is `config` of the record (the hook's `PromotionConfig`), which no declared field shows.
 * What it refuses, it refuses for the same reason the server would: a name and a description are required, a
 * percentage over a hundred is not a discount. Everything else is the server's own word, shown as it comes.
 */

/** The form, as the person is filling it. */
internal data class EditorForm(
    val name: String = "",
    val description: String = "",
    val percentage: Boolean = true,
    val amount: String = "",
    val dishes: Set<String> = emptySet(),
    val groups: Set<String> = emptySet(),
    val minBasketTotal: String = "",
    val serviceTypes: Set<String> = emptySet(),
    val giftDish: String? = null,
    val giftAmount: String = "1",
    val giftFrom: String = "",
    val enable: Boolean = true,
    val onlyByCode: Boolean = false,
    val joint: Boolean = true,
    val onStorefront: Boolean = false,
    val sortOrder: String = "0",
    val days: Set<String> = emptySet(),
    val start: String = "10:00",
    val stop: String = "22:00",
)

/** What the catalog is open for: the products of the discount, its groups, or the gift. */
internal enum class Picking { DISHES, GROUPS, GIFT }

internal data class CatalogItem(
    val id: String,
    val name: String,
    val price: Double,
    val notForSale: Boolean,
    val promo: Boolean,
    val parentGroup: String? = null,
)

internal data class CatalogPage(
    val groups: List<CatalogItem> = emptyList(),
    val items: List<CatalogItem> = emptyList(),
    val loading: Boolean = false,
)

private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.number(name: String): Double = (this[name] as? JsonPrimitive)?.doubleOrNull ?: 0.0

private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.strings(name: String): List<String> =
    (this[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** A number as it is typed: an empty field is "not set", and only digits (and a dot) get in. */
private fun digits(text: String, dot: Boolean = true): String =
    text.filter { it.isDigit() || (dot && it == '.') }.take(9)

private fun amountOf(text: String): Double = text.toDoubleOrNull() ?: 0.0

/** A number on the wire: a whole one goes as a whole, so a server reading an integer reads one. */
private fun amountJson(value: Double): JsonPrimitive =
    if (value == value.toLong().toDouble()) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

/** The form of the record the action was opened on: a promotion being changed, or a blank one. */
internal fun formOf(record: JsonObject): EditorForm {
    val config = record["config"] as? JsonObject ?: JsonObject(emptyMap())
    val gift = config["gift"] as? JsonObject
    val giftDishes = (gift?.get("dishes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val worktime = (config["worktime"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull()
    val amount = config.number("discountAmount")
    return EditorForm(
        name = record.text("name"),
        description = config.text("description"),
        percentage = config.text("discountType") != "flat",
        amount = if (amount > 0) trimNumber(amount) else "",
        dishes = config.strings("dishes").toSet(),
        groups = config.strings("groups").toSet(),
        minBasketTotal = config.number("minBasketTotal").takeIf { it > 0 }?.let(::trimNumber).orEmpty(),
        serviceTypes = config.strings("serviceType").toSet(),
        giftDish = giftDishes.firstOrNull()?.text("dishId"),
        giftAmount = (giftDishes.firstOrNull()?.get("amount") as? JsonPrimitive)?.intOrNull?.toString() ?: "1",
        giftFrom = (gift?.get("minBasketTotal") as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let(::trimNumber).orEmpty(),
        enable = (record["enable"] as? JsonPrimitive)?.booleanOrNull ?: true,
        onlyByCode = record.flag("onlyByCode"),
        joint = record.text("isJoint") != "exclusive",
        onStorefront = record.flag("isPublic"),
        sortOrder = ((record["sortOrder"] as? JsonPrimitive)?.intOrNull ?: 0).toString(),
        days = worktime?.strings("dayOfWeek")?.toSet().orEmpty(),
        start = worktime?.text("start")?.ifEmpty { "10:00" } ?: "10:00",
        stop = worktime?.text("stop")?.ifEmpty { "22:00" } ?: "22:00",
    )
}

/** 10.0 → "10", 10.5 → "10.5": a number in a field, without a tail nobody typed. */
private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** The params of `marketing.savePromotion`, as the hook's schema takes them. */
internal fun paramsOf(form: EditorForm, record: JsonObject): JsonObject = buildJsonObject {
    record["id"]?.let { put("id", it) }
    put("name", form.name.trim())
    put("description", form.description.trim())
    put("enable", form.enable)
    put("onlyByCode", form.onlyByCode)
    put("isJoint", form.joint)
    put("isPublic", form.onStorefront)
    put("sortOrder", form.sortOrder.toIntOrNull() ?: 0)
    if (form.days.isNotEmpty()) {
        put(
            "worktime",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("dayOfWeek", buildJsonArray { form.days.forEach { add(JsonPrimitive(it)) } })
                        put("start", form.start)
                        put("stop", form.stop)
                    },
                )
            },
        )
    }
    put(
        "configDiscount",
        buildJsonObject {
            put("discountType", if (form.percentage) "percentage" else "flat")
            put("discountAmount", amountJson(amountOf(form.amount)))
            put("dishes", buildJsonArray { form.dishes.forEach { add(JsonPrimitive(it)) } })
            put("groups", buildJsonArray { form.groups.forEach { add(JsonPrimitive(it)) } })
            if (form.serviceTypes.isNotEmpty()) {
                put("serviceType", buildJsonArray { form.serviceTypes.forEach { add(JsonPrimitive(it)) } })
            }
            if (form.minBasketTotal.isNotEmpty()) put("minBasketTotal", amountJson(amountOf(form.minBasketTotal)))
            val giftDish = form.giftDish
            if (giftDish != null) {
                put(
                    "gift",
                    buildJsonObject {
                        put("minBasketTotal", amountJson(amountOf(form.giftFrom)))
                        put(
                            "dishes",
                            buildJsonArray {
                                add(buildJsonObject { put("dishId", giftDish); put("amount", form.giftAmount.toIntOrNull() ?: 1) })
                            },
                        )
                    },
                )
            }
        },
    )
}

/** Why the form cannot be sent yet, in words; null — it can. */
internal fun refusalOf(form: EditorForm, words: MarketingWords): String? = when {
    form.name.isBlank() || form.description.isBlank() -> words.nameNeeded
    form.percentage && amountOf(form.amount) > 100 -> words.tooBig
    else -> null
}

/** The screen itself. Registered as `promotion-editor` by [MarketingScreens]. */
@Composable
internal fun PromotionEditor(scope: NativeActionScope) {
    val words = remember(scope.lang) { MarketingWords(scope.lang) }
    var form by remember(scope.record) { mutableStateOf(formOf(scope.record)) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val opened = remember(scope.record) { formOf(scope.record) }
    // What the chosen ids are called: read once from the catalog, and from every page the picker opens.
    val names = remember(scope.record) { mutableStateMapOf<String, String>() }
    val dirty = form != opened
    val fresh = scope.record["id"] == null
    val cls = LocalWindowClass.current
    val currency = scope.facts?.currency

    LaunchedEffect(scope.record) {
        val known = opened.dishes + listOfNotNull(opened.giftDish)
        if (opened.groups.isNotEmpty()) catalogOf(scope, buildJsonObject {})?.let { page -> page.groups.forEach { names[it.id] = it.name } }
        if (known.isNotEmpty()) {
            catalogOf(scope, buildJsonObject { put("ids", buildJsonArray { known.forEach { add(JsonPrimitive(it)) } }) })
                ?.let { page -> page.items.forEach { names[it.id] = it.name } }
        }
    }

    fun save(saved: () -> Unit) {
        val why = refusalOf(form, words)
        refusal = why
        if (why == null) scope.submitThen(paramsOf(form, scope.record), saved)
    }
    HoldUnsaved(dirty, scope.hold) { saved -> if (!scope.busy) save(saved) }

    val saveLabel = if (scope.busy) words.saving else words.save
    Column(modifier = Modifier.fillMaxSize().testTag("marketing:editor")) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Page(PageWidth.Default) {
                PageHeader(
                    title = form.name.ifBlank { if (fresh) words.newPromotion else opened.name.ifBlank { words.editor } },
                    description = words.editorHint,
                    leading = {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(Kit.shapes.lg)
                                .border(1.dp, Kit.colors.input, Kit.shapes.lg)
                                .background(Kit.colors.background)
                                .kitClickable(Kit.shapes.lg, onClick = scope.leave)
                                .semantics { contentDescription = words.close }
                                .testTag("marketing:editor-cancel"),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Lucide.X, contentDescription = null, size = 18.dp) }
                    },
                    badge = { if (dirty) StatusBadge(words.unsaved, Tone.Warn, Modifier.testTag("marketing:editor-unsaved")) },
                    actions = if (cls == WindowClass.Compact) null else {
                        {
                            Button(words.cancel, scope.leave, variant = ButtonVariant.Outline, modifier = Modifier.testTag("marketing:editor-cancel-button"))
                            Button(
                                saveLabel,
                                { save {} },
                                enabled = dirty && !scope.busy,
                                loading = scope.busy,
                                modifier = Modifier.testTag("marketing:editor-save"),
                            )
                        }
                    },
                )
                refusal?.let {
                    Notice(Tone.Danger, Lucide.CircleAlert, words.cannotSave, it, Modifier.testTag("marketing:editor-refusal"))
                }
                scope.failure?.let {
                    Notice(Tone.Danger, Lucide.TriangleAlert, words.serverRefused, it, Modifier.testTag("marketing:editor-failure"))
                }
                Grid(if (cls == WindowClass.Expanded) 5 else 1, spans = listOf(3, 2), gap = byWindow(16.dp, 20.dp, 24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(byWindow(16.dp, 20.dp, 24.dp))) {
                        BasicsPanel(form, words) { form = it }
                        DiscountPanel(form, words, currency, names, edit = { form = it }, pickGift = { picking = Picking.GIFT })
                        AppliesPanel(form, words, names) { picking = it }
                        ConditionsPanel(form, words, currency) { form = it }
                        BehaviourPanel(form, words) { form = it }
                    }
                    PromotionPreview(form, words, scope)
                }
            }
        }
        if (cls == WindowClass.Compact) {
            ActionBar {
                Text(
                    if (dirty) words.unsavedChanges else words.noChanges,
                    Modifier.weight(1f),
                    variant = TextVariant.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(
                    saveLabel,
                    { save {} },
                    enabled = dirty && !scope.busy,
                    loading = scope.busy,
                    modifier = Modifier.testTag("marketing:editor-save"),
                )
            }
        }
    }

    picking?.let { what ->
        CatalogPicker(
            scope = scope,
            words = words,
            picking = what,
            form = form,
            onNames = { found -> names.putAll(found) },
            onChange = { form = it },
            onClose = { picking = null },
        )
    }
}

/** The unit after a number in a field; the field's label says it to a screen reader, so it stays out of the text. */
@Composable
private fun UnitSign(sign: String) {
    Text(sign, variant = TextVariant.Muted, modifier = Modifier.clearAndSetSemantics {})
}

/** «₽» of RUB: the sign [money] writes after a sum, alone. */
private fun currencySign(currency: String?): String = money(0.0, currency).drop(1).trim { it.isWhitespace() || it == '\u00A0' }

/** The form's own setter, as every panel takes it. */
private typealias Edit = (EditorForm) -> Unit

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

/** A field that opens a choice: what is chosen as pills, or a quiet «Выбрать…», and a chevron. */
@Composable
private fun ChooserField(label: String, chosen: List<String>, empty: String, tag: String, onClick: () -> Unit, description: String? = null) {
    Field(label, description = description) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .clip(Kit.shapes.md)
                .border(1.dp, Kit.colors.input, Kit.shapes.md)
                .background(Kit.colors.background)
                .kitClickable(Kit.shapes.md, hover = false, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chosen.isEmpty()) {
                Text(empty, Modifier.weight(1f), color = Kit.colors.mutedForeground, maxLines = 1)
            } else {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chosen.forEach { Pill(it, Tone.Brand) }
                }
            }
            Icon(Lucide.ChevronRight, null, size = 16.dp, tint = Kit.colors.mutedForeground)
        }
    }
}

/** Switches in one bordered block, hairlines between them — as the prototype's settings rows. */
@Composable
private fun SwitchBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Kit.shapes.xl).border(1.dp, Kit.colors.border, Kit.shapes.xl),
        content = content,
    )
}

@Composable
private fun ChipField(label: String, hint: String?, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, variant = TextVariant.Small)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        if (hint != null) Text(hint, variant = TextVariant.Muted)
    }
}

@Composable
private fun BasicsPanel(form: EditorForm, words: MarketingWords, edit: Edit) {
    Panel(title = words.basics) {
        Field(words.name) {
            Input(
                value = form.name,
                onValueChange = { edit(form.copy(name = it.take(200))) },
                placeholder = words.namePlaceholder,
                label = words.name,
                modifier = Modifier.testTag("marketing:editor-name"),
            )
        }
        Field(words.description, description = words.descriptionHint) {
            Input(
                value = form.description,
                onValueChange = { edit(form.copy(description = it.take(1000))) },
                label = words.description,
                singleLine = false,
                minLines = 2,
                modifier = Modifier.testTag("marketing:editor-description"),
            )
        }
        SwitchBlock {
            SwitchItem(
                title = words.works,
                checked = form.enable,
                onCheckedChange = { edit(form.copy(enable = it, onlyByCode = if (it) form.onlyByCode else false)) },
                description = words.worksHint,
                modifier = Modifier.testTag("marketing:editor-enable"),
            )
            Separator()
            SwitchItem(
                title = words.onlyByCode,
                checked = form.onlyByCode,
                onCheckedChange = { edit(form.copy(onlyByCode = it)) },
                description = words.onlyByCodeHint,
                enabled = form.enable,
                modifier = Modifier.testTag("marketing:editor-only-by-code"),
            )
        }
    }
}

@Composable
private fun DiscountPanel(form: EditorForm, words: MarketingWords, currency: String?, names: Map<String, String>, edit: Edit, pickGift: () -> Unit) {
    val sign = currencySign(currency)
    Panel(title = words.discount) {
        Tabs(
            listOf(words.percentage, words.flat),
            if (form.percentage) 0 else 1,
            { edit(form.copy(percentage = it == 0)) },
            modifier = Modifier.testTag("marketing:editor-kind"),
        )
        Field(if (form.percentage) words.percentSize else words.flatSize, modifier = Modifier.widthIn(max = 320.dp)) {
            Input(
                value = form.amount,
                onValueChange = { edit(form.copy(amount = digits(it))) },
                label = words.amount + ", " + (if (form.percentage) "%" else sign),
                placeholder = "0",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailing = { UnitSign(if (form.percentage) "%" else sign) },
                modifier = Modifier.testTag("marketing:editor-amount"),
            )
        }
        Separator()
        val gift = form.giftDish
        ChooserField(
            words.gift,
            listOfNotNull(gift?.let { names[it] ?: words.giftDish }),
            words.noGift,
            "marketing:editor-gift",
            pickGift,
            description = if (gift == null) words.giftHint else null,
        )
        if (gift != null) {
            Grid(byWindow(1, 2, 2), gap = 12.dp) {
                Field(words.giftAmount) {
                    Input(
                        value = form.giftAmount,
                        onValueChange = { edit(form.copy(giftAmount = digits(it, dot = false))) },
                        label = words.giftAmount,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.testTag("marketing:editor-gift-amount"),
                    )
                }
                Field(words.giftFrom) {
                    Input(
                        value = form.giftFrom,
                        onValueChange = { edit(form.copy(giftFrom = digits(it))) },
                        label = words.giftFrom + ", " + sign,
                        placeholder = "0",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailing = { UnitSign(sign) },
                        modifier = Modifier.testTag("marketing:editor-gift-from"),
                    )
                }
            }
            Button(
                words.removeGift,
                { edit(form.copy(giftDish = null)) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                leadingIcon = Lucide.X,
                modifier = Modifier.testTag("marketing:editor-gift-clear"),
            )
        }
    }
}

@Composable
private fun AppliesPanel(form: EditorForm, words: MarketingWords, names: Map<String, String>, pick: (Picking) -> Unit) {
    Panel(title = words.appliesTo, description = if (form.dishes.isEmpty() && form.groups.isEmpty()) words.wholeBasketHint else words.chosen) {
        ChooserField(words.menuGroups, chosenNames(form.groups, names, words), words.choose, "marketing:editor-groups", { pick(Picking.GROUPS) })
        ChooserField(words.dishes, chosenNames(form.dishes, names, words), words.choose, "marketing:editor-dishes", { pick(Picking.DISHES) })
    }
}

/** The names of the chosen ids, as far as they are known; the rest as one «+N». */
private fun chosenNames(ids: Set<String>, names: Map<String, String>, words: MarketingWords): List<String> {
    val known = ids.mapNotNull { names[it] }
    val unknown = ids.size - known.size
    return if (unknown > 0) known + words.more(unknown) else known
}

@Composable
private fun ConditionsPanel(form: EditorForm, words: MarketingWords, currency: String?, edit: Edit) {
    val sign = currencySign(currency)
    Panel(title = words.conditionsOnly) {
        Field(words.minBasket, description = words.basketHint, modifier = Modifier.widthIn(max = 320.dp)) {
            Input(
                value = form.minBasketTotal,
                onValueChange = { edit(form.copy(minBasketTotal = digits(it))) },
                placeholder = "0",
                label = words.minBasket + ", " + sign,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailing = { UnitSign(sign) },
                modifier = Modifier.testTag("marketing:editor-min-basket"),
            )
        }
        ChipField(words.serviceTypes, if (form.serviceTypes.isEmpty()) words.anyService else null) {
            for (kind in listOf("delivery", "pickup", "dine-in")) {
                Chip(
                    words.serviceType(kind),
                    selected = kind in form.serviceTypes,
                    onClick = { edit(form.copy(serviceTypes = form.serviceTypes.toggle(kind))) },
                    modifier = Modifier.testTag("marketing:editor-service-$kind"),
                )
            }
        }
        ChipField(words.weekDays, if (form.days.isEmpty()) words.everyDay else null) {
            for ((day, title) in words.days) {
                Chip(
                    title,
                    selected = day in form.days,
                    onClick = { edit(form.copy(days = form.days.toggle(day))) },
                    modifier = Modifier.testTag("marketing:editor-day-$day"),
                )
            }
        }
        if (form.days.isNotEmpty()) {
            Grid(2, gap = 12.dp) {
                Field(words.from) {
                    Select(form.start, hourOptions(form.start), { edit(form.copy(start = it)) }, title = words.fromHour, modifier = Modifier.testTag("marketing:editor-start"))
                }
                Field(words.till) {
                    Select(form.stop, hourOptions(form.stop), { edit(form.copy(stop = it)) }, title = words.tillHour, modifier = Modifier.testTag("marketing:editor-stop"))
                }
            }
        }
    }
}

/** Every half hour of a day, and the value already set when it is between them. */
private fun hourOptions(current: String): List<SelectOption> {
    val halves = (0..48).map { i -> (i / 2).toString().padStart(2, '0') + if (i % 2 == 0) ":00" else ":30" }.dropLast(1) + "23:59"
    return (if (current in halves || current.isBlank()) halves else (halves + current).sorted()).map { SelectOption(it, it) }
}

@Composable
private fun BehaviourPanel(form: EditorForm, words: MarketingWords, edit: Edit) {
    Panel(title = words.behaviour) {
        SwitchBlock {
            SwitchItem(
                title = words.joint,
                checked = form.joint,
                onCheckedChange = { edit(form.copy(joint = it)) },
                modifier = Modifier.testTag("marketing:editor-joint"),
            )
            Separator()
            SwitchItem(
                title = words.onStorefront,
                checked = form.onStorefront,
                onCheckedChange = { edit(form.copy(onStorefront = it)) },
                modifier = Modifier.testTag("marketing:editor-storefront"),
            )
        }
        Field(words.priority, description = words.priorityHint, modifier = Modifier.widthIn(max = 240.dp)) {
            Input(
                value = form.sortOrder,
                onValueChange = { edit(form.copy(sortOrder = it.filter { char -> char.isDigit() || char == '-' }.take(5))) },
                label = words.priority,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.testTag("marketing:editor-sort-order"),
            )
        }
    }
}

/** How the promotion reads to a guest and to a cashier, as the form changes. */
@Composable
private fun PromotionPreview(form: EditorForm, words: MarketingWords, scope: NativeActionScope) {
    val brand = Kit.palette.brand
    val onBrand = Kit.palette.onBrand
    val amount = amountOf(form.amount)
    val schedule = buildList {
        if (form.days.isEmpty()) {
            add(words.aroundTheClock)
        } else {
            add(words.days.filter { it.first in form.days }.joinToString(", ") { it.second } + " · " + form.start + "–" + form.stop)
        }
        if (form.serviceTypes.isNotEmpty()) add(form.serviceTypes.joinToString(", ") { words.serviceType(it).lowercase() })
        if (form.onlyByCode) add(words.byCode)
        if (!form.enable) add(words.off)
    }.joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("marketing:editor-preview")) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(lerp(brand, Color.White, 0.06f), lerp(brand, Color.Black, 0.3f))))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(words.guestSees, style = Kit.textStyles.overline, color = onBrand.copy(alpha = 0.72f))
            Text(form.name.ifBlank { words.editor }, style = Kit.typography.large, color = onBrand)
            Text(
                summaryOf(form, words, scope),
                style = Kit.typography.h3,
                color = onBrand,
                modifier = Modifier.testTag("marketing:editor-summary"),
            )
            Text(schedule, style = Kit.typography.muted, color = onBrand.copy(alpha = 0.82f))
        }
        Panel(title = words.atTill, gap = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(
                    when {
                        form.giftDish != null && amount <= 0 -> Lucide.Gift
                        form.percentage -> Lucide.Percent
                        else -> Lucide.Banknote
                    },
                    Tone.Success,
                    32.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(form.name.ifBlank { words.editor }, style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (form.onlyByCode) words.appliesByCode else words.appliesByItself,
                        style = Kit.textStyles.caption,
                        color = Kit.colors.mutedForeground,
                    )
                }
                if (amount > 0) {
                    Text(
                        if (form.percentage) words.percentOff(trimNumber(amount)) else "−" + money(amount, scope.facts?.currency),
                        style = Kit.textStyles.number.copy(fontWeight = FontWeight.SemiBold),
                        color = Tone.Success.textColor(),
                    )
                }
            }
        }
    }
}

/** What the promotion will give, in the words the card shows afterwards. */
internal fun summaryOf(form: EditorForm, words: MarketingWords, scope: NativeActionScope): String {
    val currency = scope.facts?.currency
    val parts = mutableListOf<String>()
    val amount = amountOf(form.amount)
    if (amount > 0) {
        val size = if (form.percentage) words.percentOff(trimNumber(amount)) else "−" + money(amount, currency)
        parts += if (form.dishes.isEmpty() && form.groups.isEmpty()) "$size ${words.wholeBasket.lowercase()}" else "$size ${words.chosen.lowercase()}"
    }
    if (form.giftDish != null) {
        parts += words.giftWords(form.giftAmount.toIntOrNull() ?: 1, form.giftFrom.takeIf { it.isNotEmpty() }?.let { money(amountOf(it), currency) }.orEmpty())
    }
    return parts.joinToString(", ").ifEmpty { words.nothing }
}

/** One answer of `marketing.catalog`, or null when it did not come — the picker says why itself. */
private suspend fun catalogOf(scope: NativeActionScope, params: JsonObject): CatalogPage? = try {
    pageOf(scope.call("marketing.catalog", params) as? JsonObject ?: JsonObject(emptyMap()))
} catch (refused: MobileApiException) {
    null
}

private fun pageOf(answer: JsonObject): CatalogPage {
    fun nodes(name: String) = (answer[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { node ->
        CatalogItem(
            id = node.text("id"),
            name = node.text("name"),
            price = node.number("price"),
            notForSale = node.flag("notForSale"),
            promo = node.flag("promo"),
            parentGroup = node.text("parentGroup").ifEmpty { null },
        )
    }
    return CatalogPage(groups = nodes("groups"), items = nodes("items"), loading = false)
}

/**
 * The catalog of the menu in a dialog, for choosing what the discount is for — with ticks, applied on
 * «Готово» — and the gift, where only a product the guest cannot add himself is allowed (core's own
 * rule, `Promotion.validateGift`) and one tap chooses it.
 */
@Composable
private fun CatalogPicker(
    scope: NativeActionScope,
    words: MarketingWords,
    picking: Picking,
    form: EditorForm,
    onNames: (Map<String, String>) -> Unit,
    onChange: (EditorForm) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var top by remember { mutableStateOf(CatalogPage(loading = true)) }
    var found by remember { mutableStateOf(CatalogPage()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(if (picking == Picking.GROUPS) form.groups else form.dishes) }
    val open = remember { mutableStateMapOf<String, CatalogPage>() }
    val seen = remember { mutableStateMapOf<String, String>() }
    val gift = picking == Picking.GIFT

    suspend fun load(params: JsonObject): CatalogPage? = try {
        pageOf(scope.call("marketing.catalog", params) as? JsonObject ?: JsonObject(emptyMap())).also { page ->
            failure = null
            (page.groups + page.items).forEach { seen[it.id] = it.name }
        }
    } catch (refused: MobileApiException) {
        failure = refused.message
        null
    }

    LaunchedEffect(Unit) { top = load(buildJsonObject {}) ?: CatalogPage() }
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            found = found.copy(loading = true)
            found = load(buildJsonObject { put("q", query.trim()) }) ?: CatalogPage()
        }
    }

    fun finish(apply: () -> Unit) {
        onNames(seen.toMap())
        apply()
        onClose()
    }

    val title = when (picking) {
        Picking.DISHES -> words.dishes
        Picking.GROUPS -> words.menuGroups
        Picking.GIFT -> words.gift
    }
    Dialog(
        onDismiss = onClose,
        title = title,
        description = if (gift) words.giftHint else words.chosenCount(picked.size),
        closeLabel = words.close,
        width = 520.dp,
        footer = {
            if (gift) {
                Button(words.cancel, onClose, Modifier.fillMaxWidth().testTag("marketing:picker-cancel"), variant = ButtonVariant.Outline)
            } else {
                Box(Modifier.testTag("marketing:picker-done-row")) {
                    DialogActions(
                        confirm = words.done,
                        onConfirm = {
                            finish {
                                onChange(if (picking == Picking.GROUPS) form.copy(groups = picked) else form.copy(dishes = picked))
                            }
                        },
                        cancel = words.cancel,
                        onCancel = onClose,
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag("marketing:picker"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (picking != Picking.GROUPS) {
                Input(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = words.searchProduct,
                    leadingIcon = Lucide.Search,
                    modifier = Modifier.testTag("marketing:picker-search"),
                )
            }
            failure?.let { Notice(Tone.Danger, Lucide.TriangleAlert, words.serverRefused, it, Modifier.testTag("marketing:picker-error")) }

            @Composable
            fun itemRow(item: CatalogItem, indent: Boolean) {
                // core refuses a gift that is for sale, and so does this screen: the rule is one.
                val allowed = !gift || item.notForSale || item.promo
                val chosen = if (gift) form.giftDish == item.id else item.id in picked
                PickerRow(
                    title = item.name,
                    subtitle = money(item.price, scope.facts?.currency) + if (gift && !allowed) " · " + words.notGiftable else "",
                    leading = { Checkbox(chosen, null, enabled = allowed) },
                    indent = indent,
                    enabled = allowed,
                    tag = "marketing:picker-dish-${item.id}",
                    onClick = {
                        if (gift) {
                            finish { onChange(form.copy(giftDish = if (form.giftDish == item.id) null else item.id)) }
                        } else {
                            picked = picked.toggle(item.id)
                        }
                    },
                )
            }

            if (query.isNotBlank() && picking != Picking.GROUPS) {
                when {
                    found.loading -> PickerSkeleton()
                    found.items.isEmpty() -> Text(words.nothingFound, variant = TextVariant.Muted, modifier = Modifier.padding(8.dp).testTag("marketing:picker-empty"))
                    else -> found.items.forEach { itemRow(it, indent = false) }
                }
                return@Column
            }
            if (top.loading) {
                PickerSkeleton()
                return@Column
            }
            val groupIds = top.groups.map { it.id }.toSet()
            val roots = top.groups.filter { it.parentGroup == null || it.parentGroup !in groupIds }
            if (roots.isEmpty() && top.items.isEmpty()) {
                Text(words.nothingFound, variant = TextVariant.Muted, modifier = Modifier.padding(8.dp).testTag("marketing:picker-empty"))
            }
            for (group in roots) {
                if (picking == Picking.GROUPS) {
                    // A group itself is what is chosen here: a row with a tick, not a door.
                    PickerRow(
                        title = group.name,
                        subtitle = null,
                        leading = { Checkbox(group.id in picked, null) },
                        tag = "marketing:picker-group-${group.id}",
                        onClick = { picked = picked.toggle(group.id) },
                    )
                } else {
                    val page = open[group.id]
                    val ticked = page?.items?.count { it.id in picked } ?: 0
                    PickerRow(
                        title = group.name,
                        subtitle = null,
                        leading = {
                            Icon(if (page != null) Lucide.ChevronDown else Lucide.ChevronRight, null, size = 16.dp, tint = Kit.colors.mutedForeground)
                        },
                        trailing = if (ticked > 0) {
                            { Pill(ticked.toString(), Tone.Brand) }
                        } else {
                            null
                        },
                        tag = "marketing:picker-open-${group.id}",
                        onClick = { if (page != null) open.remove(group.id) else open[group.id] = CatalogPage(loading = true) },
                    )
                    if (page != null) {
                        LaunchedEffect(group.id) {
                            if (page.loading) open[group.id] = load(buildJsonObject { put("group", group.id) }) ?: CatalogPage()
                        }
                        if (page.loading) PickerSkeleton() else page.items.forEach { itemRow(it, indent = true) }
                    }
                }
            }
            if (picking != Picking.GROUPS) {
                top.items.filter { it.parentGroup == null || it.parentGroup !in groupIds }.forEach { itemRow(it, indent = false) }
            }
        }
    }
}

/** A row of the picker: a tick or a chevron, what it is, and its price. */
@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    tag: String,
    indent: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clip(Kit.shapes.md)
            .kitClickable(Kit.shapes.md, enabled = enabled, onClick = onClick)
            .padding(start = if (indent) 36.dp else 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = Kit.textStyles.label, color = if (enabled) Kit.colors.foreground else Kit.colors.mutedForeground)
            if (subtitle != null) Text(subtitle, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
        }
        trailing?.invoke()
    }
}

@Composable
private fun PickerSkeleton() {
    Column(Modifier.testTag("marketing:picker-loading"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { Skeleton(Modifier.fillMaxWidth().height(36.dp)) }
    }
}
