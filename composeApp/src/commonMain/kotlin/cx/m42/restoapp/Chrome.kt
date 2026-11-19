package cx.m42.restoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronsUpDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sun
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.render.ServerStatus
import cx.m42.superizer.ThemeMode
import cx.m42.superizer.kit.Avatar
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.IconTile
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Menu
import cx.m42.superizer.kit.MenuItem
import cx.m42.superizer.kit.MenuLabel
import cx.m42.superizer.kit.MenuSeparator
import cx.m42.superizer.kit.NameMark
import cx.m42.superizer.kit.Panel
import cx.m42.superizer.kit.Pill
import cx.m42.superizer.kit.StatusDot
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Meter
import cx.m42.superizer.kit.color
import cx.m42.superizer.kit.softColor
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import com.composables.icons.lucide.Wallet
import androidx.compose.foundation.layout.width
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textStyles
import cx.m42.superizer.ui.shell.ShellChrome
import cx.m42.superizer.ui.shell.ShellScope

/*
 * RestoApp's chrome over the adaptive shell (`_plan/07-prototype-1to1.md`): the console of the
 * prototype. Under the context row of the sidebar — the point; at its foot — the server's state and
 * the person with their menu; on a phone — the point and the person at the end of the top bar, the
 * rest under «Ещё». Everything here is the host's: it reads the session, never an app.
 */

/** The chrome of [connection]: built once per Superizer, as the shell is. */
internal fun restoChrome(host: RestoAppHost, connection: Connection): ShellChrome {
    // A Resto Cloud account (_plan/08): «Обзор» for Home, its money where a server has its point.
    val cloud = connection.profile.cloud
    return ShellChrome(
        home = { HomeSummary(host, connection, this) },
        navHeader = { Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp)) { PointField(connection) } },
        navFooter = {
            val shell = this
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (cloud) BalanceCard(host, connection, shell) else ServerCard(host, connection)
                AccountRow(host, connection, shell)
            }
        },
        railFooter = {
            val shell = this
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (cloud) RailBalance(host, connection, shell)
                AccountMark(host, connection, shell, 36.dp)
            }
        },
        barTrailing = { root ->
            // An account has no points: the phone's bar shows what is left on it instead.
            if (cloud) BalanceChip(host, connection, this) else if (root) PointChip(connection)
            AccountMark(host, connection, this, 32.dp)
        },
        more = { MoreRows(host, connection, this) },
        homeTitle = if (cloud) ({ lang -> if (ru(lang)) "Обзор" else "Overview" }) else null,
        homeIcon = if (cloud) AppIcon.Path(DASHBOARD_PATH) else null,
        notice = { LowMoneyStrip(host, connection, this) },
    )
}

/** Material Symbols «dashboard»: the account's «Обзор». */
private const val DASHBOARD_PATH = "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"

/** The cloud account [connection] belongs to — itself, or the account of a server of it; null for the rest. */
@Composable
internal fun accountOf(host: RestoAppHost, connection: Connection): ServerProfile? {
    val servers by host.servers.collectAsState()
    val profile = connection.profile
    return when {
        profile.cloud -> servers.firstOrNull { it.id == profile.id } ?: profile
        profile.parent != null -> servers.firstOrNull { it.id == profile.parent && it.cloud }
        else -> null
    }
}

/** Where a tap on the money leads: «Баланс» of the account — in the account, or the account itself from a server of it. */
private fun openBalance(host: RestoAppHost, connection: Connection, scope: ShellScope, account: ServerProfile) {
    if (connection.profile.id == account.id) {
        if (connection.superizer.registry.get(AppId("balance")) != null) scope.openApp(AppId("balance")) else scope.openHome()
    } else {
        host.switchTo(account.id)
    }
}

/**
 * The strip over every screen of an account and of each of its servers when the money runs out (P10): amber under a
 * week, red under three days or at nothing — those are what stops. «Пополнить» leads to the account's balance.
 */
@Composable
private fun LowMoneyStrip(host: RestoAppHost, connection: Connection, scope: ShellScope) {
    val account = accountOf(host, connection) ?: return
    val balance = account.balance ?: return
    val tone = balanceTone(balance)
    if (tone == Tone.Neutral) return
    val r = ru(langOf(connection))
    Row(
        Modifier.fillMaxWidth().background(tone.softColor()).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).testTag("shell:low-money"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Lucide.Wallet, contentDescription = null, size = 15.dp, tint = tone.textColor())
        Text(
            if (balance.amount <= 0) (if (r) "Баланс исчерпан — сервисы остановлены" else "The balance has run out — the services are stopped")
            else (if (r) "Осталось " else "Left: ") + moneyText(balance.amount, account.currency, ru = r) + (moneyLeft(balance, r)?.let { " — $it" } ?: ""),
            Modifier.weight(1f), style = Kit.textStyles.caption.copy(fontWeight = FontWeight.Medium), color = tone.textColor(), maxLines = 2,
        )
        Text(
            if (r) "Пополнить" else "Top up",
            Modifier.clip(RoundedCornerShape(6.dp)).kitClickable(hover = false) { openBalance(host, connection, scope, account) }
                .padding(horizontal = 8.dp, vertical = 6.dp).testTag("shell:top-up"),
            style = Kit.textStyles.caption.copy(fontWeight = FontWeight.SemiBold), color = tone.textColor(),
        )
    }
}

