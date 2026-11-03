package cx.m42.restoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.protocol.ServerAbout
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.render.Notice
import cx.m42.restoapp.render.ServerStatus
import cx.m42.superizer.HomeBanner
import cx.m42.superizer.HostScreen
import cx.m42.superizer.HostSection
import cx.m42.superizer.Superizer
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.CheckIcon
import cx.m42.superizer.ui.components.Monogram
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The host's own words, in the two languages it speaks. */
private fun String.ru(langTag: String, en: String): String = if (langTag.startsWith("ru")) this else en

@Composable
private fun Superizer.lang(): String {
    val tag by settings.langTag.collectAsState()
    return tag
}

/**
 * "Подключить сервер", as GitHub's "Add account": the demo server in one tap, or a RestoApp of your
 * own — its address, `server.about` to see it is one, then the admin panel's login and password,
 * the CAPTCHA solved here, and the server is in force, signed in (plan section 4).
 */
internal class AddServerScreen(private val host: RestoAppHost) : HostScreen {
    override val id: String = "add-server"

    override fun title(langTag: String): String = "Подключить сервер".ru(langTag, "Connect a server")

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val lang = superizer.lang()
        val words = remember(lang) { HostWords(lang) }
        val tokens = AppTheme
        val scope = rememberCoroutineScope()
        var custom by remember { mutableStateOf(false) }
        var address by remember { mutableStateOf("") }
        var found by remember { mutableStateOf<Pair<String, ServerAbout>?>(null) }
        var name by remember { mutableStateOf("") }
        var login by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var working by remember { mutableStateOf<String?>(null) }
        var problem by remember { mutableStateOf<String?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.pageBackground)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            Option(title = words.demoServer, body = words.demoServerHint, tag = "servers:add-demo", onClick = { host.addDemo() })
            Option(title = words.ownServer, body = words.ownServerHint, tag = "servers:add-custom", selected = custom, onClick = { custom = true })

            if (custom) {
                Spacer(Modifier.height(12.dp))
                Section(title = words.address) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextField(
                            value = address,
                            onValueChange = {
                                address = it
                                found = null
                                problem = null
                            },
                            placeholder = "https://admin.restoran.ru",
                            label = words.address,
                            modifier = Modifier.fillMaxWidth().testTag("servers:address"),
                        )
                        found?.let { (_, about) ->
                            Text(
                                text = words.server(about.name, about.version),
                                style = tokens.label,
                                color = tokens.muted,
                                modifier = Modifier.testTag("servers:found"),
                            )
                        }
                    }
                }
                if (found == null) {
                    AppButton(
                        text = if (working != null) words.checking else words.next,
                        onClick = {
                            working = words.checking
                            problem = null
                            scope.launch {
                                try {
                                    val result = host.check(address)
                                    found = result
                                    name = result.second.name
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    problem = words.failure(failure)
                                } finally {
                                    working = null
                                }
                            }
                        },
                        enabled = working == null && address.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("servers:check"),
                    )
                } else {
                    Section(title = words.signIn) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppTextField(value = login, onValueChange = { login = it }, placeholder = words.login, modifier = Modifier.fillMaxWidth().testTag("servers:login"))
                            PasswordField(password, { password = it }, words.password, "servers:password")
                            AppTextField(value = name, onValueChange = { name = it }, placeholder = words.name, label = words.name, modifier = Modifier.fillMaxWidth().testTag("servers:name"))
                        }
                    }
                    AppButton(
                        text = working ?: words.signIn,
                        onClick = {
                            val base = found?.first ?: return@AppButton
                            working = words.signingIn
                            problem = null
                            scope.launch {
                                try {
                                    host.addServer(base, name, login, password) { fraction -> working = words.progress(fraction) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    problem = words.failure(failure)
                                } finally {
                                    working = null
                                }
                            }
                        },
                        enabled = working == null && login.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("servers:connect"),
                    )
                }
                problem?.let { Problem(it) }
            }
        }
    }
}

/** A password field: the keyboard neither suggests over it nor learns it, and it shows dots. */
@Composable
internal fun PasswordField(value: String, onChange: (String) -> Unit, placeholder: String, tag: String) {
    val tokens = AppTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, tokens.border, RoundedCornerShape(8.dp))
            .background(tokens.background)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(text = placeholder, style = tokens.body, color = tokens.muted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = tokens.body.copy(color = tokens.foreground),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            cursorBrush = SolidColor(tokens.foreground),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder }.testTag(tag),
        )
    }
}

@Composable
private fun Problem(text: String) {
    val tokens = AppTheme
    Text(
        text = text,
        style = tokens.label,
        color = tokens.danger,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).testTag("servers:problem"),
    )
}

/**
 * Signing in again to a server the person has: the server no longer knows this device — revoked,
 * expired, the password changed — or they signed out. The menu and its data stay; this brings the
 * token back.
 */
internal class LoginScreen(private val host: RestoAppHost) : HostScreen {
    override val id: String = "login"

    override val secure: Boolean = true

    override fun title(langTag: String): String = HostWords(langTag).signIn

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val lang = superizer.lang()
        val words = remember(lang) { HostWords(lang) }
        val tokens = AppTheme
        val scope = rememberCoroutineScope()
        val connection by host.active.collectAsState()
        val session = connection.session
        var login by remember { mutableStateOf(session.login.value.orEmpty()) }
        var password by remember { mutableStateOf("") }
        var working by remember { mutableStateOf<String?>(null) }
        var problem by remember { mutableStateOf<String?>(null) }

