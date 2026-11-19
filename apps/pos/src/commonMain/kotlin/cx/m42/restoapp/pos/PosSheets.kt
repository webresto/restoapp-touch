package cx.m42.restoapp.pos

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Ticket
import cx.m42.restoapp.protocol.Tones
import cx.m42.superizer.kit.Avatar
import cx.m42.superizer.kit.ChoiceItem
import cx.m42.superizer.kit.ChoiceTile
import cx.m42.superizer.kit.Chip
import cx.m42.superizer.kit.Field
import cx.m42.superizer.kit.Grid
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.Item
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.QtyStepper
import cx.m42.superizer.kit.Separator
import cx.m42.superizer.kit.SwitchItem
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.byWindow
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles
import androidx.compose.ui.draw.alpha

/*
 * The questions of the order, one kit dialog each — a centred card in a wide window, a sheet from the
 * bottom on a phone: how the food is handed over, who the guest is, where it goes, when, what it is
 * paid with, a promo code, the details, a note to a line, and what goes with a dish. Every answer is
 * a call — the server writes it on the order and answers with the whole receipt — and a dialog
 * closes only when the server took it.
 */

@Composable
internal fun PosSheets(context: PosContext) {
    val state = context.state
    val sheet = state.sheet ?: return
    val order = state.order ?: return
    when (sheet) {
        PosSheet.Service -> ServiceSheet(context, order)
        PosSheet.Guest -> GuestSheet(context, order)
        PosSheet.Clients -> ClientsSheet(context)
        PosSheet.Address -> AddressSheet(context, order)
        PosSheet.Time -> TimeSheet(context, order)
        PosSheet.Payment -> PaymentSheet(context, order)
        PosSheet.Promocode -> PromocodeSheet(context, order)
        PosSheet.Details -> DetailsSheet(context, order)
        PosSheet.LineComment -> LineCommentSheet(context, order)
        PosSheet.Options -> OptionsSheet(context)
    }
}

private fun tagOf(sheet: PosSheet) = "pos:sheet-${sheet.name.lowercase()}"

/** The server's refusal of the answer, inside the dialog that gave it: the operator fixes what they look at. */
@Composable
private fun Refusal(context: PosContext) {
    context.state.message?.takeIf { it.tone == Tones.DANGER }?.let { PosNotice(context.words, it.tone, it.text, "pos:sheet-message") }
}

/** A dialog with only a way out: the choice itself is the answer. */
@Composable
private fun ChoiceDialog(
    context: PosContext,
    sheet: PosSheet,
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val tag = tagOf(sheet)
    val close = context.instance::closeSheet
    PosDialog(
        tag = tag,
        title = title,
        description = description,
        onDismiss = close,
        words = context.words,
        footer = { PosActions(null, {}, "$tag:ok", context.words.close, close, "$tag:close") },
    ) {
        Refusal(context)
        content()
    }
}

/** Delivery, pickup or the dining room — and the table when the order is served at one. */
@Composable
private fun ServiceSheet(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    var table by remember(order.id) { mutableStateOf(order.table.orEmpty()) }
    ChoiceDialog(context, PosSheet.Service, words.receiving, words.receivingHint) {
        val options = state.options.service
        Grid(options.size.coerceIn(1, 3), gap = 8.dp) {
            options.forEach { option ->
                val on = order.serviceType == option.id
                ChoiceTile(
                    on,
                    { if (option.available) instance.setService(option.id, table.takeIf { option.id == PosOrder.DINE_IN }) },
                    Modifier.alpha(if (option.available) 1f else 0.5f).testTag("pos:service-${option.id}"),
                    padding = 12.dp,
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(serviceIcon(option.id), null, size = 20.dp, tint = if (on) Kit.palette.onBrandSoft else Kit.colors.mutedForeground)
                        Text(words.serviceType(option.id), style = Kit.textStyles.label, maxLines = 1)
                        option.reason?.takeIf { !option.available }?.let { Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground) }
                    }
                }
            }
        }
        if (order.serviceType == PosOrder.DINE_IN) {
            Field(words.table, description = words.tableHint) {
                Input(
                    table,
                    { table = it },
                    Modifier.testTag("pos:table-input"),
                    placeholder = "12",
                    label = words.table,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            cx.m42.superizer.kit.Button(
                words.save,
                { instance.setTable(table) },
                Modifier.testTag("pos:table-save"),
                enabled = !state.busy && table.isNotBlank(),
            )
        }
    }
}