/** The money left, one tap from the balance: the phone's bar of an account, instead of a search. */
@Composable
private fun BalanceChip(host: RestoAppHost, connection: Connection, scope: ShellScope) {
    val account = accountOf(host, connection) ?: return
    val balance = account.balance ?: return
    val tone = balanceTone(balance)
    val warn = tone != Tone.Neutral
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(if (warn) tone.softColor() else Kit.colors.card)
            .border(1.dp, if (warn) androidx.compose.ui.graphics.Color.Transparent else Kit.colors.border, CircleShape)
            .kitClickable(hover = false) { openBalance(host, connection, scope, account) }.padding(horizontal = 10.dp).testTag("shell:balance-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Lucide.Wallet, contentDescription = null, size = 14.dp, tint = if (warn) tone.textColor() else Kit.colors.mutedForeground)
        Text(
            moneyText(balance.amount, account.currency, whole = true, ru = ru(langOf(connection))),
            style = Kit.textStyles.caption.copy(fontWeight = FontWeight.SemiBold), color = if (warn) tone.textColor() else Kit.colors.foreground, maxLines = 1,
        )
    }
}

/** The tablet's rail: the money left under the destinations, tinted when it runs low. */
@Composable
private fun RailBalance(host: RestoAppHost, connection: Connection, scope: ShellScope) {
    val account = accountOf(host, connection) ?: return
    val balance = account.balance ?: return
    val tone = balanceTone(balance)
    val warn = tone != Tone.Neutral
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).background(if (warn) tone.softColor() else androidx.compose.ui.graphics.Color.Transparent)
            .kitClickable { openBalance(host, connection, scope, account) }.padding(vertical = 8.dp).testTag("shell:balance"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(moneyText(balance.amount, account.currency, whole = true, ru = ru(langOf(connection))), style = Kit.textStyles.caption.copy(fontWeight = FontWeight.SemiBold), color = if (warn) tone.textColor() else Kit.colors.foreground, maxLines = 1)
        balance.daysLeft?.let { Text(daysText(it, ru(langOf(connection))), style = Kit.textStyles.caption, color = if (warn) tone.textColor() else Kit.colors.mutedForeground, maxLines = 1) }
    }
}

/** The sidebar's foot of an account: what is left, how long it lasts, the meter of it and the way to top it up. */
@Composable
private fun BalanceCard(host: RestoAppHost, connection: Connection, scope: ShellScope) {
    val account = accountOf(host, connection) ?: return
    val balance = account.balance ?: return
    val r = ru(langOf(connection))
    val tone = balanceTone(balance)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Kit.colors.card).border(1.dp, Kit.colors.border, shape).padding(14.dp).testTag("shell:balance"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (r) "Осталось на счёте" else "Left on the account", style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
                Text(moneyText(balance.amount, account.currency, ru = r), style = Kit.textStyles.metric.copy(fontSize = Kit.typography.h4.fontSize, lineHeight = Kit.typography.h4.lineHeight), maxLines = 1)
            }
            moneyLeft(balance, r)?.let { Text(it, style = Kit.textStyles.caption, color = if (tone != Tone.Neutral) tone.textColor() else Kit.colors.mutedForeground, maxLines = 1) }
        }
        balance.daysLeft?.let { days ->
            Meter((days / 60f).coerceIn(0.04f, 1f), color = if (tone != Tone.Neutral) tone.color() else Kit.palette.brand, height = 5.dp)
        }
        Button(
            if (r) "Пополнить" else "Top up", { openBalance(host, connection, scope, account) }, Modifier.fillMaxWidth().testTag("shell:balance-top-up"),
            variant = ButtonVariant.Outline, size = ButtonSize.Sm, leadingIcon = Lucide.Plus,
        )
    }
}

private fun ru(lang: String) = lang.startsWith("ru")

@Composable
private fun langOf(connection: Connection): String = connection.superizer.settings.langTag.collectAsState().value

