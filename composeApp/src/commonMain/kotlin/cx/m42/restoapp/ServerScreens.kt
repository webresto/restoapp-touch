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
import androidx.compose.runtime.LaunchedEffect
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
import cx.m42.superizer.kit.Avatar
import cx.m42.superizer.kit.AvatarShape
import cx.m42.superizer.kit.Kit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.restoapp.render.Group
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.ItemSeparator
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.FlaskConical
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.CircleCheck
import cx.m42.superizer.kit.Field
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Text as KitText
import cx.m42.restoapp.render.LeadingIcon
import cx.m42.restoapp.render.PageGutter

/** The host's own words, in the two languages it speaks. */
private fun String.ru(langTag: String, en: String): String = if (langTag.startsWith("ru")) this else en

@Composable
private fun Superizer.lang(): String {
    val tag by settings.langTag.collectAsState()
    return tag
}

/**
 * "Подключить сервер", as GitHub's "Add account": the demo server in one tap, Resto Cloud (the cloud
 * account — its services and balance, [ServerProfile.CLOUD_ADDRESS]), or a RestoApp of your own — its
 * address, `server.about` to see it is one, then the admin panel's login and password, the CAPTCHA
 * solved here, and the server is in force, signed in (plan section 4). The same address may be added
 * under several accounts: the login then goes into the name, so the switcher tells them apart.
 */
internal class AddServerScreen(private val host: RestoAppHost, private val cloudAccount: Boolean) : HostScreen {
    override val id: String = if (cloudAccount) "add-cloud" else "add-server"

    override fun title(langTag: String): String =
        if (cloudAccount) HostWords(langTag).connectCloud else "Подключить сервер".ru(langTag, "Connect a server")

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val lang = superizer.lang()
        val words = remember(lang) { HostWords(lang) }
        val tokens = AppTheme
        val scope = rememberCoroutineScope()
        var custom by remember { mutableStateOf(false) }
        var cloud by remember { mutableStateOf(false) }
        // Sign-up instead of sign-in: only on a cloud whose `server.about` says registration is open.
        var signUp by remember { mutableStateOf(false) }
        var fullName by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
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
            fun openCloud() {
                if (working != null) return
                cloud = true
                custom = false
                address = host.cloudAddress
                found = null
                problem = null
                working = words.checking
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
            }
            // «Подключить облачный аккаунт» goes straight to the cloud's sign-in.
            if (cloudAccount) LaunchedEffect(Unit) { openCloud() }

            if (cloudAccount) {
                Option(title = words.cloud, body = words.cloudHint, icon = Lucide.Cloud, tag = "servers:add-cloud", selected = true, onClick = { if (found == null) openCloud() })
            } else {
                Option(title = words.demoServer, body = words.demoServerHint, icon = Lucide.FlaskConical, tag = "servers:add-demo", onClick = { host.addDemo() })
            }
            if (!cloudAccount) Option(
                title = words.ownServer,
                body = words.ownServerHint,
                icon = Lucide.Server,
                tag = "servers:add-custom",
                selected = custom,
                onClick = {
                    if (cloud) {
                        address = ""
                        found = null
                        problem = null
                    }
                    custom = true
                    cloud = false
                },
            )

            if (cloud && found == null) {
                Text(
                    text = working ?: words.cloudHint,
                    style = tokens.label,
                    color = tokens.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("servers:cloud-status"),
                )
            }