        Column(
            modifier = Modifier.fillMaxSize().background(tokens.pageBackground).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        ) {
            Section(title = connection.profile.name, hint = hostOf(connection.profile.address)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(value = login, onValueChange = { login = it }, placeholder = words.login, modifier = Modifier.fillMaxWidth().testTag("login:login"))
                    PasswordField(password, { password = it }, words.password, "login:password")
                }
            }
            AppButton(
                text = working ?: words.signIn,
                onClick = {
                    working = words.signingIn
                    problem = null
                    scope.launch {
                        try {
                            session.signIn(login, password) { fraction -> working = words.progress(fraction) }
                            back()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            problem = words.failure(failure)
                        } finally {
                            working = null
                        }
                    }
                },
                enabled = working == null && login.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("login:submit"),
            )
            problem?.let { Problem(it) }
        }
    }
}

/** The demo's role switch and its data — what a real server's administrator decides in the groups. */
internal class DemoScreen(private val host: RestoAppHost) : HostScreen {
    override val id: String = "demo"

    override fun title(langTag: String): String = HostWords(langTag).demoTitle

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val lang = superizer.lang()
        val words = remember(lang) { HostWords(lang) }
        val tokens = AppTheme
        val connection by host.active.collectAsState()
        val demo = connection.session.api as? DemoBackend ?: return
        val role by demo.role.collectAsState()

        Column(modifier = Modifier.fillMaxSize().background(tokens.pageBackground).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Section(title = words.role, hint = words.demoData) {
                DemoRole.entries.forEachIndexed { index, each ->
                    if (index > 0) RowDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { host.setDemoRole(each) }
                            .semantics { selected = each == role }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("demo:role-${each.name.lowercase()}"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = words.roleTitle(each), style = tokens.body, color = tokens.foreground, modifier = Modifier.weight(1f))
                            if (each == role) CheckIcon(tokens.foreground)
                        }
                        Text(text = words.roleHint(each), style = tokens.label, color = tokens.muted)
                    }
                }
            }
            AppButton(
                text = words.resetDemo,
                onClick = { host.resetDemo() },
                variant = ButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("demo:reset"),
            )
        }
    }
}

/**
 * The line on Home about the server itself: sign in again, no connection, update the app, nothing
 * granted — or, on the demo, which role it plays.
 */
internal class SessionBanner(private val host: RestoAppHost) : HomeBanner {
    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit): Boolean {
        val lang = superizer.lang()
        val words = remember(lang) { HostWords(lang) }
        val connection by host.active.collectAsState()
        val session = connection.session
        val status by session.status.collectAsState()
        val declaration by session.declaration.collectAsState()
        when {
            status == ServerStatus.SignedOut -> Notice(words.needsSignIn, Tones.DANGER, words.signIn, { open(LoginScreen(host)) }, "home:sign-in")
            status == ServerStatus.Offline -> Notice(words.offline, Tones.WARNING, words.retry, { session.refreshInBackground() }, "home:offline")
            status == ServerStatus.UpdateRequired -> Notice(words.updateRequired, Tones.DANGER, null, null, "home:update")
            declaration != null && declaration!!.apps.isEmpty() -> Notice(words.nothingGranted, Tones.INFO, null, null, "home:nothing")
            session.api is DemoBackend -> {
                val role by (session.api as DemoBackend).role.collectAsState()
                Notice(words.demoRole(role), null, words.change, { open(DemoScreen(host)) }, "home:demo")
            }
            else -> return false
        }
        return true
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

/** "Серверы" in Settings: where you are, as whom, and the doors to the other screens. */
internal class ServersSection(private val host: RestoAppHost) : HostSection {
    override val id: String = "servers"

    override fun title(langTag: String): String = "Серверы".ru(langTag, "Servers")

    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit) {
        val lang = superizer.lang()
        val words = remember(lang) { HostWords(lang) }
        val tokens = AppTheme
        val scope = rememberCoroutineScope()
        val current by host.current.collectAsState()
        val connection by host.active.collectAsState()
        val session = connection.session
        val login by session.login.collectAsState()
        val status by session.status.collectAsState()
        current?.let { server ->
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Monogram(title = server.title, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(text = server.title, style = tokens.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.foreground)
                    server.subtitle?.let { Text(text = it, style = tokens.label, color = tokens.muted) }
                    if (!connection.profile.isDemo && login != null && status != ServerStatus.SignedOut) {
                        Text(text = words.signedInAs(login!!), style = tokens.label, color = tokens.muted, modifier = Modifier.testTag("settings:signed-in"))
                    }
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
            when {
                connection.profile.isDemo -> AppButton(
                    text = words.demoTitle,
                    onClick = { open(DemoScreen(host)) },
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("settings:demo"),
                )
                status == ServerStatus.SignedOut -> AppButton(
                    text = words.signIn,
                    onClick = { open(LoginScreen(host)) },
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("settings:sign-in"),
                )
                else -> AppButton(
                    text = words.signOut,
                    onClick = { scope.launch { session.signOut() } },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("settings:sign-out"),
                )
            }
        }
    }
}