/** The points of the server the person has in any app, and the one picked. */
@Composable
private fun pointsOf(connection: Connection): Pair<List<Place>, Place?> {
    val declaration by connection.session.declaration.collectAsState()
    val picked by connection.session.pickedPlace.collectAsState()
    val places = pointsByUse(declaration)
    return places to (places.firstOrNull { it.id == picked } ?: places.firstOrNull())
}

@Composable
private fun PointMenu(connection: Connection, places: List<Place>, current: Place?, open: Boolean, onDismiss: () -> Unit) {
    val lang = langOf(connection)
    Menu(open, onDismiss, alignEnd = false, width = 240.dp) {
        MenuLabel(if (ru(lang)) "Точка" else "Point")
        places.forEach { p ->
            Box(Modifier.testTag("shell:point:item-${p.id}")) {
                MenuItem(p.title, { connection.session.selectPlace(p.id); onDismiss() }, selected = p.id == current?.id, hint = p.address)
            }
        }
    }
}

/** The sidebar's point: what orders, the menu and the till show. One point — nothing to choose, nothing drawn. */
@Composable
private fun PointField(connection: Connection) {
    val (places, current) = pointsOf(connection)
    if (places.size < 2 || current == null) return
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Box {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(shape).background(Kit.colors.background).border(1.dp, Kit.colors.border, shape)
                .kitClickable(hover = false) { open = true }.padding(horizontal = 10.dp).testTag("shell:point"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Lucide.MapPin, contentDescription = null, size = 15.dp, tint = Kit.colors.mutedForeground)
            Text(current.title, Modifier.weight(1f), style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Lucide.ChevronsUpDown, contentDescription = null, size = 13.dp, tint = Kit.colors.mutedForeground)
        }
        PointMenu(connection, places, current, open) { open = false }
    }
}

/** The phone's point: a chip at the end of the top bar. */
@Composable
private fun PointChip(connection: Connection) {
    val (places, current) = pointsOf(connection)
    if (current == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(32.dp).clip(CircleShape).background(Kit.colors.card).border(1.dp, Kit.colors.border, CircleShape)
                .kitClickable(enabled = places.size > 1, hover = false) { open = true }.padding(horizontal = 10.dp).testTag("shell:point"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Lucide.MapPin, contentDescription = null, size = 13.dp, tint = Kit.colors.mutedForeground)
            Text(current.title, style = Kit.textStyles.caption.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        }
        PointMenu(connection, places, current, open) { open = false }
    }
}

