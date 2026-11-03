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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
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
import cx.m42.restoapp.render.money
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.platform.SystemBackHandler

/*
 * The POS screen: the open orders of the point, and the receipt of one of them. Mobile first — one
 * column, one question at a time — because that is the shape a phone and a terminal in portrait both
 * take; the two-column layout of a tablet is slice D of order-entry.plan.md and will choose itself by
 * the width of the window, not by another screen.
 */

@Composable
internal fun PosScreen(instance: PosInstance) {
    val runtime = LocalAppRuntime.current
    val lang by runtime.locale.langTag.collectAsState()
    val words = remember(lang) { PosWords(lang) }
    // The shared components ask for the renderer's words — «Подтвердить», «Отмена».
    val shared = remember(lang) { RenderWords(lang) }
    val state by instance.state.collectAsState()
    val facts by instance.server.facts.collectAsState()
    val places by instance.server.places.collectAsState()
    val place by instance.server.place.collectAsState()
    val currency = facts?.currency
    LaunchedEffect(instance) { instance.start() }

    val tokens = AppTheme
    var sheet by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(tokens.pageBackground).testTag("pos")) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.notice?.let { Notice(it, Tones.WARNING, null, instance::dismissNotice, "pos:notice") }
            state.error?.let { Notice(it, Tones.DANGER, words.retry, { instance.reload() }, "pos:error") }

            // The point is the first thing chosen and it is always on screen: a terminal stands at
            // one, an operator switches. One point — no line to tap.
            if (places.size > 1 && place != null) {
                PlaceLine(place!!.title, place!!.address, { sheet = true }, "pos:place")
            }

            when {
                places.isEmpty() -> EmptyState(words.noPlaces, null, null, null, "pos:no-places")
                state.order != null -> {
                    SystemBackHandler(enabled = true) { instance.close() }
                    Receipt(instance, words, shared, currency, state)
                }
                else -> Drafts(instance, words, currency, state)
            }
        }
        ChoiceSheet(
            visible = sheet,
            title = words.places,
            choices = places.map { SheetChoice(it.id, it.title, it.address) },
            selected = place?.id,
            onDismiss = { sheet = false },
            onPick = { id ->
                sheet = false
                instance.server.selectPlace(id)
            },
            tag = "pos:places",
        )
    }
}

/** The open orders of this point, and the one button that starts another. */
@Composable
private fun Drafts(instance: PosInstance, words: PosWords, currency: String?, state: PosState) {
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
                        value = money(order.total, currency),
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

/** The receipt: what is in the order, what the server says it costs, and what can be done to it. */
@Composable
private fun Receipt(instance: PosInstance, words: PosWords, shared: RenderWords, currency: String?, state: PosState) {
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
        // Who it is for and where it is going — the lines a terminal needs at a glance.
        Card {
            FieldLine(words.customer, order.customerName ?: words.noCustomer, null, "pos:customer")
            order.table?.let { FieldLine(words.table, it, null, "pos:table") }
            order.cookingPlaceTitle
                ?.takeIf { order.cookingPlaceId != null && order.cookingPlaceId != order.placeId }
                ?.let { FieldLine(words.cooks, it, Tones.WARNING, "pos:cooks") }
        }

        if (order.lines.isEmpty()) {
            EmptyState(words.emptyReceipt, null, null, null, "pos:no-lines")
        } else {
            Card {
                order.lines.forEach { line ->
                    ListRow(
                        title = line.name,
                        subtitle = line.comment,
                        meta = "${line.amount} × ${money(line.price, currency)}",
                        value = money(line.total, currency),
                        valueTone = null,
                        badge = null,
                        badgeTone = null,
                        tag = "pos:line-${line.lineId}",
                        onClick = null,
                    )
                }
            }
        }

        // The totals as the server counted them, in its order: products, discount, delivery, total.
        Card {
            FieldLine(words.basket, money(order.basketTotal, currency), null, "pos:basket")
            if (order.discountTotal != 0.0) FieldLine(words.discount, money(-order.discountTotal, currency), Tones.SUCCESS, "pos:discount")
            if (order.deliveryCost != 0.0) FieldLine(words.delivery, money(order.deliveryCost, currency), null, "pos:delivery")
            if (order.bonusesTotal != 0.0) FieldLine(words.bonuses, money(-order.bonusesTotal, currency), Tones.SUCCESS, "pos:bonuses")
            FieldLine(words.total, money(order.total, currency), null, "pos:total")
        }
        order.message?.takeIf { it.isNotBlank() }?.let { Notice(it, Tones.INFO, null, null, "pos:message") }

        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The catalog is slice B: the button is here, and says so rather than pretending.
            AppButton(
                text = words.add,
                onClick = {},
                enabled = false,
                modifier = Modifier.weight(1f).testTag("pos:add"),
            )
        }
        Text(
            text = words.addLater,
            style = tokens.footnote,
            color = tokens.muted,
            modifier = Modifier.padding(horizontal = 20.dp).testTag("pos:add-later"),
        )
        Box(modifier = Modifier.padding(12.dp)) {
            ConfirmButton(
                text = words.drop,
                tone = Tones.DANGER,
                confirm = words.dropConfirm,
                enabled = !state.busy,
                words = shared,
                onClick = instance::cancel,
                tag = "pos:drop",
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A white block on the grey page — the renderer's card without its heading. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.surface),
    ) { content() }
}
