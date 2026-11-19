package cx.m42.restoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Receipt
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Wallet
import cx.m42.restoapp.protocol.Controls
import cx.m42.restoapp.protocol.CreditRate
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.protocol.Views
import cx.m42.restoapp.protocol.actions
import cx.m42.superizer.app.AppId
import cx.m42.superizer.kit.BarChart
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Grid
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.IconTile
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.NameMark
import cx.m42.superizer.kit.Page
import cx.m42.superizer.kit.PageHeader
import cx.m42.superizer.kit.Panel
import cx.m42.superizer.kit.ShareBar
import cx.m42.superizer.kit.Sparkline
import cx.m42.superizer.kit.Stat
import cx.m42.superizer.kit.StatusBadge
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.byWindow
import cx.m42.superizer.kit.color
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.softColor
import cx.m42.superizer.kit.textStyles
import cx.m42.superizer.ui.shell.ShellScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * «Обзор» of a Resto Cloud account (`_plan/08-cloud-screens.md`) — the prototype's `ui/screens/Home.kt` from
 * `home.summary` of kind `cloud`: the greeting, what needs attention with a restart one tap away, the four numbers
 * (running, the money left, the spend, today's orders), the services with the shape of their load, and on a wide
 * window the thirty days of spend with whose it was. Only what the record carries is drawn; the rest closes up.
 */

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.rows(key: String): List<JsonObject>? = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }

private fun toneOf(tone: String?): Tone = when (tone) {
    Tones.INFO -> Tone.Running
    Tones.WARNING -> Tone.Warn
    Tones.SUCCESS -> Tone.Success
    Tones.DANGER -> Tone.Danger
    Tones.MUTED -> Tone.Muted
    else -> Tone.Neutral
}