/** The server's own state at the foot of the sidebar: it answers, it does not, the person has to sign in. */
@Composable
private fun ServerCard(host: RestoAppHost, connection: Connection) {
    val account = accountOf(host, connection)
    val lang = langOf(connection)
    val status by connection.session.status.collectAsState()
    val declaration by connection.session.declaration.collectAsState()
    val shape = RoundedCornerShape(12.dp)
    val r = ru(lang)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Kit.colors.card).border(1.dp, Kit.colors.border, shape).padding(12.dp).testTag("shell:server"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(
            when (status) {
                ServerStatus.Online -> Tone.Success
                ServerStatus.Offline -> Tone.Warn
                else -> Tone.Danger
            },
        )
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    connection.profile.isDemo -> if (r) "Демо-сервер" else "Demo server"
                    status == ServerStatus.Online -> if (r) "Сервер работает" else "The server is up"
                    status == ServerStatus.Offline -> if (r) "Нет связи с сервером" else "No connection"
                    status == ServerStatus.SignedOut -> if (r) "Нужно войти заново" else "Sign in again"
                    else -> if (r) "Обновите приложение" else "Update the app"
                },
                style = Kit.textStyles.label, maxLines = 1,
            )
            val version = declaration?.server?.version
            Text(
                when {
                    connection.profile.isDemo -> if (r) "Данные только на этом устройстве" else "The data stays on this device"
                    // A server of a cloud account: what is left on the account that pays for it.
                    account?.balance != null -> (if (r) "На счёте " else "On the account ") + balanceLine(account.balance, account.currency, r)
                    version != null -> "RestoApp $version"
                    else -> hostOf(connection.profile.address)
                },
                style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Who the person is on this server: the name the server knows them by, and what it lets them be. */
@Composable
private fun whoOf(connection: Connection): Pair<String, String> {
    val lang = langOf(connection)
    val declaration by connection.session.declaration.collectAsState()
    val login by connection.session.login.collectAsState()
    val user = declaration?.user
    val name = user?.fullName?.takeIf { it.isNotBlank() } ?: user?.login ?: login ?: connection.profile.name
    val role = when {
        user?.isAdministrator == true -> if (ru(lang)) "Администратор" else "Administrator"
        user != null -> user.login
        else -> hostOf(connection.profile.address)
    }
    return name to role
}

@Composable
private fun AccountMenu(host: RestoAppHost, connection: Connection, scope: ShellScope, open: Boolean, onDismiss: () -> Unit) {
    val lang = langOf(connection)
    val r = ru(lang)
    val (name, role) = whoOf(connection)
    val theme by connection.superizer.settings.theme.collectAsState()
    val dark = theme == ThemeMode.Dark
    Menu(open, onDismiss, alignEnd = false, width = 240.dp) {
        MenuLabel("$name · $role")
        MenuItem(if (r) "Настройки приложения" else "App settings", { onDismiss(); scope.openSettings() }, icon = Lucide.Settings)
        MenuItem(
            if (dark) (if (r) "Светлая тема" else "Light theme") else (if (r) "Тёмная тема" else "Dark theme"),
            { connection.superizer.settings.chooseTheme(if (dark) ThemeMode.Light else ThemeMode.Dark); onDismiss() },
            icon = if (dark) Lucide.Sun else Lucide.Moon,
        )
        MenuSeparator()
        MenuItem(if (r) "Управление серверами" else "Manage servers", { onDismiss(); scope.openScreen(host.manageScreen) }, icon = Lucide.Server)
    }
}

@Composable
private fun AccountRow(host: RestoAppHost, connection: Connection, scope: ShellScope) {
    var open by remember { mutableStateOf(false) }
    val (name, role) = whoOf(connection)
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).kitClickable { open = true }.padding(8.dp).testTag("shell:account"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(name, size = 30.dp, fill = Kit.palette.brandSoft, contentColor = Kit.palette.onBrandSoft)
            Column(Modifier.weight(1f)) {
                Text(name, style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(role, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Lucide.ChevronsUpDown, contentDescription = null, size = 14.dp, tint = Kit.colors.mutedForeground)
        }
        AccountMenu(host, connection, scope, open) { open = false }
    }
}

/** The person as a mark: the rail's foot and the end of the phone's bar. */
@Composable
private fun AccountMark(host: RestoAppHost, connection: Connection, scope: ShellScope, size: androidx.compose.ui.unit.Dp) {
    var open by remember { mutableStateOf(false) }
    val (name, _) = whoOf(connection)
    Box {
        Avatar(name, Modifier.clip(CircleShape).kitClickable(hover = false) { open = true }.testTag("shell:account"), size = size, fill = Kit.palette.brandSoft, contentColor = Kit.palette.onBrandSoft)
        AccountMenu(host, connection, scope, open) { open = false }
    }
}

@Composable
private fun MoreLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tag: String, chevron: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().kitClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon, Tone.Neutral, 34.dp)
        Text(title, Modifier.weight(1f), style = Kit.textStyles.label)
        if (chevron) Icon(Lucide.ChevronRight, contentDescription = null, size = 16.dp, tint = Kit.colors.mutedForeground)
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Kit.colors.border))
}

/** «Ещё», under the apps: the server in force and who the person is on it, then the app's own doors. */
@Composable
private fun MoreRows(host: RestoAppHost, connection: Connection, scope: ShellScope) {
    val lang = langOf(connection)
    val r = ru(lang)
    val current by host.current.collectAsState()
    val (_, role) = whoOf(connection)
    val theme by connection.superizer.settings.theme.collectAsState()
    val dark = theme == ThemeMode.Dark
    Panel(title = if (r) "Сервер" else "Server", flush = true) {
        Line()
        Row(
            Modifier.fillMaxWidth().kitClickable(onClick = scope.openContexts).padding(horizontal = 16.dp, vertical = 12.dp).testTag("more:server"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NameMark(current?.title ?: connection.profile.name, 34.dp)
            Column(Modifier.weight(1f)) {
                Text(current?.title ?: connection.profile.name, style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                current?.subtitle?.let { Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Pill(role, Tone.Neutral)
        }
        Line()
        MoreLine(Lucide.Server, if (r) "Управление серверами" else "Manage servers", "more:servers") { scope.openScreen(host.manageScreen) }
        Line()
        MoreLine(Lucide.Plus, if (r) "Подключить сервер" else "Connect a server", "more:add") { scope.openScreen(host.addScreen) }
    }
    Panel(flush = true) {
        MoreLine(if (dark) Lucide.Sun else Lucide.Moon, if (dark) (if (r) "Светлая тема" else "Light theme") else (if (r) "Тёмная тема" else "Dark theme"), "more:theme", chevron = false) {
            connection.superizer.settings.chooseTheme(if (dark) ThemeMode.Light else ThemeMode.Dark)
        }
        Line()
        MoreLine(Lucide.Settings, if (r) "Настройки приложения" else "App settings", "more:app-settings") { scope.openSettings() }
    }
}
