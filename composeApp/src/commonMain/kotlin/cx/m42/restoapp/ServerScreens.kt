package cx.m42.restoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.HostScreen
import cx.m42.superizer.HostSection
import cx.m42.superizer.Superizer
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.Monogram
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.components.ToggleRow

/** The host's own words, in the two languages it speaks. */
private fun String.ru(langTag: String, en: String): String = if (langTag.startsWith("ru")) this else en

@Composable
private fun Superizer.lang(): String {
    val tag by settings.langTag.collectAsState()
    return tag
}

/**
 * "Подключить сервер", as GitHub's "Add account": a ready-made demo server in one tap, or one of
 * your own by address. Either way the new server is in force as soon as it is added.
 */
internal class AddServerScreen(private val host: RestoAppHost) : HostScreen {
    override val id: String = "add-server"

    override fun title(langTag: String): String = "Подключить сервер".ru(langTag, "Connect a server")

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val lang = superizer.lang()
        val tokens = AppTheme
        var custom by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf(ServerApps.ids.toSet()) }
        var checking by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.pageBackground)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            Option(
                title = "Демо-сервер".ru(lang, "Demo server"),
                body = "Готовые заказы и склад, чтобы посмотреть, как всё работает"
                    .ru(lang, "Orders and stock, ready to try"),
                tag = "servers:add-demo",
                onClick = { host.addDemo() },
            )
            Option(
                title = "Свой сервер".ru(lang, "Your server"),
                body = "По адресу вашего RestoApp. Пока подключение имитируется"
                    .ru(lang, "By your RestoApp's address. The connection is simulated for now"),
                tag = "servers:add-custom",
                selected = custom,
                onClick = { custom = true },
            )

            if (custom) {
                Spacer(Modifier.height(12.dp))
                Section(title = "Сервер".ru(lang, "Server")) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextField(
                            value = address,
                            onValueChange = { address = it },
                            placeholder = "https://admin.restoran.ru",
                            label = "Адрес".ru(lang, "Address"),
                            modifier = Modifier.fillMaxWidth().testTag("servers:address"),
                        )
                        AppTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "Название, например «Кафе на Мира»".ru(lang, "Name, e.g. \"Main street cafe\""),
                            label = "Название".ru(lang, "Name"),
                            modifier = Modifier.fillMaxWidth().testTag("servers:name"),
                        )
                    }
                }
                Section(
                    title = "Что есть на сервере".ru(lang, "What the server has"),
                    hint = "Настоящий сервер скажет это сам — пока выберите для демо"
                        .ru(lang, "A real server will say so itself; pick for the demo"),
                ) {
                    ServerApps.ids.forEachIndexed { index, appId ->
                        if (index > 0) RowDivider()
                        ToggleRow(
                            label = ServerApps.title(appId, lang),
                            checked = appId in apps,
                            onCheckedChange = { on -> apps = if (on) apps + appId else apps - appId },
                            modifier = Modifier.testTag("servers:app-$appId"),
                        )
                    }
                }
                AppButton(
                    text = if (checking) "Проверяем подключение…".ru(lang, "Checking the connection…") else "Подключить".ru(lang, "Connect"),
                    onClick = {
                        checking = true
                        host.connectNew(name, address, apps.toList())
                    },
                    enabled = !checking && address.isNotBlank() && apps.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("servers:connect"),
                )
            }
        }
    }
}

@Composable
private fun Option(title: String, body: String, tag: String, selected: Boolean = false, onClick: () -> Unit) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(if (selected) tokens.surface else tokens.background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp)
            .testTag(tag),
    ) {
        Text(text = title, style = tokens.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.foreground)
        Spacer(Modifier.height(4.dp))
        Text(text = body, style = tokens.label, color = tokens.muted)
    }
}

/** Rename, reorder, remove — everything about servers that is not switching between them. */
internal class ManageServersScreen(private val host: RestoAppHost) : HostScreen {
    override val id: String = "servers"