/** The guest: a name and a number, with an account made on the spot when asked; or a customer found. */
@Composable
private fun GuestSheet(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    var name by remember(order.id) { mutableStateOf(order.customerName.orEmpty()) }
    var phone by remember(order.id) { mutableStateOf(order.customerPhone.orEmpty()) }
    var create by remember(order.id) { mutableStateOf(false) }
    val tag = tagOf(PosSheet.Guest)
    PosDialog(
        tag = tag,
        title = words.guest,
        description = words.guestHint,
        onDismiss = instance::closeSheet,
        words = words,
        footer = {
            PosActions(
                words.save, { instance.setCustomer(name.trim(), phone.trim(), create) }, "pos:guest-save",
                words.cancel, instance::closeSheet, "$tag:close",
                enabled = !state.busy && name.isNotBlank() && phone.isNotBlank(),
                extra = Triple(words.findClient, { instance.openSheet(PosSheet.Clients) }, "pos:guest-find"),
            )
        },
    ) {
        Refusal(context)
        Grid(byWindow(1, 2, 2), gap = 12.dp) {
            Field(words.name) { Input(name, { name = it }, Modifier.testTag("pos:guest-name"), label = words.name) }
            Field(words.phone) {
                Input(
                    phone, { phone = it }, Modifier.testTag("pos:guest-phone"),
                    placeholder = "+7", label = words.phone,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Kit.colors.border, RoundedCornerShape(12.dp))) {
            SwitchItem(words.createClient, create, { create = it }, Modifier.testTag("pos:guest-create"), enabled = !state.busy)
        }
    }
}

/** The customers of this server, searched by the digits of a number or by name. */
@Composable
private fun ClientsSheet(context: PosContext) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    var query by remember { mutableStateOf("") }
    ChoiceDialog(context, PosSheet.Clients, words.findClient) {
        Input(
            query,
            { typed ->
                query = typed
                instance.searchClients(typed)
            },
            Modifier.testTag("pos:clients-search"),
            placeholder = words.searchClient,
            leadingIcon = Lucide.Search,
        )
        val clients = state.options.clients
        if (clients.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Kit.colors.border, RoundedCornerShape(12.dp))) {
                clients.forEachIndexed { i, client ->
                    if (i > 0) Separator()
                    Item(
                        title = client.title,
                        description = client.phone,
                        modifier = Modifier.testTag("pos:client-${client.id}"),
                        onClick = { instance.setClient(client.id) },
                        leading = { Avatar(client.title, size = 30.dp, fill = Kit.palette.brandSoft, contentColor = Kit.palette.onBrandSoft) },
                        trailing = {
                            if (client.orderCount > 0) Text(words.orders(client.orderCount), style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
                        },
                    )
                }
            }
        }
    }
}

