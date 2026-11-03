package cx.m42.restoapp.pos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.render.ListRow
import cx.m42.restoapp.render.Notice
import cx.m42.restoapp.render.SearchBox
import cx.m42.restoapp.render.money
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.restoapp.render.SwitchControl

/*
 * The questions of the checkout, one sheet each: how the food is handed over, who the guest is,
 * where it goes, when, what it is paid with, a promo code, the details. Every answer is a call —
 * the server writes it on the order and answers with the whole receipt — and a sheet closes only
 * when the server took it.
 */

@Composable
internal fun BoxScope.PosSheets(context: PosContext) {
    val state = context.state
    val sheet = state.sheet ?: return
    val order = state.order ?: return
    val words = context.words
    val title = when (sheet) {
        PosSheet.Service -> words.receiving
        PosSheet.Guest -> words.guest
        PosSheet.Clients -> words.findClient
        PosSheet.Address -> words.address
        PosSheet.Time -> words.time
        PosSheet.Payment -> words.payment
        PosSheet.Promocode -> words.promocode
        PosSheet.Details -> words.details
        PosSheet.LineComment -> words.comment
    }
    Sheet(title = title, onDismiss = context.instance::closeSheet, tag = "pos:sheet-${sheet.name.lowercase()}") {
        state.message?.takeIf { it.tone == Tones.DANGER }?.let { Notice(it.text, it.tone, null, null, "pos:sheet-message") }
        when (sheet) {
            PosSheet.Service -> ServiceSheet(context, order)
            PosSheet.Guest -> GuestSheet(context, order)
            PosSheet.Clients -> ClientsSheet(context)
            PosSheet.Address -> AddressSheet(context, order)
            PosSheet.Time -> TimeSheet(context)
            PosSheet.Payment -> PaymentSheet(context, order)
            PosSheet.Promocode -> PromocodeSheet(context, order)
            PosSheet.Details -> DetailsSheet(context, order)
            PosSheet.LineComment -> LineCommentSheet(context, order)
        }
    }
}

/** Delivery, pickup or the dining room — and the table when the order is served at one. */
@Composable
private fun ServiceSheet(context: PosContext, order: PosOrder) {
    val words = context.words
    var table by remember(order.id) { mutableStateOf(order.table.orEmpty()) }
    context.state.options.service.forEach { option ->
        ListRow(
            title = words.serviceType(option.id),
            subtitle = option.reason,
            meta = null,
            value = if (order.serviceType == option.id) "✓" else null,
            valueTone = null,
            badge = null,
            badgeTone = null,
            tag = "pos:service-${option.id}",
            onClick = if (option.available) {
                { context.instance.setService(option.id, table.takeIf { option.id == PosOrder.DINE_IN }) }
            } else {
                null
            },
        )
    }
    if (order.serviceType == PosOrder.DINE_IN) {
        Spacer(Modifier.heightIn(min = 8.dp))
        Field(words.table, table, { table = it }, "pos:table-input")
        AppButton(
            text = words.save,
            onClick = { context.instance.setTable(table) },
            enabled = !context.state.busy && table.isNotBlank(),
            size = ButtonSize.Sm,
            modifier = Modifier.padding(top = 8.dp).testTag("pos:table-save"),
        )
    }
}

/** The guest: a name and a number, with an account made on the spot when asked. */
@Composable
private fun GuestSheet(context: PosContext, order: PosOrder) {
    val words = context.words
    var name by remember(order.id) { mutableStateOf(order.customerName.orEmpty()) }
    var phone by remember(order.id) { mutableStateOf(order.customerPhone.orEmpty()) }
    var create by remember(order.id) { mutableStateOf(false) }
    Field(words.name, name, { name = it }, "pos:guest-name")
    Spacer(Modifier.heightIn(min = 8.dp))
    Field(words.phone, phone, { phone = it }, "pos:guest-phone")
    Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = words.createClient, style = AppTheme.body, color = AppTheme.foreground, modifier = Modifier.weight(1f))
        SwitchControl(checked = create, label = words.createClient, enabled = !context.state.busy, onChange = { create = it }, tag = "pos:guest-create")
    }
    Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton(
            text = words.save,
            onClick = { context.instance.setCustomer(name.trim(), phone.trim(), create) },
            enabled = !context.state.busy && name.isNotBlank() && phone.isNotBlank(),
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("pos:guest-save"),
        )
        AppButton(
            text = words.findClient,
            onClick = { context.instance.openSheet(PosSheet.Clients) },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("pos:guest-find"),
        )
    }
}

