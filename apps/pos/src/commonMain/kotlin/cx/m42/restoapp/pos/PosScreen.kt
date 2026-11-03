package cx.m42.restoapp.pos

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.render.Badge
import cx.m42.restoapp.render.ChoiceSheet
import cx.m42.restoapp.render.ConfirmButton
import cx.m42.restoapp.render.EmptyState
import cx.m42.restoapp.render.FieldLine
import cx.m42.restoapp.render.ListRow
import cx.m42.restoapp.render.Notice
import cx.m42.restoapp.render.PlaceLine
import cx.m42.restoapp.render.RenderWords
import cx.m42.restoapp.render.SheetChoice
import cx.m42.restoapp.render.StepperRow
import cx.m42.restoapp.render.money
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.platform.SystemBackHandler

/*
 * The POS screen: the open orders of the point, the receipt of one of them, the catalog behind the
 * «+», and the questions of the checkout as sheets. Mobile first — one column, one question at a
 * time — because that is the shape a phone and a terminal in portrait both take; the two-column
 * layout of a tablet is slice D of order-entry.plan.md and will choose itself by the width of the
 * window, not by another screen.
 */

/** Everything a screen of the POS needs, gathered once. */
internal class PosContext(
    val instance: PosInstance,
    val state: PosState,
    val words: PosWords,
    val shared: RenderWords,
    val currency: String?,
)

@Composable
internal fun PosScreen(instance: PosInstance) {
    val runtime = LocalAppRuntime.current
    val lang by runtime.locale.langTag.collectAsState()
    val words = remember(lang) { PosWords(lang) }
    val shared = remember(lang) { RenderWords(lang) }
    val state by instance.state.collectAsState()
    val facts by instance.server.facts.collectAsState()
    val places by instance.server.places.collectAsState()
    val place by instance.server.place.collectAsState()
    LaunchedEffect(instance) { instance.start() }

    val context = PosContext(instance, state, words, shared, facts?.currency)
    val tokens = AppTheme
    var pointSheet by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(tokens.pageBackground).testTag("pos")) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.notice?.let { Notice(it, Tones.WARNING, null, instance::dismissNotice, "pos:notice") }
            state.error?.let { Notice(it, Tones.DANGER, words.retry, { instance.reload() }, "pos:error") }

            // The point is the first thing chosen and it is always on screen: a terminal stands at
            // one, an operator switches. One point — no line to tap.
            if (places.size > 1 && place != null && state.placed == null) {
                PlaceLine(place!!.title, place!!.address, { pointSheet = true }, "pos:place")
            }

            when {
                places.isEmpty() -> EmptyState(words.noPlaces, null, null, null, "pos:no-places")
                state.placed != null -> {
                    SystemBackHandler(enabled = true) { instance.done() }
                    Placed(context)
                }
                state.catalog != null -> {
                    SystemBackHandler(enabled = true) { instance.catalogBack() }
                    CatalogScreen(context)
                }
                state.order != null -> {
                    SystemBackHandler(enabled = true) { instance.close() }
                    Receipt(context)
                }
                else -> Drafts(context)
            }
        }
        PosSheets(context)
        ChoiceSheet(
            visible = pointSheet,
            title = words.places,
            choices = places.map { SheetChoice(it.id, it.title, it.address) },
            selected = place?.id,
            onDismiss = { pointSheet = false },
            onPick = { id ->
                pointSheet = false
                instance.server.selectPlace(id)
            },
            tag = "pos:places",
        )
    }
}

/** The open orders of this point, and the one button that starts another. */
@Composable
private fun Drafts(context: PosContext) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val tokens = AppTheme
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = words.drafts, style = tokens.header, color = tokens.foreground, modifier = Modifier.weight(1f))
            AppButton(
                text = words.newOrder,
                onClick = { instance.newOrder() },
                enabled = !state.busy,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("pos:new"),
            )
        }
        when {
            state.drafts.isNotEmpty() -> Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                state.drafts.forEach { order ->
                    ListRow(
                        title = "№ ${order.number}",
                        subtitle = listOfNotNull(order.customerName ?: words.noCustomer, words.serviceType(order.serviceType)).joinToString(" · "),
                        meta = words.positions(order.dishesCount),
                        value = money(order.total, context.currency),
                        valueTone = null,
                        badge = null,
                        badgeTone = null,
                        tag = "pos:draft-${order.id}",
                        onClick = { instance.open(order.id) },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
            state.loading && !state.loaded -> Text(
                text = words.loading,
                style = tokens.label,
                color = tokens.muted,
                modifier = Modifier.padding(20.dp).testTag("pos:loading"),
            )
            else -> EmptyState(words.noDrafts, words.noDraftsHint, null, null, "pos:empty")
        }
    }
}