            if (custom || (cloud && found != null)) {
                Spacer(Modifier.height(12.dp))
                if (custom) Group(title = words.address) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Input(
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
                    if (custom) Button(
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("servers:check"),
                        size = ButtonSize.Lg,
                    )
                } else if (cloud && signUp) {
                    Group(title = words.signUp, hint = words.signUpHint) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(label = words.login) { Input(value = login, onValueChange = { login = it }, label = words.login, modifier = Modifier.testTag("servers:signup-login")) }
                            Field(label = words.fullName) { Input(value = fullName, onValueChange = { fullName = it }, label = words.fullName, modifier = Modifier.testTag("servers:signup-name")) }
                            Field(label = words.email) { Input(value = email, onValueChange = { email = it }, label = words.email, modifier = Modifier.testTag("servers:signup-email")) }
                            PasswordField(password, { password = it }, words.passwordRule, "servers:signup-password")
                        }
                    }
                    Button(
                        text = working ?: words.signUpButton,
                        onClick = {
                            val base = found?.first ?: return@Button
                            val about = found?.second?.name ?: hostOf(base)
                            working = words.signingUp
                            problem = null
                            scope.launch {
                                try {
                                    host.registerServer(base, "$about · ${login.trim()}", login, fullName, email, password) { fraction -> working = words.progress(fraction) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    problem = words.failure(failure)
                                } finally {
                                    working = null
                                }
                            }
                        },
                        enabled = working == null && login.isNotBlank() && fullName.isNotBlank() && password.length >= 8,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("servers:signup"),
                        size = ButtonSize.Lg,
                    )
                    Button(
                        text = words.haveAccount,
                        onClick = { signUp = false; problem = null },
                        variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("servers:to-signin"),
                        size = ButtonSize.Lg,
                    )
                } else {
                    Group(title = words.signIn, hint = if (cloud) words.cloudSignIn else null) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(label = words.login) { Input(value = login, onValueChange = { login = it }, label = words.login, modifier = Modifier.testTag("servers:login")) }
                            PasswordField(password, { password = it }, words.password, "servers:password")
                            Field(label = words.name) { Input(value = name, onValueChange = { name = it }, label = words.name, modifier = Modifier.testTag("servers:name")) }
                        }
                    }
                    Button(
                        text = working ?: words.signIn,
                        onClick = {
                            val base = found?.first ?: return@Button
                            working = words.signingIn
                            problem = null
                            scope.launch {
                                try {
                                    val about = found?.second?.name
                                    // A second account at the same address (or the cloud, where an account is
                                    // the whole point): the login goes into the name, so the switcher tells them apart.
                                    val shown = if ((cloud || found?.second?.cloud == true || host.hasServerAt(base)) && (name.isBlank() || name.trim() == about)) {
                                        "${about ?: hostOf(base)} · ${login.trim()}"
                                    } else {
                                        name
                                    }
                                    val isCloud = cloud || found?.second?.cloud == true
                                    host.addServer(base, shown, login, password, cloud = isCloud) { fraction -> working = words.progress(fraction) }
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("servers:connect"),
                        size = ButtonSize.Lg,
                    )
                    if (cloud && found?.second?.registration == true) {
                        Button(
                            text = words.noAccount,
                            onClick = { signUp = true; problem = null },
                            variant = ButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("servers:to-signup"),
                            size = ButtonSize.Lg,
                        )
                    }
                }
            }
            problem?.let { Problem(it) }
        }
    }
}