/** The customers of this server, searched by the digits of a number or by name. */
@Composable
private fun ClientsSheet(context: PosContext) {
    val words = context.words
    var query by remember { mutableStateOf("") }
    SearchBox(query, words.searchClient, { typed ->
        query = typed
        context.instance.searchClients(typed)
    }, "pos:clients-search")
    context.state.options.clients.forEach { client ->
        ListRow(
            title = client.title,
            subtitle = client.phone,
            meta = null,
            value = client.orderCount.takeIf { it > 0 }?.toString(),
            valueTone = null,
            badge = null,
            badgeTone = null,
            tag = "pos:client-${client.id}",
            onClick = { context.instance.setClient(client.id) },
        )
    }
}

/** Where a delivery goes: a saved address of the customer, or one typed here. */
@Composable
private fun AddressSheet(context: PosContext, order: PosOrder) {
    val words = context.words
    val options = context.state.options
    var city by remember(order.id) { mutableStateOf(options.cities.firstOrNull()?.name.orEmpty()) }
    var street by remember(order.id) { mutableStateOf("") }
    var home by remember(order.id) { mutableStateOf("") }
    var apartment by remember(order.id) { mutableStateOf("") }

    if (options.savedAddresses.isNotEmpty()) {
        Text(text = words.savedAddresses, style = AppTheme.footnote, color = AppTheme.muted)
        options.savedAddresses.forEach { saved ->
            ListRow(
                title = saved.formatted,
                subtitle = null,
                meta = null,
                value = null,
                valueTone = null,
                badge = null,
                badgeTone = null,
                tag = "pos:saved-${saved.id}",
                // A saved line is a street and a house already: it goes in as the whole address.
                onClick = { context.instance.setAddress(city, saved.formatted, "", null, null) },
            )
        }
        Spacer(Modifier.heightIn(min = 8.dp))
    }
    if (options.cities.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            options.cities.forEach { option ->
                AppButton(
                    text = option.name,
                    onClick = { city = option.name },
                    variant = if (city == option.name) ButtonVariant.Default else ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("pos:city-${option.id}"),
                )
            }
        }
    }
    Field(words.street, street, { typed ->
        street = typed
        context.instance.searchStreets(city, typed)
    }, "pos:address-street")
    options.streets.take(4).forEach { suggestion ->
        ListRow(
            title = suggestion.name,
            subtitle = null,
            meta = null,
            value = null,
            valueTone = null,
            badge = null,
            badgeTone = null,
            tag = "pos:street-${suggestion.id}",
            onClick = { street = suggestion.name },
        )
    }
    Spacer(Modifier.heightIn(min = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { Field(words.house, home, { home = it }, "pos:address-home") }
        Box(Modifier.weight(1f)) { Field(words.apartment, apartment, { apartment = it }, "pos:address-apartment") }
    }
    AppButton(
        text = words.save,
        onClick = { context.instance.setAddress(city, street.trim(), home.trim(), apartment.trim(), null) },
        enabled = !context.state.busy && street.isNotBlank() && home.isNotBlank(),
        size = ButtonSize.Sm,
        modifier = Modifier.padding(top = 10.dp).testTag("pos:address-save"),
    )
}

/** When: as soon as possible, by one of the kitchen's slots, or within so many minutes. */
@Composable
private fun TimeSheet(context: PosContext) {
    val words = context.words
    val options = context.state.options.time
    var wait by remember { mutableStateOf("") }
    if (options.asap) {
        ListRow(
            title = words.asap,
            subtitle = if (options.minCookingMinutes > 0) "${words.cooking} ~${options.minCookingMinutes} ${words.minutes}" else null,
            meta = null, value = null, valueTone = null, badge = null, badgeTone = null,
            tag = "pos:time-asap",
            onClick = { context.instance.setTime("asap") },
        )
    }
    options.slots.take(8).forEach { slot ->
        val from = slot.firstOrNull() ?: return@forEach
        ListRow(
            title = words.clock(from),
            subtitle = null, meta = null, value = null, valueTone = null, badge = null, badgeTone = null,
            tag = "pos:time-slot-$from",
            onClick = { context.instance.setTime("time", date = from) },
        )
    }
    Spacer(Modifier.heightIn(min = 8.dp))
    Field("${words.maxWait}, ${words.minutes}", wait, { wait = it.filter(Char::isDigit) }, "pos:time-wait")
    AppButton(
        text = words.save,
        onClick = { context.instance.setTime("maxWait", maxWaitMinutes = wait.toIntOrNull()) },
        enabled = !context.state.busy && (wait.toIntOrNull() ?: 0) > 0,
        size = ButtonSize.Sm,
        modifier = Modifier.padding(top = 8.dp).testTag("pos:time-wait-save"),
    )
}