/** The receipt: what is in the order, what the server says it costs, and what is left to answer. */
@Composable
private fun Receipt(context: PosContext) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val order = state.order ?: return
    val tokens = AppTheme
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("pos:receipt")) {
        AppButton(
            text = words.back,
            onClick = instance::close,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.padding(8.dp).testTag("pos:back"),
        )
        Row(modifier = Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "№ ${order.number}",
                style = tokens.header,
                color = tokens.foreground,
                modifier = Modifier.weight(1f).testTag("pos:number"),
            )
            Badge(words.serviceType(order.serviceType), Tones.INFO, Modifier.testTag("pos:service"))
        }

        // The lines, each with its −/+ and what the server charges for it.
        if (order.lines.isEmpty()) {
            EmptyState(words.emptyReceipt, null, null, null, "pos:no-lines")
        } else {
            PosCard {
                order.lines.forEachIndexed { index, line ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    LineRow(context, line)
                }
            }
        }

        AppButton(
            text = words.add,
            onClick = { instance.openCatalog() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("pos:add"),
        )

        // The questions of the checkout: each a line with its answer, each a sheet when tapped.
        PosCard {
            StepLine(context, words.receiving, receivingOf(context, order), PosSheet.Service, "pos:step-service")
            StepLine(context, words.guest, order.customerName ?: words.noCustomer, PosSheet.Guest, "pos:step-guest")
            if (order.serviceType == PosOrder.DELIVERY) {
                StepLine(context, words.address, order.address ?: words.notSet, PosSheet.Address, "pos:step-address")
            }
            StepLine(context, words.time, timeOf(context, order), PosSheet.Time, "pos:step-time")
            StepLine(context, words.payment, order.paymentMethodTitle ?: words.notSet, PosSheet.Payment, "pos:step-payment")
            StepLine(context, words.promocode, order.promotionCode ?: words.notSet, PosSheet.Promocode, "pos:step-promocode")
            StepLine(context, words.details, order.comment ?: words.notSet, PosSheet.Details, "pos:step-details")
        }

        // The totals as the server counted them, in its order: products, discount, delivery, total.
        PosCard {
            FieldLine(words.basket, money(order.basketTotal, context.currency), null, "pos:basket")
            if (order.discountTotal != 0.0) FieldLine(words.discount, money(-order.discountTotal, context.currency), Tones.SUCCESS, "pos:discount")
            if (order.deliveryCost != 0.0) FieldLine(words.delivery, money(order.deliveryCost, context.currency), null, "pos:delivery")
            if (order.bonusesTotal != 0.0) FieldLine(words.bonuses, money(-order.bonusesTotal, context.currency), Tones.SUCCESS, "pos:bonuses")
            FieldLine(words.total, money(order.total, context.currency), null, "pos:total")
        }
        order.cookingPlaceTitle
            ?.takeIf { order.cookingPlaceId != null && order.cookingPlaceId != order.placeId }
            ?.let { PosCard { FieldLine(words.cooks, it, Tones.WARNING, "pos:cooks") } }

        state.message?.let { Notice(it.text, it.tone, null, instance::dismissMessage, "pos:message") }
        order.message?.takeIf { it.isNotBlank() && it != state.message?.text }?.let { Notice(it, Tones.INFO, null, null, "pos:server-note") }

        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                text = words.check,
                onClick = instance::check,
                enabled = !state.busy && order.canCheck,
                variant = ButtonVariant.Outline,
                modifier = Modifier.weight(1f).testTag("pos:check"),
            )
            if (order.canPlace) {
                ConfirmButton(
                    text = words.placeOrder,
                    tone = Tones.SUCCESS,
                    confirm = words.placeConfirm,
                    enabled = !state.busy,
                    words = context.shared,
                    onClick = instance::place,
                    // Not «pos:place»: that is the point line at the top of the screen.
                    tag = "pos:place-order",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(modifier = Modifier.padding(12.dp)) {
            ConfirmButton(
                text = words.drop,
                tone = Tones.DANGER,
                confirm = words.dropConfirm,
                enabled = !state.busy,
                words = context.shared,
                onClick = instance::cancel,
                tag = "pos:drop",
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One line of the receipt: the product, its −/+, and what it costs. */
@Composable
private fun LineRow(context: PosContext, line: PosLine) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val tokens = AppTheme
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).testTag("pos:line-${line.lineId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = line.name, style = tokens.body, color = tokens.foreground)
                val note = listOfNotNull(
                    line.comment,
                    if (line.hasOptions) words.withOptions else null,
                    "${money(line.price, context.currency)}${line.oldPrice?.let { " · ${money(it, context.currency)}" } ?: ""}",
                ).joinToString(" · ")
                Text(text = note, style = tokens.footnote, color = tokens.muted)
            }
            Text(
                text = money(line.total, context.currency),
                style = tokens.body,
                color = tokens.foreground,
                modifier = Modifier.padding(start = 8.dp).testTag("pos:line-${line.lineId}:total"),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperRow(
                display = line.amount.toString(),
                tone = null,
                onMinus = { instance.setLine(line.lineId, line.amount - 1) },
                onPlus = { instance.setLine(line.lineId, line.amount + 1) },
                enabled = !state.busy,
                tag = "pos:line-${line.lineId}:amount",
            )
            AppButton(
                text = words.comment,
                onClick = { instance.openSheet(PosSheet.LineComment, line.lineId) },
                enabled = !state.busy,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.padding(start = 8.dp).testTag("pos:line-${line.lineId}:comment"),
            )
        }
    }
}

/** A question of the checkout: what it is, what it is answered with, and a tap to change it. */
@Composable
private fun StepLine(context: PosContext, label: String, value: String, sheet: PosSheet, tag: String) {
    ListRow(
        title = label,
        subtitle = null,
        meta = null,
        value = value,
        valueTone = null,
        badge = null,
        badgeTone = null,
        tag = tag,
        onClick = { context.instance.openSheet(sheet) },
    )
}

/** The «accepted» screen: the number to say out loud, and the way back. */
@Composable
private fun Placed(context: PosContext) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val order = state.placed ?: return
    val tokens = AppTheme
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).testTag("pos:placed"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text(text = words.accepted, style = tokens.header, color = tokens.foreground)
        Text(text = "№ ${order.number}", style = tokens.header, color = tokens.foreground, modifier = Modifier.testTag("pos:placed-number"))
        Text(
            text = listOfNotNull(order.customerName, words.serviceType(order.serviceType), money(order.total, context.currency)).joinToString(" · "),
            style = tokens.label,
            color = tokens.muted,
        )
        state.message?.let { Notice(it.text, it.tone, null, null, "pos:placed-message") }
        AppButton(
            text = words.newOrderAgain,
            onClick = { instance.newOrder() },
            modifier = Modifier.fillMaxWidth().testTag("pos:placed-new"),
        )
        AppButton(
            text = words.toDrafts,
            onClick = instance::done,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth().testTag("pos:placed-done"),
        )
    }
}

/** What the «Получение» line reads: the way, and the table when there is one. */
private fun receivingOf(context: PosContext, order: PosOrder): String =
    listOfNotNull(
        context.words.serviceType(order.serviceType),
        order.table?.let { "${context.words.table} $it" },
    ).joinToString(" · ")

/** What the «Время» line reads. */
private fun timeOf(context: PosContext, order: PosOrder): String = when {
    order.maxWaitMinutes != null -> "${context.words.maxWait} ${order.maxWaitMinutes} ${context.words.minutes}"
    order.date != null -> context.words.clock(order.date)
    else -> context.words.asap
}

/** A white block on the grey page — the renderer's card without its heading. */
@Composable
internal fun PosCard(content: @Composable () -> Unit) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.surface),
    ) { content() }
}
