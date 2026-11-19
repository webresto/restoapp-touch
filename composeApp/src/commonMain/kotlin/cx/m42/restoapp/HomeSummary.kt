package cx.m42.restoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ClipboardList
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Receipt
import com.composables.icons.lucide.ShoppingBag
import com.composables.icons.lucide.Truck
import com.composables.icons.lucide.UtensilsCrossed
import com.composables.icons.lucide.Wallet
import cx.m42.restoapp.protocol.Tones
import cx.m42.superizer.app.AppId
import cx.m42.superizer.kit.BarChart
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.Grid
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Meter
import cx.m42.superizer.kit.NameMark
import cx.m42.superizer.kit.Page
import cx.m42.superizer.kit.PageHeader
import cx.m42.superizer.kit.Panel
import cx.m42.superizer.kit.Skeleton
import cx.m42.superizer.kit.Sparkline
import cx.m42.superizer.kit.Stat
import cx.m42.superizer.kit.StatusBadge
import cx.m42.superizer.kit.StatusDot
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.byWindow
import cx.m42.superizer.kit.color
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textStyles
import cx.m42.superizer.ui.screens.HomeScreen
import cx.m42.superizer.ui.shell.ShellScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/*
 * «Главная» — the prototype's day at a glance (`_plan/07-prototype-1to1.md`), from `home.summary` of the
 * server's app `home`. The record carries only what the person may see, and the screen draws only what
 * the record carries: no revenue for an operator, no orders for a stock manager — and what is left
 * closes up, without zeros and holes. A server that has no such app keeps the tiles.
 */

internal const val HOME_APP = "home"
private const val REFRESH_MS = 30_000L

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.rows(key: String): List<JsonObject>? = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }

/** 48320 → «48 320 ₽»; the currency is the server's. */
private fun money(amount: Int, currency: String?): String {
    val digits = kotlin.math.abs(amount).toString().reversed().chunked(3).joinToString(" ").reversed()
    val sign = if (amount < 0) "−" else ""
    return "$sign$digits " + when (currency) {
        null, "RUB" -> "₽"
        "USD" -> "$"
        "EUR" -> "€"
        else -> currency
    }
}

private fun toneOf(tone: String?): Tone = when (tone) {
    Tones.INFO -> Tone.Running
    Tones.WARNING -> Tone.Warn
    Tones.SUCCESS -> Tone.Success
    Tones.DANGER -> Tone.Danger
    Tones.MUTED -> Tone.Muted
    else -> Tone.Neutral
}


