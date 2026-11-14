package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.render.ServerStatus
import cx.m42.superizer.app.AppId
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Resto Cloud in the app, through the window, against a running cloud panel (cloud-panel,
 * `layers/app-mobile-api`): «Подключить сервер → Resto Cloud», the cloud account's login and password
 * (the CAPTCHA of adminizer solved by the Kotlin solver), Home with «Серверы» and «Аккаунт», the
 * balance, a service created from the list, started, stopped and deleted from its card, its history;
 * then a second
 * account of the same cloud, which is one more server of the switcher under its own name, and the
 * switch back.
 *
 *   ./gradlew :composeApp:desktopTest --tests '*CloudServerTest*' \
 *     -Pcloud.url=http://localhost:17280 -Pcloud.login=user2 -Pcloud.password=user2 \
 *     -Pcloud.login2=admin -Pcloud.password2=admin
 *
 * Skipped without `cloud.url`. Screenshots — `composeApp/build/screenshots-cloud`. Starting a service
 * installs it into the location's cluster: on a laptop the location is a fake one and the service
 * ends up in `error`, which is what the walk expects; against dev.resto.cloud do not run it.
 */
@OptIn(ExperimentalTestApi::class)
class CloudServerTest {

    private val url = System.getProperty("cloud.url")?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val login = System.getProperty("cloud.login") ?: "user2"
    private val password = System.getProperty("cloud.password") ?: "user2"
    private val login2 = System.getProperty("cloud.login2")
    private val password2 = System.getProperty("cloud.password2")
    private val out = File("build/screenshots-cloud").apply { mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    /** waitUntil, and on a timeout a screenshot `failed-<name>` of what was on screen. */
    private fun ComposeUiTest.waitOrShot(name: String, timeoutMillis: Long, condition: () -> Boolean) {
        runCatching { waitUntil(timeoutMillis = timeoutMillis, condition = condition) }.onFailure { shot("failed-$name"); throw it }
    }

    /** The text a node shows, joined. */
    private fun ComposeUiTest.textOf(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    private fun ComposeUiTest.exists(tag: String) = runCatching { onNodeWithTag(tag).assertExists() }.isSuccess

    private fun ComposeUiTest.hasText(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /** Tags of the nodes whose tag starts with [prefix]: the chips of a form field, one per value. */
    private fun ComposeUiTest.tagsStartingWith(prefix: String): List<String> =
        onAllNodes(SemanticsMatcher("tag starts with $prefix") { node -> node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true })
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.TestTag) }

    /** The switcher's sheet, opened and settled: it slides in, and a tap during the slide misses its row. */
    private fun ComposeUiTest.openSwitcher() {
        openMenu()
        onNodeWithTag("context:row").performClick()
        waitUntil(timeoutMillis = 5_000L) { exists("context:sheet") }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    /**
     * A context of the sheet: brought into view by scrolling the list — up first, then down — then tapped.
     * (performScrollTo does not move the sheet's own scroll, which the shell's drawer sits around.)
     */
    private fun ComposeUiTest.pick(id: String) {
        fun visible() = onNodeWithTag("context:item-$id").fetchSemanticsNode().boundsInRoot.height > 20f
        val moves = List(8) { true } + List(16) { false }
        for (down in moves) {
            if (visible()) break
            scrollList(if (down) -200f else 200f)
            waitForIdle()
        }
        assertTrue(visible(), "context $id is not in view")
        onNodeWithTag("context:item-$id").performClick()
    }

    /** Scrolls the sheet's list by [dy] pixels, as a finger or a wheel does: positive — towards its end. */
    private fun ComposeUiTest.scrollList(dy: Float) {
        // In the unmerged tree: the sheet is clickable (it swallows taps meant for the scrim) and merges what is in it.
        onNodeWithTag("context:list", useUnmergedTree = true).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
    }

    private fun ComposeUiTest.openMenu() {
        onNode(hasContentDescription("Открыть меню")).performClick()
        waitForIdle()
    }

    /** An app of the server in force: «Главная» of the drawer, then its tile (a title alone may also be a heading). */
    private fun ComposeUiTest.openApp(appId: String, tag: String) {
        openMenu()
        onAllNodesWithText("Главная").onFirst().performClick()
        waitUntil(timeoutMillis = 10_000L) { exists("home:tile-$appId") }
        onNodeWithTag("home:tile-$appId").performScrollTo().performClick()
        waitUntil(timeoutMillis = 15_000L) { exists(tag) }
    }

    /** «Подключить сервер → Resto Cloud» and the account's login: the cloud becomes the server in force. */
    private fun ComposeUiTest.connectCloud(host: RestoAppHost, login: String, password: String) {
        openMenu()
        onNodeWithTag("context:row").performClick()
        waitForIdle()
        // «Подключить облачный аккаунт» — the second "+" row: straight to the cloud's sign-in.
        onNodeWithTag("context:add-add-cloud").performClick()
        // server.about of the cloud, then the form of the account.
        waitUntil(timeoutMillis = 15_000L) { exists("servers:login") }
        shot("01-connect-cloud")
        onNodeWithTag("servers:login").performTextInput(login)
        onNodeWithTag("servers:password").performTextInput(password)
        onNodeWithTag("servers:connect").performClick()
        // The PoW of adminizer's CAPTCHA takes a few seconds.
        waitUntil(timeoutMillis = 60_000L) {
            host.current.value?.title?.endsWith("· $login") == true && host.active.value.superizer.registry.get(AppId("services")) != null
        }
        waitUntil(timeoutMillis = 15_000L) { exists("home:tile-services") }
    }

    @Test
    fun aCloudAccountSeesItsServicesBalanceAndDrivesAService() {
        val base = url ?: run {
            println("SKIPPED: no -Pcloud.url=<address of a running cloud panel>")
            return
        }
        val dir = Files.createTempDirectory("restoapp-cloud").toFile()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            device = DeviceInfo("Cloud test", "desktop"),
            solver = CryptoPuzzleSolver,
            cloudAddress = base,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        val title = "Кафе из приложения ${System.currentTimeMillis() % 100000}"
        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()
            connectCloud(host, login, password)
            val cloudId = host.current.value!!.id
            assertEquals("Resto Cloud · $login", host.current.value!!.title)
            onNodeWithTag("home:tile-account").assertExists()
            shot("02-home")

            // «Аккаунт»: the balance and the profile — the home record of the app.
            onNodeWithTag("home:tile-account").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("account:detail") }
            waitUntil(timeoutMillis = 15_000L) { hasText("Баланс, кредитов") }
            assertTrue(hasText(login), "the account card does not show the login")
            shot("03-account")
            onAllNodesWithText("Операции по балансу").onFirst().performScrollTo().performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("account:list") }
            shot("04-transactions")

            // «Серверы»: a new one from the form of the list, then its card.
            openApp("services", "services:list")
            onNodeWithTag("services:add").performClick()
            waitUntil(timeoutMillis = 5_000L) { exists("services:form:title") }
            onNodeWithTag("services:form:title").performTextInput(title)
            shot("05-new-service")
            onNodeWithTag("services:form:submit").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("services:detail") && hasText(title) }
            waitUntil(timeoutMillis = 15_000L) { exists("services:services.power-start") }
            assertTrue(hasText("Остановлен"), "a new service is not stopped")
            shot("06-service-card")