    override fun title(langTag: String): String = "Управление серверами".ru(langTag, "Manage servers")

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val lang = superizer.lang()
        val tokens = AppTheme
        val servers by host.servers.collectAsState()
        val current by host.current.collectAsState()
        var renaming by remember { mutableStateOf<String?>(null) }
        var removing by remember { mutableStateOf<String?>(null) }
        var typed by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.pageBackground)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            Section(
                title = "Серверы".ru(lang, "Servers"),
                hint = "Порядок — тот же, что в переключателе и для Ctrl+1…9".ru(lang, "The order is the switcher's, and Ctrl+1…9's"),
            ) {
                servers.forEachIndexed { index, server ->
                    if (index > 0) RowDivider()
                    val isCurrent = server.id == current?.id
                    Column(modifier = Modifier.padding(12.dp).testTag("servers:row-${server.id}")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Monogram(title = server.name, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = server.name,
                                    style = tokens.body.copy(fontWeight = FontWeight.SemiBold),
                                    color = tokens.foreground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = server.address + if (isCurrent) " · " + "текущий".ru(lang, "current") else "",
                                    style = tokens.label,
                                    color = tokens.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        when (server.id) {
                            renaming -> Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppTextField(
                                    value = typed,
                                    onValueChange = { typed = it },
                                    placeholder = server.name,
                                    modifier = Modifier.fillMaxWidth().testTag("servers:name-field"),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppButton(
                                        text = "Сохранить".ru(lang, "Save"),
                                        onClick = {
                                            host.rename(server.id, typed)
                                            renaming = null
                                        },
                                        enabled = typed.isNotBlank(),
                                        size = ButtonSize.Sm,
                                        modifier = Modifier.testTag("servers:save-name"),
                                    )
                                    AppButton(
                                        text = "Отмена".ru(lang, "Cancel"),
                                        onClick = { renaming = null },
                                        variant = ButtonVariant.Ghost,
                                        size = ButtonSize.Sm,
                                    )
                                }
                            }

                            removing -> Column(modifier = Modifier.padding(top = 8.dp)) {
                                Text(
                                    text = "Удалить «${server.name}» и его данные на этом компьютере?"
                                        .ru(lang, "Remove \"${server.name}\" and its data on this computer?"),
                                    style = tokens.label,
                                    color = tokens.foreground,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppButton(
                                        text = "Удалить".ru(lang, "Remove"),
                                        onClick = {
                                            host.remove(server.id)
                                            removing = null
                                        },
                                        variant = ButtonVariant.Destructive,
                                        size = ButtonSize.Sm,
                                        modifier = Modifier.testTag("servers:confirm-remove"),
                                    )
                                    AppButton(
                                        text = "Отмена".ru(lang, "Cancel"),
                                        onClick = { removing = null },
                                        variant = ButtonVariant.Ghost,
                                        size = ButtonSize.Sm,
                                    )
                                }
                            }

                            else -> Row(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                AppButton(
                                    text = "Переименовать".ru(lang, "Rename"),
                                    onClick = {
                                        typed = server.name
                                        removing = null
                                        renaming = server.id
                                    },
                                    variant = ButtonVariant.Ghost,
                                    size = ButtonSize.Sm,
                                    modifier = Modifier.testTag("servers:rename-${server.id}"),
                                )
                                AppButton(
                                    text = "↑",
                                    onClick = { host.move(server.id, -1) },
                                    enabled = index > 0,
                                    variant = ButtonVariant.Ghost,
                                    size = ButtonSize.Sm,
                                    modifier = Modifier.testTag("servers:up-${server.id}"),
                                )
                                AppButton(
                                    text = "↓",
                                    onClick = { host.move(server.id, 1) },
                                    enabled = index < servers.lastIndex,
                                    variant = ButtonVariant.Ghost,
                                    size = ButtonSize.Sm,
                                    modifier = Modifier.testTag("servers:down-${server.id}"),
                                )
                                if (!isCurrent) {
                                    AppButton(
                                        text = "Удалить".ru(lang, "Remove"),
                                        onClick = {
                                            renaming = null
                                            removing = server.id
                                        },
                                        variant = ButtonVariant.Ghost,
                                        size = ButtonSize.Sm,
                                        modifier = Modifier.testTag("servers:remove-${server.id}"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            AppButton(
                text = "+ " + "Подключить сервер".ru(lang, "Connect a server"),
                onClick = { open(host.addScreen) },
                variant = ButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("servers:add"),
            )
        }
    }
}

/** "Серверы" in Settings: where you are, and the doors to the two screens above. */
internal class ServersSection(private val host: RestoAppHost) : HostSection {
    override val id: String = "servers"

    override fun title(langTag: String): String = "Серверы".ru(langTag, "Servers")

    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit) {
        val lang = superizer.lang()
        val tokens = AppTheme
        val current by host.current.collectAsState()
        current?.let { server ->
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Monogram(title = server.title, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(text = server.title, style = tokens.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.foreground)
                    server.subtitle?.let { Text(text = it, style = tokens.label, color = tokens.muted) }
                }
            }
            RowDivider()
        }
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppButton(
                text = "Управление".ru(lang, "Manage"),
                onClick = { open(host.manageScreen) },
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("settings:servers-manage"),
            )
            AppButton(
                text = "Подключить".ru(lang, "Connect"),
                onClick = { open(host.addScreen) },
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("settings:servers-add"),
            )
        }
    }
}