/** What it is paid with; online payment is listed and refused, with the reason said. */
@Composable
private fun PaymentSheet(context: PosContext, order: PosOrder) {
    context.state.options.payments.forEach { method ->
        ListRow(
            title = method.title,
            subtitle = if (method.available) null else context.words.addLater,
            meta = null,
            value = if (order.paymentMethodId == method.id) "✓" else null,
            valueTone = null,
            badge = null,
            badgeTone = null,
            tag = "pos:payment-${method.id}",
            onClick = if (method.available) ({ context.instance.setPayment(method.id) }) else null,
        )
    }
}

@Composable
private fun PromocodeSheet(context: PosContext, order: PosOrder) {
    val words = context.words
    var code by remember(order.id) { mutableStateOf(order.promotionCode.orEmpty()) }
    Field(words.promocode, code, { code = it }, "pos:promocode-input")
    Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton(
            text = words.save,
            onClick = { context.instance.setPromocode(code.trim()) },
            enabled = !context.state.busy && code.isNotBlank(),
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("pos:promocode-save"),
        )
        AppButton(
            text = words.clear,
            onClick = { context.instance.setPromocode(null) },
            enabled = !context.state.busy && order.promotionCode != null,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("pos:promocode-clear"),
        )
    }
}

@Composable
private fun DetailsSheet(context: PosContext, order: PosOrder) {
    val words = context.words
    var comment by remember(order.id) { mutableStateOf(order.comment.orEmpty()) }
    var persons by remember(order.id) { mutableStateOf(order.personsCount?.toString().orEmpty()) }
    Field(words.comment, comment, { comment = it }, "pos:details-comment")
    Spacer(Modifier.heightIn(min = 8.dp))
    Field(words.persons, persons, { persons = it.filter(Char::isDigit) }, "pos:details-persons")
    AppButton(
        text = words.save,
        onClick = { context.instance.setDetails(comment.trim(), persons.toIntOrNull()) },
        enabled = !context.state.busy,
        size = ButtonSize.Sm,
        modifier = Modifier.padding(top = 10.dp).testTag("pos:details-save"),
    )
}

@Composable
private fun LineCommentSheet(context: PosContext, order: PosOrder) {
    val lineId = context.state.lineId ?: return
    val line = order.lines.firstOrNull { it.lineId == lineId } ?: return
    val words = context.words
    var comment by remember(lineId) { mutableStateOf(line.comment.orEmpty()) }
    Text(text = line.name, style = AppTheme.body.copy(fontWeight = FontWeight.SemiBold), color = AppTheme.foreground)
    Spacer(Modifier.heightIn(min = 8.dp))
    Field(words.comment, comment, { comment = it }, "pos:line-comment-input")
    AppButton(
        text = words.save,
        onClick = { context.instance.setLineComment(lineId, comment.trim()) },
        enabled = !context.state.busy,
        size = ButtonSize.Sm,
        modifier = Modifier.padding(top = 10.dp).testTag("pos:line-comment-save"),
    )
}

/** A labelled text field, as the renderer's form draws one. */
@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, tag: String) {
    val tokens = AppTheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = tokens.footnote, color = tokens.muted)
        AppTextField(value = value, onValueChange = onChange, placeholder = label, modifier = Modifier.fillMaxWidth().testTag(tag))
    }
}

/**
 * A sheet from the bottom over a scrim — the shape of the point switcher, with whatever a question
 * needs inside it.
 */
@Composable
private fun BoxScope.Sheet(title: String, onDismiss: () -> Unit, tag: String, content: @Composable () -> Unit) {
    val tokens = AppTheme
    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.foreground.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(tokens.background)
            .padding(16.dp)
            .testTag(tag),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(text = title, style = tokens.header, color = tokens.foreground, modifier = Modifier.weight(1f))
            AppButton(
                text = "✕",
                onClick = onDismiss,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$tag:close"),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            content()
        }
    }
}
