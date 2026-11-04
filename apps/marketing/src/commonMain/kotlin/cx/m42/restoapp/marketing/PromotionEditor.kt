package cx.m42.restoapp.marketing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.render.CatalogShell
import cx.m42.restoapp.render.Chips
import cx.m42.restoapp.render.EmptyState
import cx.m42.restoapp.render.GroupTile
import cx.m42.restoapp.render.ListRow
import cx.m42.restoapp.render.NativeActionScope
import cx.m42.restoapp.render.Notice
import cx.m42.restoapp.render.money
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.components.ToggleRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

internal data class CatalogItem(val id: String, val name: String, val price: Double, val notForSale: Boolean, val promo: Boolean)

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
    val tokens = AppTheme
    var form by remember(scope.record) { mutableStateOf(formOf(scope.record)) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().testTag("marketing:editor")) {
        if (picking != null) {
            CatalogPicker(
                scope = scope,
                words = words,
                picking = picking!!,
                form = form,
                onChange = { form = it },
                onClose = { picking = null },
            )
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton(
                    text = words.cancel,
                    onClick = scope.close,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("marketing:editor-cancel"),
                )
                Text(
                    text = form.name.ifBlank { words.editor },
                    style = tokens.subtitle,
                    color = tokens.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
                )
                AppButton(
                    text = if (scope.busy) words.saving else words.save,
                    onClick = {
                        val why = refusalOf(form, words)
                        refusal = why
                        if (why == null) scope.submit(paramsOf(form, scope.record))
                    },
                    enabled = !scope.busy,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("marketing:editor-save"),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                refusal?.let { Notice(it, "danger", null, null, "marketing:editor-refusal") }

                Section(title = words.basics) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextField(
                            value = form.name,
                            onValueChange = { form = form.copy(name = it.take(200)) },
                            placeholder = words.name,
                            modifier = Modifier.testTag("marketing:editor-name"),
                        )
                        AppTextField(
                            value = form.description,
                            onValueChange = { form = form.copy(description = it.take(1000)) },
                            placeholder = words.description,
                            modifier = Modifier.testTag("marketing:editor-description"),
                        )
                    }
                }

                Section(title = words.discount) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chips(
                            values = listOf("percentage", "flat"),
                            titles = mapOf("percentage" to words.percentage, "flat" to words.flat),
                            counts = emptyMap(),
                            selected = if (form.percentage) "percentage" else "flat",
                            onSelect = { form = form.copy(percentage = it == "percentage") },
                            tag = "marketing:editor-kind",
                        )
                        AppTextField(
                            value = form.amount,
                            onValueChange = { form = form.copy(amount = digits(it)) },
                            placeholder = words.amount,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.testTag("marketing:editor-amount"),
                        )
                    }
                }

                Section(title = words.appliesTo, hint = if (form.dishes.isEmpty() && form.groups.isEmpty()) words.wholeBasket else words.chosen) {
                    ListRow(
                        title = words.counted(words.dishes, form.dishes.size),
                        subtitle = null, meta = null, value = "›", valueTone = null, badge = null, badgeTone = null,
                        tag = "marketing:editor-dishes",
                        onClick = { picking = Picking.DISHES },
                    )
                    RowDivider()
                    ListRow(
                        title = words.counted(words.groups, form.groups.size),
                        subtitle = null, meta = null, value = "›", valueTone = null, badge = null, badgeTone = null,
                        tag = "marketing:editor-groups",
                        onClick = { picking = Picking.GROUPS },
                    )
                }

                Section(title = words.conditions) {
                    ListRow(
                        title = words.giftDish,
                        subtitle = form.giftDish?.let { words.giftWords(form.giftAmount.toIntOrNull() ?: 1, form.giftFrom) },
                        meta = null, value = "›", valueTone = null, badge = null, badgeTone = null,
                        tag = "marketing:editor-gift",
                        onClick = { picking = Picking.GIFT },
                    )
                    RowDivider()
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (form.giftDish != null) {
                            AppTextField(
                                value = form.giftAmount,
                                onValueChange = { form = form.copy(giftAmount = digits(it, dot = false)) },
                                placeholder = words.giftAmount,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.testTag("marketing:editor-gift-amount"),
                            )
                            AppTextField(
                                value = form.giftFrom,
                                onValueChange = { form = form.copy(giftFrom = digits(it)) },
                                placeholder = words.giftFrom,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.testTag("marketing:editor-gift-from"),
                            )
                        }
                        AppTextField(
                            value = form.minBasketTotal,
                            onValueChange = { form = form.copy(minBasketTotal = digits(it)) },
                            placeholder = words.minBasket,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.testTag("marketing:editor-min-basket"),
                        )
                        Text(text = words.serviceTypes, style = tokens.label, color = tokens.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (kind in listOf("delivery", "pickup", "dine-in")) {
                                AppButton(
                                    text = words.serviceType(kind),
                                    onClick = {
                                        form = form.copy(
                                            serviceTypes = if (kind in form.serviceTypes) form.serviceTypes - kind else form.serviceTypes + kind,
                                        )
                                    },
                                    variant = if (kind in form.serviceTypes) ButtonVariant.Default else ButtonVariant.Outline,
                                    size = ButtonSize.Sm,
                                    modifier = Modifier.testTag("marketing:editor-service-$kind"),
                                )
                            }
                        }
                        if (form.serviceTypes.isEmpty()) {
                            Text(text = words.anyService, style = tokens.footnote, color = tokens.muted)
                        }
                    }
                }

                Section(title = words.behaviour) {
                    ToggleRow(
                        label = words.works,
                        checked = form.enable,
                        onCheckedChange = { form = form.copy(enable = it, onlyByCode = if (it) form.onlyByCode else false) },
                        modifier = Modifier.testTag("marketing:editor-enable"),
                    )
                    Text(text = words.worksHint, style = tokens.footnote, color = tokens.muted, modifier = Modifier.padding(horizontal = 16.dp))
                    RowDivider()
                    ToggleRow(
                        label = words.onlyByCode,
                        checked = form.onlyByCode,
                        onCheckedChange = { form = form.copy(onlyByCode = it) },
                        enabled = form.enable,
                        modifier = Modifier.testTag("marketing:editor-only-by-code"),
                    )
                    Text(text = words.onlyByCodeHint, style = tokens.footnote, color = tokens.muted, modifier = Modifier.padding(horizontal = 16.dp))
                    RowDivider()
                    ToggleRow(
                        label = words.joint,
                        checked = form.joint,
                        onCheckedChange = { form = form.copy(joint = it) },
                        modifier = Modifier.testTag("marketing:editor-joint"),
                    )
                    RowDivider()
                    ToggleRow(
                        label = words.onStorefront,
                        checked = form.onStorefront,
                        onCheckedChange = { form = form.copy(onStorefront = it) },
                        modifier = Modifier.testTag("marketing:editor-storefront"),
                    )
                    RowDivider()
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppTextField(
                            value = form.sortOrder,
                            onValueChange = { form = form.copy(sortOrder = it.filter { char -> char.isDigit() || char == '-' }.take(5)) },
                            placeholder = words.priority,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.testTag("marketing:editor-sort-order"),
                        )
                        Text(text = words.priorityHint, style = tokens.footnote, color = tokens.muted)
                    }
                }

                Section(title = words.schedule, hint = if (form.days.isEmpty()) words.aroundTheClock else null) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((day, title) in words.days) {
                                AppButton(
                                    text = title,
                                    onClick = { form = form.copy(days = if (day in form.days) form.days - day else form.days + day) },
                                    variant = if (day in form.days) ButtonVariant.Default else ButtonVariant.Outline,
                                    size = ButtonSize.Sm,
                                    modifier = Modifier.testTag("marketing:editor-day-$day"),
                                )
                            }
                        }
                        if (form.days.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) {
                                    AppTextField(
                                        value = form.start,
                                        onValueChange = { form = form.copy(start = it.take(5)) },
                                        placeholder = words.from,
                                        modifier = Modifier.testTag("marketing:editor-start"),
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    AppTextField(
                                        value = form.stop,
                                        onValueChange = { form = form.copy(stop = it.take(5)) },
                                        placeholder = words.till,
                                        modifier = Modifier.testTag("marketing:editor-stop"),
                                    )
                                }
                            }
                        }
                    }
                }

                Section(title = words.total) {
                    Text(
                        text = summaryOf(form, words, scope),
                        style = tokens.body.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.foreground,
                        modifier = Modifier.padding(16.dp).testTag("marketing:editor-summary"),
                    )
                }
                Spacer(Modifier.height(24.dp))
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