@Composable
internal fun CloudOverview(connection: Connection, shell: ShellScope, data: JsonObject, ru: Boolean, currency: String?, rate: CreditRate?, reload: () -> Unit) {
    val registered by connection.superizer.registry.apps.collectAsState()
    val declaration by connection.session.declaration.collectAsState()
    fun has(id: String) = registered.any { it.id.value == id }
    val window = LocalWindowClass.current
    val scope = rememberCoroutineScope()
    val services = declaration?.app("services")
    // The words of a service's state: the cloud's own `x-titles` of `status`, else the switcher's.
    val statusTitles = services?.models?.values?.flatMap { it.fields }?.firstOrNull { it.name == "status" }?.titles.orEmpty()
    fun statusTitle(status: String?): String = status?.let { statusTitles[it] ?: statusOf(it, ru).text.replaceFirstChar { c -> c.uppercase() } }.orEmpty()
    val attention = data.rows("attention").orEmpty()
    // «+ Создать сервис» — when the cloud lets this person make one: the action of the list of «Сервисы».
    val canCreate = services?.methods?.any { it.ui?.view == Views.ACTION && it.ui?.on == "list" } == true && has("services")
    val name = data.text("name") ?: declaration?.user?.fullName?.substringBefore(' ')

    Page(modifier = Modifier.testTag("home:cloud")) {
        PageHeader(
            (if (ru) "Добрый день" else "Good day") + (name?.let { ", $it" } ?: ""),
            description = when {
                attention.isEmpty() -> if (ru) "Все сервисы в порядке" else "All services are fine"
                ru -> "${attention.size} ${plural(attention.size, "сервис требует", "сервиса требуют", "сервисов требуют")} внимания, остальные в порядке"
                else -> "${attention.size} need attention, the rest are fine"
            },
            actions = if (canCreate) ({
                Button(if (ru) "Создать сервис" else "New service", { shell.openApp(AppId("services")) }, Modifier.testTag("home:new-service"), leadingIcon = Lucide.Plus)
            }) else null,
        )
        attention.forEach { item ->
            Incident(item, ru, statusTitle = statusTitle(item.text("status")),
                onRestart = item.actions().firstOrNull()?.let { action ->
                    {
                        scope.launch {
                            runCatching { connection.session.call("services", action.method, action.input ?: JsonObject(emptyMap())) }
                            reload()
                        }
                    }
                },
                onOpen = if (has("services")) ({ shell.openApp(AppId("services")) }) else null,
            )
        }

        val running = data.int("running")
        val total = data.int("total")
        val balance = data.number("balance")
        val perDay = data.number("perDay")
        val orders = data.int("ordersToday")
        val count = listOf(running != null && total != null, balance != null, perDay != null, orders != null).count { it }
        if (count > 0) Grid(byWindow(count.coerceAtMost(2), count, count), gap = byWindow(12.dp, 16.dp, 16.dp)) {
            if (running != null && total != null) {
                val starting = data.int("starting") ?: 0
                val failed = data.int("failed") ?: 0
                Stat(
                    if (ru) "Работают" else "Running", if (ru) "$running из $total" else "$running of $total", Modifier.testTag("home:stat-running"),
                    hint = listOfNotNull(
                        starting.takeIf { it > 0 }?.let { if (ru) "$it запускается" else "$it starting" },
                        failed.takeIf { it > 0 }?.let { if (ru) "$it с ошибкой" else "$it failed" },
                    ).joinToString(" · ").ifEmpty { if (ru) "без происшествий" else "all quiet" },
                    icon = Lucide.Server,
                    onClick = if (has("services")) ({ shell.openApp(AppId("services")) }) else null,
                )
            }
            if (balance != null) {
                val days = data.int("daysLeft")
                Stat(
                    if (ru) "Баланс" else "Balance", moneyText(balance, currency, ru = ru), Modifier.testTag("home:stat-balance"),
                    hint = listOfNotNull(
                        approxText(balance, rate),
                        when {
                            balance <= 0 -> if (ru) "баланс исчерпан" else "the balance has run out"
                            days != null -> if (ru) "хватит на ${daysText(days, true)}" else "lasts ${daysText(days, false)}"
                            else -> null
                        },
                    ).joinToString(" · ").ifEmpty { null },
                    icon = Lucide.Wallet, tone = toneOf(data.text("balanceTone")),
                    onClick = if (has("balance")) ({ shell.openApp(AppId("balance")) }) else null,
                )
            }
            if (perDay != null) {
                val month = data.number("perMonth")
                Stat(
                    if (ru) "Расход в сутки" else "Spend a day", moneyText(perDay, currency, ru = ru), Modifier.testTag("home:stat-per-day"),
                    hint = listOfNotNull(
                        approxText(perDay, rate),
                        month?.let { moneyText(it, currency, whole = true, ru = ru) + (if (ru) " в месяц" else " a month") },
                    ).joinToString(" · ").ifEmpty { null },
                    icon = Lucide.Flame,
                    onClick = if (has("balance")) ({ shell.openApp(AppId("balance")) }) else null,
                )
            }
            if (orders != null) {
                Stat(
                    if (ru) "Заказов сегодня" else "Orders today", orders.toString().reversed().chunked(3).joinToString(" ").reversed(), Modifier.testTag("home:stat-orders"),
                    hint = if (ru) "по всем сервисам" else "across the services", icon = Lucide.Receipt,
                )
            }
        }

        // P11: the amounts above are credits; the sum in the currency is at the system rate, which the (i) explains.
        rate?.let { RateNote(it, ru) }

        val list = data.rows("services")
        val spend = data.rows("spend")?.takeIf { it.size >= 2 }
        val wide = window != WindowClass.Compact
        val servicesPanel: (@Composable () -> Unit)? = list?.let { rows ->
            {
                Panel(
                    title = if (ru) "Сервисы" else "Services", flush = true, modifier = Modifier.testTag("home:services"),
                    action = if (has("services")) ({
                        Row(
                            Modifier.clip(RoundedCornerShape(7.dp)).kitClickable(hover = false) { shell.openApp(AppId("services")) }.padding(horizontal = 8.dp, vertical = 5.dp).testTag("home:all-services"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text((if (ru) "Все " else "All ") + (total ?: rows.size), style = Kit.textStyles.label, color = Kit.palette.onBrandSoft)
                            Icon(Lucide.ArrowRight, contentDescription = null, size = 14.dp, tint = Kit.palette.onBrandSoft)
                        }
                    }) else null,
                ) {
                    if (rows.isEmpty()) {
                        Line()
                        Text(if (ru) "Сервисов пока нет" else "No services yet", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = Kit.colors.mutedForeground)
                    }
                    rows.take(5).forEach { s -> Line(); ServiceRow(s, statusTitle(s.text("status")), onClick = if (has("services")) ({ shell.openApp(AppId("services")) }) else null) }
                }
            }
        }
        val spendPanel: (@Composable () -> Unit)? = if (spend != null && wide) ({ SpendPanel(data, spend, ru, currency) }) else null
        when {
            servicesPanel != null && spendPanel != null && window == WindowClass.Expanded -> Grid(5, spans = listOf(3, 2)) {
                servicesPanel()
                spendPanel()
            }
            else -> {
                servicesPanel?.invoke()
                spendPanel?.invoke()
            }
        }
    }
}

/**
 * P11: «ⓘ Суммы в кредитах · 1 кр = $0,01 · курс системы» under the numbers; a tap opens the whole note of the cloud — the
 * rate is set by the administrator, the sum in the currency is display only, everything is charged in credits.
 */
@Composable
private fun RateNote(rate: CreditRate, ru: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).kitClickable(hover = false) { open = !open }
            .padding(horizontal = 4.dp, vertical = 6.dp).testTag("home:rate"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Lucide.Info, contentDescription = if (ru) "О курсе" else "About the rate", size = 14.dp, tint = Kit.colors.mutedForeground)
            Text(
                (if (ru) "Суммы в кредитах · " else "Amounts in credits · ") + rateLine(rate, ru),
                style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 2,
            )
        }
        if (open) {
            Text(
                rate.note ?: if (ru) "Суммы в ${rate.currency} — только для отображения: все расчёты идут в кредитах."
                else "Amounts in ${rate.currency} are for display only: everything is calculated in credits.",
                Modifier.testTag("home:rate-note"), style = Kit.textStyles.caption, color = Kit.colors.foreground,
            )
        }
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Kit.colors.border))
}

