package cx.m42.restoapp.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.theme.Tokens
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.platform.SystemBackHandler

private const val TAG = OrdersApp.ID

/** The list with its tabs, or one open order — switched on [OrdersInstance.open]. */
@Composable
internal fun OrdersScreen(instance: OrdersInstance) {
    val runtime = LocalAppRuntime.current
    val langTag by runtime.locale.langTag.collectAsState()
    val strings = remember(langTag) { runtime.locale.pick(OrdersStringTables, OrdersStringsRu) }
    val orders by instance.orders.collectAsState()
    val open by instance.open.collectAsState()

    val order = orders.firstOrNull { it.id == open }
    if (order != null) {
        SystemBackHandler(enabled = true) { instance.back() }
        OrderPage(order, instance, strings)
    } else {
        OrderList(orders, instance, strings)
    }
}

@Composable
private fun OrderList(orders: List<Order>, instance: OrdersInstance, strings: OrdersStrings) {
    val tokens = AppTheme
    val tab by instance.tab.collectAsState()
    val shown = orders.filter { tab.shows(it) }.sortedByDescending { it.createdAt }

    Column(modifier = Modifier.fillMaxSize().testTag("$TAG:list")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tab.entries.forEach { each ->
                val label = when (each) {
                    Tab.Active -> strings.active
                    Tab.Done -> strings.done
                    Tab.All -> strings.all
                }
                Chip(
                    label = label,
                    count = orders.count { each.shows(it) },
                    selected = each == tab,
                    tag = "$TAG:tab-${each.name.lowercase()}",
                    onClick = { instance.select(each) },
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            if (shown.isEmpty()) {
                Text(
                    text = if (tab == Tab.Active) strings.noActive else strings.noOrders,
                    style = tokens.body,
                    color = tokens.muted,
                    modifier = Modifier.padding(20.dp).testTag("$TAG:empty"),
                )
            }
            shown.forEachIndexed { index, each ->
                if (index > 0) RowDivider()
                OrderRow(each, strings, now = instance.now(), onClick = { instance.open(each.id) })
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = strings.demoHint,
                style = tokens.footnote,
                color = tokens.muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            AppButton(
                text = strings.resetDemo,
                onClick = { instance.resetDemo() },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("$TAG:reset"),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Chip(label: String, count: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) tokens.primary else tokens.surface)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = tokens.label.copy(fontWeight = FontWeight.Medium),
            color = if (selected) tokens.primaryForeground else tokens.foreground,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            style = tokens.label,
            color = if (selected) tokens.primaryForeground.copy(alpha = 0.7f) else tokens.muted,
        )
    }
}

@Composable
private fun OrderRow(order: Order, strings: OrdersStrings, now: Long, onClick: () -> Unit) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("$TAG:row-${order.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "№${order.number}",
                style = tokens.body.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.foreground,
                modifier = Modifier.weight(1f),
            )
            StatePill(order.state, strings)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = order.customerName + " · " + if (order.selfService) strings.pickup else strings.delivery,
            style = tokens.label,
            color = tokens.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row {
            Text(
                text = strings.dishes(order.dishesCount) + " · " + rub(order.total),
                style = tokens.label,
                color = tokens.muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = strings.ago((now - order.createdAt) / 60_000),
                style = tokens.label,
                color = tokens.muted,
            )
        }
    }
}

/** Colour is for state (Superizer tokens), and an order's state is exactly that. */
private fun Tokens.colorOf(state: OrderState): Color = when (state) {
    OrderState.ORDER -> accent
    OrderState.COOKING -> warning
    OrderState.ON_THE_WAY -> foreground
    OrderState.DONE -> success
    OrderState.REJECT -> danger
}

@Composable
private fun StatePill(state: OrderState, strings: OrdersStrings) {
    val tokens = AppTheme
    val color = tokens.colorOf(state)
    Text(
        text = strings.state(state),
        style = tokens.label.copy(fontWeight = FontWeight.Medium),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun OrderPage(order: Order, instance: OrdersInstance, strings: OrdersStrings) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.pageBackground)
            .verticalScroll(rememberScrollState())
            .testTag("$TAG:order"),
    ) {
        AppButton(
            text = "← " + strings.back,
            onClick = { instance.back() },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).testTag("$TAG:back"),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "№${order.number}",
                style = tokens.header,
                color = tokens.foreground,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.testTag("$TAG:state")) { StatePill(order.state, strings) }
        }
        Text(
            text = strings.created(strings.ago((instance.now() - order.createdAt) / 60_000)) + " · " +
                if (order.paid) strings.paid else strings.notPaid,
            style = tokens.label,
            color = tokens.muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))

        val next = order.next()
        if (next.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                next.forEachIndexed { index, to ->
                    AppButton(
                        text = strings.action(to),
                        onClick = { instance.move(order.id, to) },
                        variant = when {
                            to == OrderState.REJECT -> ButtonVariant.Destructive
                            index == 0 -> ButtonVariant.Default
                            else -> ButtonVariant.Outline
                        },
                        size = ButtonSize.Default,
                        modifier = Modifier.fillMaxWidth().testTag("$TAG:to-${to.name}"),
                    )
                }
            }
        }

        Section(title = strings.customer) {
            Line(order.customerName)
            RowDivider()
            Line(order.customerPhone)
        }

        Section(title = strings.receiving) {
            Line(if (order.selfService) strings.pickup else strings.delivery)
            order.address?.let {
                RowDivider()
                Line(it)
            }
        }

        order.comment?.let { comment ->
            Section(title = strings.comment) { Line(comment) }
        }

        Section(title = strings.contents) {
            order.lines.forEachIndexed { index, line ->
                if (index > 0) RowDivider()
                Line("${line.name} × ${line.amount}", rub(line.total))
            }
            if (order.deliveryCost > 0) {
                RowDivider()
                Line(strings.deliveryCost, rub(order.deliveryCost))
            }
            RowDivider()
            Line(strings.total, rub(order.total), strong = true)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Line(text: String, value: String? = null, strong: Boolean = false) {
    val tokens = AppTheme
    val style = if (strong) tokens.body.copy(fontWeight = FontWeight.SemiBold) else tokens.body
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = style, color = tokens.foreground, modifier = Modifier.weight(1f))
        if (value != null) Text(text = value, style = style, color = tokens.foreground)
    }
}