@Composable
internal fun HomeSummary(host: RestoAppHost, connection: Connection, shell: ShellScope) {
    val superizer = connection.superizer
    val lang by superizer.settings.langTag.collectAsState()
    val ru = lang.startsWith("ru")
    val server = remember(connection) { connection.session.bind(HOME_APP) }
    val app by server.app.collectAsState()
    val registered by superizer.registry.apps.collectAsState()
    if (app == null) {
        // The server has no «Главная» for this person (an old server, a role without a point): the tiles, as before.
        val home by superizer.home.collectAsState()
        HomeScreen(
            apps = home.mapNotNull { id -> registered.firstOrNull { it.id == id } },
            langTag = lang,
            onOpen = shell.openApp,
            onRemove = {},
            onAdd = shell.openCatalog,
        )
        return
    }
    val place by server.place.collectAsState()
    val facts by server.facts.collectAsState()
    val declaration by connection.session.declaration.collectAsState()
    var record by remember { mutableStateOf<JsonObject?>(null) }
    var failed by remember { mutableStateOf(false) }
    // A restart from «Обзор» of a cloud account reads the summary again at once.
    var reloads by remember { mutableStateOf(0) }
    LaunchedEffect(place?.id, lang) { record = null }
    LaunchedEffect(place?.id, lang, reloads) {
        while (true) {
            runCatching { server.call("home.summary", JsonObject(emptyMap())) }
                .onSuccess { result -> record = (result as? JsonObject)?.get("item")?.jsonObject; failed = false }
                .onFailure { failed = record == null }
            delay(REFRESH_MS)
        }
    }
    fun has(id: String) = registered.any { it.id.value == id }
    val name = declaration?.user?.fullName?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    val currency = facts?.currency
    // A Resto Cloud account's «Обзор» (_plan/08): the same record, of another kind, its own screen.
    record?.takeIf { it.text("kind") == "cloud" }?.let { cloud ->
        CloudOverview(connection, shell, cloud, ru, currency, facts?.credits) { reloads++ }
        return
    }
    Page(modifier = Modifier.testTag("home:summary")) {
        PageHeader(
            (if (ru) "Добрый день" else "Good day") + (name?.let { ", $it" } ?: ""),
            description = listOfNotNull(place?.title, place?.address).joinToString(" · ").ifEmpty { null },
            actions = if (has("pos")) ({
                Button(if (ru) "Новый заказ" else "New order", { shell.openApp(AppId("pos")) }, Modifier.testTag("home:new-order"), leadingIcon = Lucide.Plus)
            }) else null,
        )
        val data = record
        if (data == null) {
            if (failed) {
                Text(if (ru) "Не получилось загрузить сводку — попробуем ещё раз через полминуты" else "Could not load the summary — trying again in half a minute", color = Kit.colors.mutedForeground)
            } else {
                Grid(byWindow(2, 4, 4)) { repeat(4) { Skeleton(Modifier.fillMaxWidth().height(118.dp), RoundedCornerShape(14.dp)) } }
                Skeleton(Modifier.fillMaxWidth().height(260.dp), RoundedCornerShape(14.dp))
            }
            return@Page
        }
        val revenue = data.int("revenue")
        val orders = data.int("orders")
        val check = data.int("averageCheck")
        val stop = data.int("stop")
        val tiles = listOfNotNull(revenue, orders, check, stop).size
        if (tiles > 0) Grid(byWindow(tiles.coerceIn(1, 2), tiles, tiles), gap = byWindow(12.dp, 16.dp, 16.dp)) {
            if (revenue != null) {
                val delta = data.int("revenueDelta")
                val series = data.rows("revenueByHour")?.mapNotNull { it.int("y")?.toFloat() }.orEmpty()
                Stat(
                    if (ru) "Выручка сегодня" else "Revenue today", money(revenue, currency), Modifier.testTag("home:stat-revenue"),
                    hint = delta?.let { (if (it >= 0) "+" else "−") + kotlin.math.abs(it) + (if (ru) "% к прошлой неделе" else "% vs last week") },
                    icon = Lucide.Wallet, tone = if ((delta ?: 0) >= 0) Tone.Success else Tone.Danger,
                    extra = if (series.size >= 2) ({ val top = series.max().coerceAtLeast(1f); Sparkline(series.map { it / top }, Modifier.fillMaxWidth().height(28.dp), color = Tone.Success.color()) }) else null,
                )
            }
            if (orders != null) {
                val active = data.int("ordersActive")
                Stat(
                    if (ru) "Заказов" else "Orders", orders.toString(), Modifier.testTag("home:stat-orders"),
                    hint = active?.let { if (ru) "$it сейчас в работе" else "$it in progress" }, icon = Lucide.ClipboardList,
                    onClick = if (has("orders")) ({ shell.openApp(AppId("orders")) }) else null,
                )
            }
            if (check != null) {
                val delta = data.int("averageCheckDelta")
                Stat(
                    if (ru) "Средний чек" else "Average check", money(check, currency), Modifier.testTag("home:stat-check"),
                    hint = delta?.let { (if (it >= 0) "+" else "−") + money(kotlin.math.abs(it), currency) + (if (ru) " к обычному" else " vs usual") }, icon = Lucide.Receipt,
                )
            }
            if (stop != null) {
                val low = data.int("low")
                Stat(
                    if (ru) "Стоп-лист" else "Stop list", if (ru) "$stop ${plural(stop, "блюдо", "блюда", "блюд")}" else "$stop",
                    Modifier.testTag("home:stat-stop"),
                    hint = low?.let { if (ru) "ещё $it ${plural(it, "заканчивается", "заканчиваются", "заканчиваются")}" else "$it more running low" },
                    icon = Lucide.UtensilsCrossed, tone = if (stop > 0) Tone.Warn else Tone.Neutral,
                    onClick = if (has("dishes")) ({ shell.openApp(AppId("dishes")) }) else null,
                )
            }
        }
        val byHour = data.rows("revenueByHour")?.takeIf { it.size >= 2 }
        val kitchen = data.rows("kitchen")
        Side(byHour != null, kitchen != null, 3, 2,
            first = {
                Panel(
                    title = if (ru) "Выручка по часам" else "Revenue by hour", description = if (ru) "Тысячи, текущий час выделен" else "Thousands, the current hour is marked",
                    action = { revenue?.let { Text(money(it, currency), style = Kit.textStyles.label) } },
                ) {
                    BarChart(byHour!!.map { (it.int("y") ?: 0) / 1000f }, byHour.map { it.text("x").orEmpty() }, height = byWindow(130.dp, 150.dp, 150.dp), unit = if (ru) "к" else "k")
                }
            },
            second = {
                Panel(title = if (ru) "Кухня сейчас" else "The kitchen now", description = if (ru) "Заказы по этапам" else "Orders by stage", gap = 14.dp) {
                    val most = kitchen!!.maxOfOrNull { it.int("count") ?: 0 }?.coerceAtLeast(1) ?: 1
                    kitchen.forEach { stage ->
                        val tone = toneOf(stage.text("tone"))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(if (has("orders")) Modifier.kitClickable { shell.openApp(AppId("orders")) } else Modifier),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusDot(tone, size = 7.dp)
                                Text(stage.text("title").orEmpty(), Modifier.weight(1f), style = Kit.textStyles.label.copy(fontWeight = FontWeight.Normal))
                                Text((stage.int("count") ?: 0).toString(), style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold))
                            }
                            Meter((stage.int("count") ?: 0) / most.toFloat(), color = tone.color(), height = 5.dp)
                        }
                    }
                }
            },
        )
        val active = data.rows("active")
        val popular = data.rows("popular")?.takeIf { it.isNotEmpty() }
        Side(active != null, popular != null, 3, 2,
            first = {
                Panel(
                    title = if (ru) "В работе" else "In progress", flush = true,
                    action = {
                        if (has("orders")) Row(
                            Modifier.clip(RoundedCornerShape(6.dp)).kitClickable(hover = false) { shell.openApp(AppId("orders")) }.padding(horizontal = 6.dp, vertical = 4.dp).testTag("home:all-orders"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(if (ru) "Все заказы" else "All orders", style = Kit.textStyles.label, color = Kit.palette.onBrandSoft)
                            Icon(Lucide.ArrowRight, contentDescription = null, size = 14.dp, tint = Kit.palette.onBrandSoft)
                        }
                    },
                ) {
                    if (active!!.isEmpty()) {
                        Line()
                        Text(if (ru) "Сейчас заказов в работе нет" else "No orders in progress", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = Kit.colors.mutedForeground)
                    }
                    active.forEach { order ->
                        Line()
                        Row(
                            Modifier.fillMaxWidth().then(if (has("orders")) Modifier.kitClickable { shell.openApp(AppId("orders")) } else Modifier)
                                .padding(horizontal = byWindow(16.dp, 20.dp, 20.dp), vertical = 12.dp).testTag("home:order-${order.text("id")}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Kit.colors.secondary), contentAlignment = Alignment.Center) {
                                Icon(if (order.text("method") == "pickup") Lucide.ShoppingBag else Lucide.Truck, contentDescription = null, size = 17.dp, tint = Kit.colors.mutedForeground)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(order.text("title").orEmpty(), style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold))
                                    order.text("stateTitle")?.let { StatusBadge(it, toneOf(order.text("tone"))) }
                                }
                                val ago = order.int("minutes")?.let { if (ru) "$it мин назад" else "$it min ago" }
                                Text(listOfNotNull(order.text("summary"), ago).joinToString(" · "), style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            order.int("total")?.let { Text(money(it, currency), style = Kit.textStyles.label) }
                        }
                    }
                }
            },
            second = {
                Panel(title = if (ru) "Популярное сегодня" else "Popular today", flush = true) {
                    popular!!.forEachIndexed { i, dish ->
                        Line()
                        Row(
                            Modifier.fillMaxWidth().then(if (has("dishes")) Modifier.kitClickable { shell.openApp(AppId("dishes")) } else Modifier)
                                .padding(horizontal = byWindow(16.dp, 20.dp, 20.dp), vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("${i + 1}", Modifier.width(14.dp), style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
                            NameMark(dish.text("group") ?: dish.text("title").orEmpty(), 32.dp)
                            Text(dish.text("title").orEmpty(), Modifier.weight(1f), style = Kit.textStyles.label.copy(fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${dish.int("sold") ?: 0}" + if (ru) " шт" else "", style = Kit.textStyles.label, color = Kit.colors.mutedForeground)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Kit.colors.border))
}

/** Two panels side by side on a desktop when the person has both; the one they have — alone, in the full width. */
@Composable
private fun Side(hasFirst: Boolean, hasSecond: Boolean, spanFirst: Int, spanSecond: Int, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    when {
        hasFirst && hasSecond -> Grid(byWindow(1, 1, spanFirst + spanSecond), spans = listOf(spanFirst, spanSecond)) {
            first()
            second()
        }
        hasFirst -> first()
        hasSecond -> second()
    }
}