            // Start: with credits the cloud takes it (starting → error on a fake cluster), without — it says why.
            onNodeWithTag("services:services.power-start").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) {
                exists("services:services.power-stop") || exists("services:error") || hasText("кредитов", substring = true)
            }
            shot("07-service-started")
            if (exists("services:services.power-stop")) {
                // Stop asks first: it is the «danger» button.
                onNodeWithTag("services:services.power-stop").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000L) { exists("services:services.power-stop:yes") }
                shot("08-stop-confirm")
                onNodeWithTag("services:services.power-stop:yes").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("services:services.power-start") || hasText("Останавливается") }
                shot("09-service-stopped")
            }

            // The history of the service is a section of its card: what was done, by whom.
            waitUntil(timeoutMillis = 15_000L) { hasText("Вы: Создан «$title»", substring = true) }
            onAllNodesWithText("Вы: Создан", substring = true).onFirst().performScrollTo()
            shot("10-history")

            // Deletion asks too, and the service leaves the list.
            onNodeWithTag("services:services.delete-delete").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000L) { exists("services:services.delete-delete:yes") }
            onNodeWithTag("services:services.delete-delete:yes").performClick()
            waitUntil(timeoutMillis = 20_000L) { hasText("Удаляется") }
            shot("11-service-deleting")

            // The same protocol from the session: the list no longer has it.
            waitForIdle()
            val listed = runBlocking { host.active.value.session.call("services", "services.list", JsonObject(emptyMap())) }
            val titles = listed.jsonObject["items"]!!.jsonArray.map { it.jsonObject["title"].toString().trim('"') }
            assertTrue(title !in titles, "a deleted service is still listed: $titles")

            // A second account of the same cloud: one more server, its own name, its own token.
            if (login2 != null && password2 != null) {
                connectCloud(host, login2, password2)
                val second = host.current.value!!
                assertTrue(second.id != cloudId, "the second account replaced the first one")
                assertEquals("Resto Cloud · $login2", second.title)
                shot("12-second-account")
                if (host.active.value.session.declaration.value?.user?.isAdministrator == true) {
                    // An administrator: every account, and the services of all of them.
                    onNodeWithTag("home:tile-accounts").assertExists()
                    onNodeWithTag("home:tile-accounts").performClick()
                    waitUntil(timeoutMillis = 15_000L) { exists("accounts:list") && hasText(login) }
                    shot("13-accounts")
                    onAllNodesWithText(login).onFirst().performClick()
                    waitUntil(timeoutMillis = 15_000L) { exists("accounts:detail") }
                    shot("14-account-of-user")
                }
                openMenu()
                onNodeWithTag("context:row").performClick()
                waitUntil(timeoutMillis = 5_000L) { exists("context:sheet") }
                shot("15-switcher")
                onNodeWithTag("context:item-$cloudId").performClick()
                runCatching {
                    waitUntil(timeoutMillis = 15_000L) { host.current.value?.id == cloudId }
                    waitUntil(timeoutMillis = 15_000L) { exists("services:root") || exists("home:tile-services") }
                }.onFailure { shot("16-debug"); throw it }
                assertTrue(host.active.value.session.status.value != ServerStatus.SignedOut, "the first account is signed out after the switch")
                shot("16-back-to-first")
            }
        }
        println("screenshots in ${out.absolutePath}")
    }

    private val fleetLogin = System.getProperty("cloud.fleetLogin")
    private val fleetPassword = System.getProperty("cloud.fleetPassword") ?: "fleet-pass-1"
    private val localUrl = System.getProperty("restoapp.localUrl")

    /**
     * The whole bundle: a RestoApp connected by its address and a cloud account side by side. The account brings all
     * its servers (fifteen — the list scrolls) under itself in the switcher; a pick goes to the account or straight to
     * one of its servers, which the app signs in to through the cloud and administers exactly as the local one — the
     * same apps of the same RestoApp mobile API. A server started in the account shows its new status in the switcher at once;
     * one that does not answer says so on Home.
     *
     * Needs `-Pcloud.fleetLogin=` from `test/mobile-app/fleet-fixture.mjs` of cloud-panel (its «Кафе 07 · живой
     * RestoApp» points at a running tools/fake-server) and `-Prestoapp.localUrl=` — another running tools/fake-server.
     */
    @Test
    fun aCloudAccountBringsItsServersNextToALocalOneAndEachIsAdministered() {
        val base = url
        val fleet = fleetLogin
        val local = localUrl
        if (base == null || fleet == null || local == null) {
            println("SKIPPED: needs -Pcloud.url, -Pcloud.fleetLogin (test/mobile-app/fleet-fixture.mjs) and -Prestoapp.localUrl")
            return
        }
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-cloud-fleet").toFile()),
            debug = true,
            device = DeviceInfo("Cloud fleet test", "desktop"),
            solver = CryptoPuzzleSolver,
            cloudAddress = base,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        var startedId = ""
        // Three PoW CAPTCHAs (the local RestoApp, the cloud, the server of the cloud) and two dozen screens: past the default minute.
        runComposeUiTest(testTimeout = 5.minutes) {
            setContent { RestoApp(host) }
            waitForIdle()

            // 1. A RestoApp of one's own, by its address — the local one.
            openMenu()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-custom").performClick()
            waitForIdle()
            onNodeWithTag("servers:address").performTextInput(local)
            onNodeWithTag("servers:check").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("servers:found") }
            onNodeWithTag("servers:login").performTextInput("admin")
            onNodeWithTag("servers:password").performTextInput("secret-password")
            onNodeWithTag("servers:name").performTextClearance()
            onNodeWithTag("servers:name").performTextInput("Локальный RestoApp")
            onNodeWithTag("servers:connect").performClick()
            waitOrShot("local", 60_000L) { host.current.value?.title == "Локальный RestoApp" && host.active.value.superizer.registry.get(AppId("dishes")) != null }
            val localId = host.current.value!!.id
            waitUntil(timeoutMillis = 15_000L) { exists("home:tile-dishes") }
            shot("40-local-restoapp-home")

            // 2. The cloud account: all its servers come with it, under it.
            connectCloud(host, fleet, fleetPassword)
            val accountId = host.current.value!!.id
            waitUntil(timeoutMillis = 15_000L) { host.servers.value.count { it.parent == accountId } == 15 }
            val children = host.servers.value.filter { it.parent == accountId }
            assertEquals(children.map { it.name }.sorted(), children.map { it.name }, "the servers of the account are not in order")
            val order = host.servers.value.map { it.id }
            assertEquals(order.indexOf(accountId) + 1, order.indexOf(children.first().id), "the servers do not follow their account")
            shot("41-cloud-account-home")

            // 3. The switcher: the local server, the account and its fifteen servers — the list scrolls.
            openSwitcher()
            onNodeWithTag("context:item-$localId").assertExists()
            shot("42-switcher-account-and-servers")
            scrollList(5_000f)
            waitForIdle()
            shot("43-switcher-scrolled-to-the-end")

            // 4. Straight to a server of the account: the app signs in to it through the cloud.
            val live = children.first { it.name.contains("живой") }
            pick("${live.id}")
            waitUntil(timeoutMillis = 60_000L) {
                host.current.value?.id == live.id && host.active.value.superizer.registry.get(AppId("dishes")) != null
            }
            waitUntil(timeoutMillis = 15_000L) { exists("home:tile-dishes") }
            shot("44-cloud-server-home")

            // 5. Administered as the local one: the same «Блюда», a dish switched off and on at a point.
            onNodeWithTag("home:tile-dishes").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("dishes:list") }
            onNodeWithTag("dishes:chip-all").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("dishes:row-d3") }
            shot("45-cloud-server-dishes")
            // «Морс» switched off at this point and on again — each time read back from the server itself.
            val toggle = "dishes:row-d3:dishes.setEnabled"
            fun soldHere(): String = runBlocking {
                host.active.value.session.call("dishes", "dishes.get", buildJsonObject { put("id", JsonPrimitive("d3")) })
            }.jsonObject["item"]!!.jsonObject["placeEnable"].toString()
            val before = soldHere()
            waitOrShot("toggle-row", 15_000L) {
                runCatching { onNodeWithTag(toggle, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width > 0f }.getOrDefault(false)
            }
            waitForIdle()
            onNodeWithTag(toggle, useUnmergedTree = true).performClick()
            waitOrShot("toggle-off", 15_000L) { soldHere() != before }
            println("cloud server: «Морс» sold at «Ленина, 5»: $before → ${soldHere()}")
            waitForIdle()
            shot("46-cloud-server-dish-changed")
            onNodeWithTag(toggle, useUnmergedTree = true).performClick()
            waitOrShot("toggle-back", 15_000L) { soldHere() == before }

            // 6. And the local one, next to it in the same switcher.
            openSwitcher()
            shot("47-switcher-on-cloud-server")
            pick("$localId")
            waitOrShot("back-local", 30_000L) { host.current.value?.id == localId && exists("home:tile-dishes") }
            shot("48-back-on-local-restoapp")

            // 7. Started in the cloud: the switcher shows it at once.
            openSwitcher()
            pick("$accountId")
            waitUntil(timeoutMillis = 30_000L) { host.current.value?.id == accountId && exists("home:tile-services") }
            // Any stopped server of the fleet (a stop never finishes on a laptop's fake cluster, so earlier runs leave some «stopping»).
            val toStart = host.servers.value.first { it.parent == accountId && it.status == "stopped" && it.id != live.id && it.name != "Кафе 01" }
            onNodeWithTag("home:tile-services").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("services:list") && hasText(toStart.name) }
            onAllNodesWithText(toStart.name).onFirst().performScrollTo().performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("services:services.power-start") }
            onNodeWithTag("services:services.power-start").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("services:services.power-stop") }
            val started = toStart.id
            startedId = started
            waitUntil(timeoutMillis = 15_000L) { host.servers.value.firstOrNull { it.id == started }?.status == "starting" }
            openSwitcher()
            shot("49-switcher-started-in-the-cloud")

            // 8. A server that does not answer: Home says why, and offers to try again through the cloud.
            val silent = children.first { it.name == "Кафе 01" }
            pick("${silent.id}")
            waitUntil(timeoutMillis = 30_000L) { host.current.value?.id == silent.id && hasText("Сервер не отвечает", substring = true) }
            shot("50-cloud-server-not-answering")

            // Stop what was started, so the fixture account costs nothing.
            openSwitcher()
            pick("$accountId")
            waitUntil(timeoutMillis = 30_000L) { host.current.value?.id == accountId }
        }
        runBlocking {
            val stop = host.active.value.session.call("services", "services.power", kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive(host.servers.value.first { it.id == startedId }.cloudId))
                put("to", kotlinx.serialization.json.JsonPrimitive("stop"))
            })
            println("stopped ${startedId}: ${stop.toString().take(80)}")
        }
        println("screenshots in ${out.absolutePath}")
    }

    /**
     * A person without an account: «Resto Cloud → Нет аккаунта? Зарегистрироваться» makes one (registration is open
     * in test mode, which a laptop panel is), the app is signed in at once and the welcome credits are there. Then
     * everything a user manages, from the app only: the profile (name, language of the console), a new service, its
     * settings (name, RestoApp version, plan), start, stop, deletion.
     */
    @Test
    fun aNewUserSignsUpInTheAppAndManagesTheAccountAndAService() {
        val base = url ?: run {
            println("SKIPPED: no -Pcloud.url=<address of a running cloud panel>")
            return
        }
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-cloud-signup").toFile()),
            debug = true,
            device = DeviceInfo("Cloud sign-up test", "desktop"),
            solver = CryptoPuzzleSolver,
            cloudAddress = base,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }
        val stamp = System.currentTimeMillis() % 1_000_000
        val newLogin = "app$stamp"
        val service = "Кофейня $stamp"

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()
            openMenu()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add-add-cloud").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("servers:to-signup") }
            onNodeWithTag("servers:to-signup").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000L) { exists("servers:signup-login") }
            onNodeWithTag("servers:signup-login").performTextInput(newLogin)
            onNodeWithTag("servers:signup-name").performTextInput("Новый Пользователь")
            onNodeWithTag("servers:signup-email").performTextInput("$newLogin@example.com")
            onNodeWithTag("servers:signup-password").performTextInput("app-password-1")
            shot("20-signup-form")
            onNodeWithTag("servers:signup").performScrollTo().performClick()
            waitUntil(timeoutMillis = 60_000L) {
                host.current.value?.title == "Resto Cloud · $newLogin" && host.active.value.superizer.registry.get(AppId("services")) != null
            }
            waitUntil(timeoutMillis = 15_000L) { exists("home:tile-account") }
            shot("21-signed-up-home")

            // The account: the welcome credits, then the profile edited in place.
            onNodeWithTag("home:tile-account").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("account:account.update") && hasText("Новый Пользователь") }
            shot("22-new-account")
            onNodeWithTag("account:account.update").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000L) { exists("account:account.update:form:fullName") }
            onNodeWithTag("account:account.update:form:fullName").performTextClearance()
            onNodeWithTag("account:account.update:form:fullName").performTextInput("Анна Кофейная")
            if (exists("account:account.update:form:locale-ru")) onNodeWithTag("account:account.update:form:locale-ru").performClick()
            shot("23-profile-form")
            onNodeWithTag("account:account.update:form:submit").performScrollTo().performClick()
            waitUntil(timeoutMillis = 15_000L) { hasText("Анна Кофейная") && !exists("account:account.update:form") }
            shot("24-profile-saved")

            // A service of the new account: create, then its settings.
            openApp("services", "services:list")
            onNodeWithTag("services:add").performClick()
            waitUntil(timeoutMillis = 5_000L) { exists("services:form:title") }
            onNodeWithTag("services:form:title").performTextInput(service)
            onNodeWithTag("services:form:submit").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("services:detail") && exists("services:services.update") }
            shot("25-new-service")

            onNodeWithTag("services:services.update").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000L) { exists("services:services.update:form:title") }
            onNodeWithTag("services:services.update:form:title").performTextClearance()
            onNodeWithTag("services:services.update:form:title").performTextInput("$service — центр")
            val versions = tagsStartingWith("services:services.update:form:imageVersion-")
            // Another version than the one it has: the last chip that is not «next».
            versions.lastOrNull { !it.endsWith("-next") }?.let { onNodeWithTag(it).performScrollTo().performClick() }
            shot("26-service-settings-form")
            onNodeWithTag("services:services.update:form:submit").performScrollTo().performClick()
            waitUntil(timeoutMillis = 15_000L) { hasText("$service — центр") && hasText("Изменены настройки", substring = true) }
            shot("27-service-settings-saved")

            // Start (the welcome credits pay for it), stop, delete.
            onNodeWithTag("services:services.power-start").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("services:services.power-stop") }
            shot("28-service-started")
            onNodeWithTag("services:services.power-stop").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000L) { exists("services:services.power-stop:yes") }
            onNodeWithTag("services:services.power-stop:yes").performClick()
            waitUntil(timeoutMillis = 20_000L) { !exists("services:services.power-stop") }
            shot("29-service-stopped")
            onNodeWithTag("services:services.delete-delete").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000L) { exists("services:services.delete-delete:yes") }
            onNodeWithTag("services:services.delete-delete:yes").performClick()
            waitUntil(timeoutMillis = 20_000L) { hasText("Удаляется") }
            shot("30-service-deleting")

            // The balance has the welcome credits and the charge for the minutes it ran.
            openApp("account", "account:detail")
            waitUntil(timeoutMillis = 15_000L) { hasText("Баланс, кредитов") }
            onAllNodesWithText("Операции по балансу").onFirst().performScrollTo().performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("account:list") && hasText("Приветственные кредиты") }
            shot("31-new-account-transactions")
        }
        println("signed up as $newLogin; screenshots in ${out.absolutePath}")
    }
}