/**
 * The catalog of the menu, for choosing what the discount is for — and the gift, where only a product the guest
 * cannot add himself is allowed (core's own rule, `Promotion.validateGift`).
 */
@Composable
private fun CatalogPicker(
    scope: NativeActionScope,
    words: MarketingWords,
    picking: Picking,
    form: EditorForm,
    onChange: (EditorForm) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = AppTheme
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(CatalogPage(loading = true)) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, group) {
        page = page.copy(loading = true)
        try {
            val answer = scope.call(
                "marketing.catalog",
                buildJsonObject {
                    if (query.isNotBlank()) put("q", query.trim())
                    group?.let { put("group", it) }
                },
            ) as? JsonObject ?: JsonObject(emptyMap())
            fun nodes(name: String) = (answer[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { node ->
                CatalogItem(
                    id = node.text("id"),
                    name = node.text("name"),
                    price = node.number("price"),
                    notForSale = node.flag("notForSale"),
                    promo = node.flag("promo"),
                )
            }
            page = CatalogPage(groups = nodes("groups"), items = nodes("items"), loading = false)
            failure = null
        } catch (refused: MobileApiException) {
            page = page.copy(loading = false)
            failure = refused.message
        }
    }

    val title = when (picking) {
        Picking.DISHES -> words.dishes
        Picking.GROUPS -> words.groups
        Picking.GIFT -> words.gift
    }

    CatalogShell(
        back = words.back,
        onBack = { if (group != null) group = null else onClose() },
        title = title,
        query = query,
        searchHint = words.searchProduct,
        onQuery = { query = it },
        tag = "marketing:picker",
    ) {
        failure?.let { Notice(it, "danger", null, null, "marketing:picker-error") }
        when {
            page.loading && page.items.isEmpty() && page.groups.isEmpty() ->
                Text(words.loading, style = tokens.label, color = tokens.muted, modifier = Modifier.padding(20.dp).testTag("marketing:picker-loading"))
            page.items.isEmpty() && page.groups.isEmpty() -> EmptyState(words.nothingFound, null, null, null, "marketing:picker-empty")
        }

        for (node in page.groups) {
            if (picking == Picking.GROUPS) {
                // A group itself is what is chosen here: a row with a tick, not a door.
                PickerRow(
                    title = node.name,
                    subtitle = null,
                    chosen = node.id in form.groups,
                    tag = "marketing:picker-group-${node.id}",
                    onClick = {
                        onChange(form.copy(groups = if (node.id in form.groups) form.groups - node.id else form.groups + node.id))
                    },
                )
            } else {
                GroupTile(node.name, { group = node.id }, "marketing:picker-open-${node.id}")
            }
        }

        if (picking != Picking.GROUPS) {
            for (item in page.items) {
                val gift = picking == Picking.GIFT
                // core refuses a gift that is for sale, and so does this screen: the rule is one.
                val allowed = !gift || item.notForSale || item.promo
                PickerRow(
                    title = item.name,
                    subtitle = money(item.price, scope.facts?.currency) + if (gift && !allowed) " · " + words.giftDish else "",
                    chosen = if (gift) form.giftDish == item.id else item.id in form.dishes,
                    enabled = allowed,
                    tag = "marketing:picker-dish-${item.id}",
                    onClick = {
                        if (gift) {
                            onChange(form.copy(giftDish = if (form.giftDish == item.id) null else item.id))
                            onClose()
                        } else {
                            onChange(form.copy(dishes = if (item.id in form.dishes) form.dishes - item.id else form.dishes + item.id))
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A row of the picker: what it is, and whether it is ticked. */
@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    chosen: Boolean,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        meta = null,
        value = if (chosen) "✓" else null,
        valueTone = if (chosen) "success" else null,
        badge = null,
        badgeTone = null,
        tag = tag,
        onClick = if (enabled) onClick else null,
    )
}