/** Something broke: said in words, with what to do about it one tap away. */
@Composable
private fun Incident(item: JsonObject, ru: Boolean, statusTitle: String, onRestart: (() -> Unit)?, onOpen: (() -> Unit)?) {
    val tone = toneOf(item.text("tone") ?: Tones.DANGER).takeIf { it != Tone.Neutral } ?: Tone.Danger
    val phone = LocalWindowClass.current == WindowClass.Compact
    val shape = RoundedCornerShape(14.dp)
    val title = item.text("title").orEmpty()
    var restarting by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Kit.colors.card).background(tone.softColor()).border(1.dp, tone.color().copy(alpha = 0.28f), shape)
            .padding(if (phone) 14.dp else 16.dp).testTag("home:attention-${item.text("id")}"),
        verticalAlignment = if (phone) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(Lucide.TriangleAlert, tone, 40.dp)
        val actions: @Composable () -> Unit = {
            if (onRestart != null) Button(
                if (ru) "Перезапустить" else "Restart", { restarting = true; onRestart() },
                Modifier.testTag("home:attention-${item.text("id")}:restart"),
                variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = Lucide.RotateCw, loading = restarting, enabled = !restarting,
            )
            if (onOpen != null) Button(if (ru) "Разобраться" else "Look into it", onOpen, Modifier.testTag("home:attention-${item.text("id")}:open"), size = ButtonSize.Sm)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (item.text("status") == "error") (if (ru) "«$title» не работает" else "«$title» is down") else "«$title» · $statusTitle",
                style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold),
            )
            item.text("text")?.let { Text(it, variant = TextVariant.Muted) }
            if (phone) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
        }
        if (!phone) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun ServiceRow(s: JsonObject, statusTitle: String, onClick: (() -> Unit)?) {
    val phone = LocalWindowClass.current == WindowClass.Compact
    val status = s.text("status")
    val tone = toneOf(s.text("tone"))
    val load = (s["load"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull?.toFloat() }
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.kitClickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (phone) 16.dp else 20.dp, vertical = 12.dp).testTag("home:service-${s.text("id")}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NameMark(s.text("title").orEmpty(), 36.dp, dimmed = status == "stopped" || status == "deleting")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(s.text("title").orEmpty(), style = Kit.textStyles.label.copy(fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.text("host")?.let { Text(it, style = Kit.textStyles.caption.copy(fontFamily = FontFamily.Monospace), color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (!phone && load != null && load.size >= 2) {
            Sparkline(load, Modifier.width(96.dp).height(28.dp).testTag("home:service-${s.text("id")}:load"), color = if (tone == Tone.Neutral) Kit.palette.brand else tone.color())
        }
        if (statusTitle.isNotEmpty()) StatusBadge(statusTitle, tone)
        if (!phone && onClick != null) Icon(Lucide.ChevronRight, contentDescription = null, size = 16.dp, tint = Kit.colors.mutedForeground)
    }
}

/** Thirty days of spend, the total over them, and whose it was: one bar in shares and the first three by name. */
@Composable
private fun SpendPanel(data: JsonObject, spend: List<JsonObject>, ru: Boolean, currency: String?) {
    val share = data.rows("share").orEmpty().mapNotNull { row -> row.text("title")?.let { t -> row.number("value")?.let { t to it } } }
    val colors = Kit.palette.chart
    Panel(
        title = if (ru) "Расход за 30 дней" else "Spend, 30 days", modifier = Modifier.testTag("home:spend"),
        action = { data.number("spendTotal")?.let { Text(moneyText(it, currency, whole = true, ru = ru), style = Kit.textStyles.label) } },
    ) {
        BarChart(spend.map { (it.number("y") ?: 0.0).toFloat() }, spend.map { it.text("x").orEmpty() }, height = 132.dp)
        if (share.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShareBar(share.map { it.second.toFloat() })
            share.take(3).forEachIndexed { i, (title, value) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(colors[i % colors.size]))
                    Text(title, Modifier.weight(1f), variant = TextVariant.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(moneyText(value, currency, ru = ru), style = Kit.textStyles.number)
                }
            }
        }
    }
}