/** A password field: the keyboard neither suggests over it nor learns it, and it shows dots. */
@Composable
internal fun PasswordField(value: String, onChange: (String) -> Unit, placeholder: String, tag: String) {
    Field(label = placeholder) {
        Input(
            value = value,
            onValueChange = onChange,
            label = placeholder,
            password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun Problem(text: String) {
    Notice(text, Tones.DANGER, null, null, "servers:problem")
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
            Group(title = connection.profile.name, hint = hostOf(connection.profile.address)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(label = words.login) { Input(value = login, onValueChange = { login = it }, label = words.login, modifier = Modifier.testTag("login:login")) }
                    PasswordField(password, { password = it }, words.password, "login:password")
                }
            }
            Button(
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("login:submit"),
                size = ButtonSize.Lg,
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
            Group(title = words.role, hint = words.demoData) {
                DemoRole.entries.forEachIndexed { index, each ->
                    if (index > 0) ItemSeparator()
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
                            if (each == role) Icon(Lucide.Check, contentDescription = null, tint = tokens.foreground)
                        }
                        Text(text = words.roleHint(each), style = tokens.label, color = tokens.muted)
                    }
                }
            }
            Button(
                text = words.resetDemo,
                onClick = { host.resetDemo() },
                variant = ButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("demo:reset"),
                size = ButtonSize.Lg,
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
        val problems by host.cloudProblems.collectAsState()
        val cloudServer = connection.profile.parent != null
        val scope = rememberCoroutineScope()
        when {
            // A server of a cloud account signs in by the cloud: say why it did not, and try again by the cloud.
            cloudServer && status == ServerStatus.SignedOut -> Notice(
                problems[connection.profile.id] ?: words.connectingViaCloud,
                if (problems[connection.profile.id] != null) Tones.DANGER else Tones.INFO,
                words.viaCloud,
                { scope.launch { host.connectCloudServer(connection.profile.id) } },
                "home:cloud-sign-in",
            )
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
private fun Option(
    title: String,
    body: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    // A card to choose: the chosen one is outlined in the main colour and ticked, the way a plan is
    // chosen on a phone; the others wait with a quiet border.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter, vertical = 5.dp)
            .clip(Kit.shapes.lg)
            .background(Kit.colors.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) Kit.colors.primary else Kit.colors.border, Kit.shapes.lg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeadingIcon(icon, Kit.colors.info)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            KitText(text = title, style = Kit.typography.p.copy(fontWeight = FontWeight.SemiBold))
            KitText(text = body, variant = TextVariant.Muted)
        }
        if (selected) Icon(Lucide.CircleCheck, contentDescription = null, size = 20.dp, tint = Kit.colors.primary)
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
            Group(title = "Серверы".ru(lang, "Servers"), hint = "Порядок — тот же, что в переключателе и для Ctrl+1…9".ru(lang, "The order is the switcher's, and Ctrl+1…9's")) {
                servers.forEachIndexed { index, server ->
                    if (index > 0) ItemSeparator()
                    val isCurrent = server.id == current?.id
                    // A server of a cloud account: under it, and the cloud's to add and remove.
                    val child = server.parent != null
                    Column(modifier = Modifier.padding(start = if (child) 40.dp else 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp).testTag("servers:row-${server.id}")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name = server.name, size = 36.dp, shape = AvatarShape.Square, fill = Kit.colors.primary, contentColor = Kit.colors.onPrimary)
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
                                Input(
                                    value = typed,
                                    onValueChange = { typed = it },
                                    placeholder = server.name,
                                    modifier = Modifier.fillMaxWidth().testTag("servers:name-field"),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        text = "Сохранить".ru(lang, "Save"),
                                        onClick = {
                                            host.rename(server.id, typed)
                                            renaming = null
                                        },
                                        enabled = typed.isNotBlank(),
                                        size = ButtonSize.Sm,
                                        modifier = Modifier.testTag("servers:save-name"),
                                    )
                                    Button(
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
                                    Button(
                                        text = "Удалить".ru(lang, "Remove"),
                                        onClick = {
                                            host.remove(server.id)
                                            removing = null
                                        },
                                        variant = ButtonVariant.Destructive,
                                        size = ButtonSize.Sm,
                                        modifier = Modifier.testTag("servers:confirm-remove"),
                                    )
                                    Button(
                                        text = "Отмена".ru(lang, "Cancel"),
                                        onClick = { removing = null },
                                        variant = ButtonVariant.Ghost,
                                        size = ButtonSize.Sm,
                                    )
                                }
                            }

                            else -> if (child) {
                                Text(
                                    text = "Сервер облачного аккаунта — список ведёт облако".ru(lang, "A server of the cloud account — the cloud keeps this list"),
                                    style = tokens.label,
                                    color = tokens.muted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            } else Row(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Button(
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
                                Button(
                                    text = "↑",
                                    onClick = { host.move(server.id, -1) },
                                    enabled = index > 0,
                                    variant = ButtonVariant.Ghost,
                                    size = ButtonSize.Sm,
                                    modifier = Modifier.testTag("servers:up-${server.id}"),
                                )
                                Button(
                                    text = "↓",
                                    onClick = { host.move(server.id, 1) },
                                    enabled = index < servers.lastIndex,
                                    variant = ButtonVariant.Ghost,
                                    size = ButtonSize.Sm,
                                    modifier = Modifier.testTag("servers:down-${server.id}"),
                                )
                                if (!isCurrent) {
                                    Button(
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
            Button(
                text = "+ " + "Подключить сервер".ru(lang, "Connect a server"),
                onClick = { open(host.addScreen) },
                variant = ButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("servers:add"),
                size = ButtonSize.Lg,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                text = "+ " + HostWords(lang).connectCloud,
                onClick = { open(host.addScreens[1]) },
                variant = ButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).testTag("servers:add-cloud-account"),
                size = ButtonSize.Lg,
            )
            val files by host.filesFlow.collectAsState()
            files?.let { platform ->
                Spacer(Modifier.height(12.dp))
                AuthTransferSection(host, platform, lang)
            }
        }
    }
}

/** Where «Перенос входов» stands: nothing open, a password for a new file, or one for the file just picked. */
private sealed interface Transfer {
    data object Idle : Transfer
    data object Export : Transfer
    class Import(val bytes: ByteArray) : Transfer
}

/**
 * «Перенос входов»: every server's sign-in into a file sealed by a password ([AuthFile]), and such a file onto the
 * list of another app. A copy of the sign-in — the person is told so, and that the file is a key to every server.
 */
@Composable
private fun AuthTransferSection(host: RestoAppHost, files: AuthFiles, lang: String) {
    val tokens = AppTheme
    val scope = rememberCoroutineScope()
    val servers by host.servers.collectAsState()
    var mode by remember { mutableStateOf<Transfer>(Transfer.Idle) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val count = servers.count { !it.isDemo }

    fun reset(next: Transfer) {
        mode = next
        password = ""
        repeat = ""
        problem = null
    }

    fun failure(error: Throwable): String = when ((error as? AuthFileException)?.reason) {
        AuthFileException.Reason.WrongPassword -> "Неверный пароль, или файл изменён".ru(lang, "Wrong password, or the file was changed")
        AuthFileException.Reason.NotAuthFile -> "Это не файл входов RestoApp".ru(lang, "Not a file of RestoApp sign-ins")
        AuthFileException.Reason.Unsupported -> "Файл другой версии приложения — обновите его".ru(lang, "A file of another version of the app — update it")
        null -> ("Не получилось: ".ru(lang, "Failed: ")) + (error.message ?: error::class.simpleName.orEmpty())
    }

    fun launchWork(work: suspend () -> Unit) {
        busy = true
        problem = null
        done = null
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                problem = failure(error)
            } finally {
                busy = false
            }
        }
    }

    Group(title = "Перенос входов".ru(lang, "Transfer sign-ins"), hint = "Входы на все серверы — в файл под паролем и из него в другом приложении"
            .ru(lang, "Sign-ins to every server into a password-protected file, and from it in another app")) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val now = mode) {
                Transfer.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        text = "Сохранить в файл".ru(lang, "Save to a file"),
                        onClick = { done = null; reset(Transfer.Export) },
                        enabled = count > 0 && !busy,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag("servers:export"),
                    )
                    Button(
                        text = "Загрузить из файла".ru(lang, "Load from a file"),
                        onClick = {
                            launchWork {
                                val bytes = files.open() ?: return@launchWork
                                reset(Transfer.Import(bytes))
                            }
                        },
                        enabled = !busy,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag("servers:import"),
                    )
                }

                Transfer.Export -> {
                    Text(
                        text = ("В файл попадут входы на $count сервер(а/ов) — демо-серверы не переносятся. " +
                            "Кто знает пароль файла, войдёт на них без логина и пароля. Для серверов это одно устройство: " +
                            "выход на одном отменит вход и на другом.").ru(
                            lang,
                            "The file gets the sign-ins to $count server(s) — the demo servers stay here. Whoever has the file " +
                                "and its password is signed in to them. To the servers it is one device: signing out on one signs out the other.",
                        ),
                        style = tokens.label,
                        color = tokens.muted,
                        modifier = Modifier.testTag("servers:export-warning"),
                    )
                    PasswordField(password, { password = it }, "Пароль файла".ru(lang, "File password"), "servers:export-password")
                    PasswordField(repeat, { repeat = it }, "Ещё раз".ru(lang, "Once more"), "servers:export-repeat")
                    val short = password.length < AuthFile.MIN_PASSWORD
                    if (password.isNotEmpty() && short) {
                        Text(text = "Не короче ${AuthFile.MIN_PASSWORD} символов".ru(lang, "At least ${AuthFile.MIN_PASSWORD} characters"), style = tokens.label, color = tokens.muted)
                    } else if (repeat.isNotEmpty() && repeat != password) {
                        Text(text = "Пароли не совпадают".ru(lang, "The passwords differ"), style = tokens.label, color = tokens.muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            text = if (busy) "Шифруем…".ru(lang, "Sealing…") else "Сохранить".ru(lang, "Save"),
                            onClick = {
                                val typed = password
                                launchWork {
                                    val bytes = host.exportAuth(typed)
                                    if (files.save("restoapp-sign-ins.${AuthFile.EXTENSION}", bytes)) {
                                        reset(Transfer.Idle)
                                        done = "Файл сохранён. Храните его как пароль.".ru(lang, "The file is saved. Keep it as you keep a password.")
                                    }
                                }
                            },
                            enabled = !busy && !short && repeat == password,
                            size = ButtonSize.Sm,
                            modifier = Modifier.testTag("servers:export-save"),
                        )
                        Button(
                            text = "Отмена".ru(lang, "Cancel"),
                            onClick = { reset(Transfer.Idle) },
                            enabled = !busy,
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                        )
                    }
                }

                is Transfer.Import -> {
                    Text(
                        text = "Пароль, которым файл был сохранён".ru(lang, "The password the file was saved with"),
                        style = tokens.label,
                        color = tokens.muted,
                    )
                    PasswordField(password, { password = it }, "Пароль файла".ru(lang, "File password"), "servers:import-password")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            text = if (busy) "Открываем…".ru(lang, "Opening…") else "Загрузить".ru(lang, "Load"),
                            onClick = {
                                val typed = password
                                launchWork {
                                    val report = host.importAuth(now.bytes, typed)
                                    reset(Transfer.Idle)
                                    done = ("Добавлено серверов: ${report.added}, вход восстановлен: ${report.signedIn}, уже были: ${report.unchanged}")
                                        .ru(lang, "Servers added: ${report.added}, signed in again: ${report.signedIn}, already here: ${report.unchanged}")
                                }
                            },
                            enabled = !busy && password.isNotEmpty(),
                            size = ButtonSize.Sm,
                            modifier = Modifier.testTag("servers:import-load"),
                        )
                        Button(
                            text = "Отмена".ru(lang, "Cancel"),
                            onClick = { reset(Transfer.Idle) },
                            enabled = !busy,
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                        )
                    }
                }
            }
            done?.let { Text(text = it, style = tokens.label, color = tokens.foreground, modifier = Modifier.testTag("servers:transfer-done")) }
        }
        problem?.let { Problem(it) }
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
                Avatar(name = server.title, size = 36.dp, shape = AvatarShape.Square, fill = Kit.colors.primary, contentColor = Kit.colors.onPrimary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(text = server.title, style = tokens.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.foreground)
                    server.subtitle?.let { Text(text = it, style = tokens.label, color = tokens.muted) }
                    if (!connection.profile.isDemo && login != null && status != ServerStatus.SignedOut) {
                        Text(text = words.signedInAs(login!!), style = tokens.label, color = tokens.muted, modifier = Modifier.testTag("settings:signed-in"))
                    }
                }
            }
            ItemSeparator()
        }
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                text = "Управление".ru(lang, "Manage"),
                onClick = { open(host.manageScreen) },
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("settings:servers-manage"),
            )
            Button(
                text = "Подключить".ru(lang, "Connect"),
                onClick = { open(host.addScreen) },
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("settings:servers-add"),
            )
            when {
                connection.profile.isDemo -> Button(
                    text = words.demoTitle,
                    onClick = { open(DemoScreen(host)) },
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("settings:demo"),
                )
                status == ServerStatus.SignedOut -> Button(
                    text = words.signIn,
                    onClick = { open(LoginScreen(host)) },
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("settings:sign-in"),
                )
                else -> Button(
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