/** Where a delivery goes: a saved address of the customer, or one typed here. */
@Composable
private fun AddressSheet(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val options = state.options
    var city by remember(order.id) { mutableStateOf(options.cities.firstOrNull()?.name.orEmpty()) }
    if (city.isEmpty() && options.cities.isNotEmpty()) city = options.cities.first().name
    var street by remember(order.id) { mutableStateOf("") }
    var home by remember(order.id) { mutableStateOf("") }
    var apartment by remember(order.id) { mutableStateOf("") }
    val tag = tagOf(PosSheet.Address)
    PosDialog(
        tag = tag,
        title = words.address,
        onDismiss = instance::closeSheet,
        words = words,
        footer = {
            PosActions(
                words.save, { instance.setAddress(city, street.trim(), home.trim(), apartment.trim(), null) }, "pos:address-save",
                words.cancel, instance::closeSheet, "$tag:close",
                enabled = !state.busy && street.isNotBlank() && home.isNotBlank(),
            )
        },
    ) {
        Refusal(context)
        if (options.savedAddresses.isNotEmpty()) {
            Text(words.savedAddresses, variant = TextVariant.Small)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Kit.colors.border, RoundedCornerShape(12.dp))) {
                options.savedAddresses.forEachIndexed { i, saved ->
                    if (i > 0) Separator()
                    // By its id: the server reads the house and the flat off the record, because core
                    // refuses a delivery whose address is a line without a house number.
                    Item(
                        saved.formatted,
                        Modifier.testTag("pos:saved-${saved.id}"),
                        onClick = { instance.setSavedAddress(saved.id) },
                        leading = { Icon(Lucide.MapPin, null, size = 16.dp, tint = Kit.colors.mutedForeground) },
                    )
                }
            }
        }
        if (options.cities.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.cities.forEach { option ->
                    Chip(option.name, city == option.name, { city = option.name }, Modifier.testTag("pos:city-${option.id}"))
                }
            }
        }
        Field(words.street) {
            Input(
                street,
                { typed ->
                    street = typed
                    instance.searchStreets(city, typed)
                },
                Modifier.testTag("pos:address-street"),
                label = words.street,
                leadingIcon = Lucide.MapPin,
            )
        }
        val streets = options.streets.take(4)
        if (streets.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Kit.colors.border, RoundedCornerShape(12.dp))) {
                streets.forEachIndexed { i, suggestion ->
                    if (i > 0) Separator()
                    Item(suggestion.name, Modifier.testTag("pos:street-${suggestion.id}"), onClick = { street = suggestion.name })
                }
            }
        }
        Grid(2, gap = 12.dp) {
            Field(words.house) { Input(home, { home = it }, Modifier.testTag("pos:address-home"), label = words.house) }
            Field(words.apartment) {
                Input(apartment, { apartment = it }, Modifier.testTag("pos:address-apartment"), label = words.apartment, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
    }
}

/** When: as soon as possible, by one of the kitchen's slots, or within so many minutes. */
@Composable
private fun TimeSheet(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val options = state.options.time
    var wait by remember { mutableStateOf(order.maxWaitMinutes?.toString().orEmpty()) }
    ChoiceDialog(context, PosSheet.Time, words.time, words.timeHint) {
        Column {
            if (options.asap) {
                ChoiceItem(
                    words.asap,
                    order.date == null && order.maxWaitMinutes == null,
                    { instance.setTime("asap") },
                    Modifier.testTag("pos:time-asap"),
                    description = if (options.minCookingMinutes > 0) "${words.cooking} ~${options.minCookingMinutes} ${words.minutes}" else null,
                )
            }
            options.slots.take(8).forEach { slot ->
                val from = slot.firstOrNull() ?: return@forEach
                ChoiceItem(words.clock(from), order.date == from, { instance.setTime("time", date = from) }, Modifier.testTag("pos:time-slot-$from"))
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("${words.maxWait}, ${words.minutes}", Modifier.weight(1f)) {
                Input(
                    wait, { wait = it.filter(Char::isDigit) }, Modifier.testTag("pos:time-wait"),
                    label = words.maxWait, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            cx.m42.superizer.kit.Button(
                words.save,
                { instance.setTime("maxWait", maxWaitMinutes = wait.toIntOrNull()) },
                Modifier.testTag("pos:time-wait-save"),
                enabled = !state.busy && (wait.toIntOrNull() ?: 0) > 0,
            )
        }
    }
}

/** What it is paid with; online payment is listed and refused, with the reason said. */
@Composable
private fun PaymentSheet(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    ChoiceDialog(context, PosSheet.Payment, words.payment) {
        Column {
            state.options.payments.forEach { method ->
                ChoiceItem(
                    method.title,
                    order.paymentMethodId == method.id,
                    { if (method.available) instance.setPayment(method.id) },
                    Modifier.testTag("pos:payment-${method.id}"),
                    // Online payment ends in a redirect, and a terminal has nowhere to send one (slice D).
                    description = if (method.available) null else words.notOnTerminal,
                    enabled = method.available,
                )
            }
        }
    }
}

@Composable
private fun PromocodeSheet(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    var code by remember(order.id) { mutableStateOf(order.promotionCode.orEmpty()) }
    val refused = state.message?.tone == Tones.DANGER
    val tag = tagOf(PosSheet.Promocode)
    PosDialog(
        tag = tag,
        title = words.promocode,
        description = words.promocodeHint,
        onDismiss = instance::closeSheet,
        words = words,
        footer = {
            PosActions(
                words.save, { instance.setPromocode(code.trim()) }, "pos:promocode-save",
                words.cancel, instance::closeSheet, "$tag:close",
                enabled = !state.busy && code.isNotBlank(),
                extra = if (order.promotionCode != null) Triple(words.clear, { instance.setPromocode(null) }, "pos:promocode-clear") else null,
                extraEnabled = !state.busy,
            )
        },
    ) {
        Field(words.code, error = state.message?.takeIf { refused }?.text) {
            Input(
                code, { code = it }, Modifier.testTag("pos:promocode-input"),
                placeholder = "DEMO10", label = words.promocode, isInvalid = refused, leadingIcon = Lucide.Ticket,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
        }
    }
}

@Composable
private fun DetailsSheet(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    var comment by remember(order.id) { mutableStateOf(order.comment.orEmpty()) }
    var persons by remember(order.id) { mutableStateOf(order.personsCount) }
    val tag = tagOf(PosSheet.Details)
    PosDialog(
        tag = tag,
        title = words.details,
        onDismiss = instance::closeSheet,
        words = words,
        footer = {
            PosActions(words.save, { instance.setDetails(comment.trim(), persons) }, "pos:details-save", words.cancel, instance::closeSheet, "$tag:close", enabled = !state.busy)
        },
    ) {
        Refusal(context)
        Field(words.orderComment, description = words.orderCommentHint) {
            Input(comment, { comment = it }, Modifier.testTag("pos:details-comment"), label = words.comment, singleLine = false, minLines = 3)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(words.persons, style = Kit.textStyles.label)
                Text(words.personsHint, variant = TextVariant.Muted)
            }
            Row(Modifier.testTag("pos:details-persons")) {
                QtyStepper(persons ?: 1, { persons = it }, min = 1)
            }
        }
    }
}

@Composable
private fun LineCommentSheet(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val lineId = state.lineId ?: return
    val line = order.lines.firstOrNull { it.lineId == lineId } ?: return
    var comment by remember(lineId) { mutableStateOf(line.comment.orEmpty()) }
    val tag = tagOf(PosSheet.LineComment)
    PosDialog(
        tag = tag,
        title = words.lineComment,
        description = line.name,
        onDismiss = instance::closeSheet,
        words = words,
        footer = {
            PosActions(words.save, { instance.setLineComment(lineId, comment.trim()) }, "pos:line-comment-save", words.cancel, instance::closeSheet, "$tag:close", enabled = !state.busy)
        },
    ) {
        Refusal(context)
        Input(comment, { comment = it }, Modifier.testTag("pos:line-comment-input"), placeholder = words.lineCommentHint, label = words.comment, singleLine = false, minLines = 2)
    }
}

/**
 * What goes with a dish: the modifier groups the server read off it.
 *
 * A group that takes one is a set of tiles, the chosen one in the accent; a group that takes several
 * is a counter per option. The rules are the server's and so are the numbers — the dialog shows what an
 * option adds to the dish and never a sum of its own: what the dish costs is on the receipt a moment
 * later. A required group left empty is marked red once the server has refused it.
 */
@Composable
private fun OptionsSheet(context: PosContext) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val chooser = state.chooser
    val tag = tagOf(PosSheet.Options)
    val refused = state.message?.tone == Tones.DANGER
    PosDialog(
        tag = tag,
        // The dish is the title: the operator is composing that one, and the dialog says which.
        title = chooser?.options?.name ?: words.options,
        description = chooser?.options?.takeIf { it.price > 0 }?.let { context.money(it.price) },
        onDismiss = instance::closeSheet,
        words = words,
        width = 540.dp,
        footer = {
            PosActions(
                if (chooser?.options?.lineId == null) words.addToReceipt else words.save,
                instance::confirmOptions,
                "pos:options-save",
                words.cancel, instance::closeSheet, "$tag:close",
                enabled = !state.busy && chooser != null,
            )
        },
    ) {
        if (chooser == null) {
            Text(words.loading, Modifier.testTag("pos:options-loading"), variant = TextVariant.Muted)
            return@PosDialog
        }
        Refusal(context)
        if (chooser.options.groups.isEmpty()) {
            Text(words.noOptions, Modifier.testTag("pos:options-none"), variant = TextVariant.Muted)
        }
        chooser.options.groups.forEach { group ->
            val empty = group.options.none { (chooser.chosen[it.id] ?: 0) > 0 }
            val bad = refused && group.required && empty
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        group.name,
                        Modifier.testTag("pos:option-group-${group.id}"),
                        style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold),
                        color = if (bad) Tone.Danger.textColor() else Kit.colors.foreground,
                    )
                    // How many of the group may be taken — the server's rule, said rather than enforced here.
                    val rule = listOfNotNull(
                        if (group.required) words.mustChoose else words.optional,
                        if (group.single) words.chooseOne else group.maxAmount?.let { words.upTo(it) },
                    ).joinToString(" · ")
                    Text(rule, style = Kit.textStyles.caption, color = if (bad) Tone.Danger.textColor() else Kit.colors.mutedForeground)
                }
                if (group.single) {
                    Grid(byWindow(2, 3, 3), gap = 8.dp) {
                        group.options.forEach { option -> SingleOption(context, group, option, chooser) }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Kit.colors.border, RoundedCornerShape(12.dp))) {
                        group.options.forEachIndexed { i, option ->
                            if (i > 0) Separator()
                            CountedOption(context, option, chooser)
                        }
                    }
                }
            }
        }
    }
}

/** One of a group: tapped, it replaces whatever was ticked; tapped again, it is taken off. */
@Composable
private fun SingleOption(context: PosContext, group: PosOptionGroup, option: PosOption, chooser: PosChooser) {
    val chosen = (chooser.chosen[option.id] ?: 0) > 0
    val stopped = option.balance == 0.0
    ChoiceTile(
        chosen,
        { if (!stopped) context.instance.pickOption(group.id, option.id) },
        Modifier.alpha(if (stopped) 0.5f else 1f).testTag("pos:option-${option.id}"),
        padding = 12.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option.name, style = Kit.textStyles.label, maxLines = 2)
            Text(optionNote(context, option) ?: context.words.noExtra, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
        }
    }
}

/** An option of a group that takes several: its own − n +. */
@Composable
private fun CountedOption(context: PosContext, option: PosOption, chooser: PosChooser) {
    val chosen = chooser.chosen[option.id] ?: 0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("pos:option-${option.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.name, style = Kit.textStyles.label)
            optionNote(context, option)?.let { Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground) }
        }
        Row(Modifier.testTag("pos:option-${option.id}:amount")) {
            QtyStepper(
                chosen,
                { context.instance.setOption(option.id, it) },
                min = 0,
                max = if (option.balance == 0.0) chosen else option.maxAmount ?: Int.MAX_VALUE,
            )
        }
    }
}

/** What an option costs on top of the dish, and what the server says about it. */
private fun optionNote(context: PosContext, option: PosOption): String? {
    val words = context.words
    return listOfNotNull(
        if (option.price > 0) "+${context.money(option.price)}" else null,
        option.freeAmount.takeIf { it > 0 }?.let { words.free(it) },
        option.defaultAmount.takeIf { it > 0 }?.let { words.byDefault },
        if (option.balance == 0.0) words.stopped else option.balance.takeIf { it > 0 }?.let { "${words.left} ${it.toInt()}" },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
}
