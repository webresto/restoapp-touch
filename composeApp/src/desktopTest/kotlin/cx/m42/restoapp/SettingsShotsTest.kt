package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.superizer.app.AppId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.imageio.ImageIO

/**
 * «Настройки» walked on a real server, type by type, with a picture of every screen:
 * `composeApp/build/screenshots-settings`.
 *
 * Not a mock anywhere. The stand is `tools/real-server` — a lifted Sails with adminizer 5.2, core's
 * 102 settings seeded from their manifests and the five of `@webresto/graphql` as an installed module
 * brings them, JWT_SECRET among them. Every screen here is the universal renderer on the server's
 * declaration plus the one screen the client draws itself: the editor of a value, built from the JSON
 * Schema that came with the record (`settings.plan.md`).
 *
 *   cd ../restoapp-dev-marketing && npx tsx ../restoapp-settings/tools/real-server/serve.ts 1342
 *   ./gradlew :composeApp:desktopTest --tests '*SettingsShotsTest' -Prestoapp.serverUrl=http://127.0.0.1:1342
 *
 * Says so and passes when there is no stand to go to.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsShotsTest {

    private val base = System.getProperty("restoapp.serverUrl")?.takeIf { it.isNotBlank() }
    private val out = File("build/screenshots-settings").apply { mkdirs() }
    private val taken = mutableListOf<String>()

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        taken += name
    }

    /** The system clipboard, which is where «Вставить из буфера» reads from on the desktop. */
    private fun clipboard(text: String) {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }

    private fun ComposeUiTest.exists(tag: String) = runCatching { onNodeWithTag(tag).assertExists() }.isSuccess

    /** The action waits for the record it will be built from: the card is drawn before it is read. */
    private fun ComposeUiTest.ready(tag: String) = runCatching { onNodeWithTag(tag).assertIsEnabled() }.isSuccess

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Back to the list of settings, however deep the stack and whatever screen is over it. */
    private fun ComposeUiTest.toList() {
        if (exists("settings:editor-cancel")) {
            onNodeWithTag("settings:editor-cancel").performClick()
            waitForIdle()
            // A screen with edits asks before it leaves (point 13 of the web page's checklist); this
            // walk is done with them by then, and the question is worth a picture once.
            if (exists("settings:editor-leave")) {
                if (!taken.contains("xx-leave-without-saving")) shot("xx-leave-without-saving")
                onNodeWithTag("settings:editor-leave:action").performScrollTo().performClick()
                waitForIdle()
            }
        }
        if (exists("settings:transfer-cancel")) {
            onNodeWithTag("settings:transfer-cancel").performClick()
            waitForIdle()
        }
        while (exists("settings:back")) {
            onNodeWithTag("settings:back").performClick()
            waitForIdle()
        }
        waitUntil(timeoutMillis = 20_000L) { exists("settings:list") }
    }

    /** The card of one setting, found the way a person finds it: by typing part of its key. */
    private fun ComposeUiTest.card(key: String) {
        toList()
        onNodeWithTag("settings:search").performTextClearance()
        onNodeWithTag("settings:search").performTextInput(key)
        waitUntil(timeoutMillis = 20_000L) { exists("settings:row-$key") }
        onNodeWithTag("settings:row-$key").performClick()
        waitUntil(timeoutMillis = 20_000L) { exists("settings:back") && hasText(key) }
    }

    /** The card, then the editor of the value — the screen the client draws itself. */
    private fun ComposeUiTest.editor(key: String) {
        card(key)
        waitUntil(timeoutMillis = 20_000L) { ready("settings:settings.update") }
        onNodeWithTag("settings:settings.update").performClick()
        waitUntil(timeoutMillis = 20_000L) { exists("settings:editor") }
    }

    @Test
    fun everyTypeOfSettingOnTheRealServer() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }

        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-settings-shots").toFile()),
            debug = true,
            device = DeviceInfo("Settings shots", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runDesktopComposeUiTest(width = 420, height = 820) {
            setContent { RestoApp(host) }
            waitForIdle()

            // Connect the stand by its address and sign in as the administrator: «Настройки» is behind
            // the token of the web page, `settings-manager`.
            onNode(hasContentDescription("Открыть меню")).performClick()
            waitForIdle()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-custom").performClick()
            waitForIdle()
            onNodeWithTag("servers:address").performTextInput(address)
            onNodeWithTag("servers:check").performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("servers:found") }
            onNodeWithTag("servers:login").performTextInput("admin")
            onNodeWithTag("servers:password").performTextInput("secret-password")
            onNodeWithTag("servers:connect").performClick()
            waitUntil(timeoutMillis = 60_000L) {
                if (exists("servers:problem")) error("the server refused the sign-in: see servers:problem")
                host.active.value.superizer.registry.get(AppId("settings")) != null && exists("home:tile-settings")
            }
            shot("01-home-with-settings")

            onNodeWithTag("home:tile-settings").performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("settings:list") }
            // The list: every setting by key, its name under it, the value and the type as a badge.
            shot("02-list")
            assertTrue(hasText("ALLOWED_PHONE_COUNTRIES"), "the list shows the settings by key")

            // The chips the web has not got: what is pinned by the environment, and what waits for a restart.
            onNodeWithTag("settings:chip-env").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("settings:row-TZ") }
            shot("03-list-from-the-environment")
            onNodeWithTag("settings:row-TZ").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("settings:back") }
            // Pinned by the environment: the card says so, and there is no «Изменить значение» at all.
            shot("04-card-pinned-by-the-environment")
            assertTrue(!exists("settings:settings.update"), "a setting the environment pins has no action")
            toList()
            onNodeWithTag("settings:chip-all").performClick()
            waitForIdle()

            // ---- a string, with the human wording of its pattern ----
            card("PROJECT_NAME")
            waitUntil(timeoutMillis = 20_000L) { ready("settings:settings.update") }
            shot("05-card-string")
            onNodeWithTag("settings:settings.update").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("settings:editor") }
            shot("06-editor-string")
            onNodeWithTag("settings:field").performTextClearance()
            onNodeWithTag("settings:field").performTextInput("Resto Stand!")
            waitUntil(timeoutMillis = 10_000L) { exists("settings:editor-problem") }
            // The rule in words, before the round trip — and «Сохранить» shut until it is met.
            shot("07-editor-string-refused-here")
            onNodeWithTag("settings:field").performTextClearance()
            onNodeWithTag("settings:field").performTextInput("resto-stand")
            waitUntil(timeoutMillis = 10_000L) { !exists("settings:editor-problem") }
            shot("08-editor-string-ready")
            onNodeWithTag("settings:editor-save").performClick()
            waitUntil(timeoutMillis = 30_000L) { !exists("settings:editor") }
            waitUntil(timeoutMillis = 30_000L) { hasText("resto-stand") }
            // Back on the card, with what the server now holds.
            shot("09-card-saved")

            // ---- a flag: two buttons, as the web has two radio buttons ----
            editor("ALLOW_BONUS_SPENDING")
            shot("10-editor-flag")
            onNodeWithTag("settings:field-true").performClick()
            waitForIdle()
            shot("11-editor-flag-yes")

            // ---- a number with bounds ----
            editor("MEDIAFILE_PARALLEL_TO_DOWNLOAD")
            shot("12-editor-number")
            onNodeWithTag("settings:field").performTextClearance()
            onNodeWithTag("settings:field").performTextInput("9")
            waitUntil(timeoutMillis = 10_000L) { exists("settings:editor-problem") }
            shot("13-editor-number-out-of-bounds")

            // ---- «a whole number or nothing»: `type: ["integer", "null"]` ----
            editor("NOTIFICATION_MAX_COST_PER_MESSAGE")
            shot("14-editor-number-or-nothing")

            // ---- a choice of a few ----
            editor("DEFAULT_LOCALE")
            shot("15-editor-small-choice")

            // ---- a choice of 238: the list opens with a search over it ----
            editor("COUNTRY_ISO")
            shot("16-editor-big-choice")
            onNodeWithTag("settings:field:open").performClick()
            waitForIdle()
            onNodeWithTag("settings:field:search").performTextInput("DE")
            waitUntil(timeoutMillis = 10_000L) { exists("settings:field-DE") }
            shot("16b-editor-big-choice-searched")
            onNodeWithTag("settings:field-DE").performClick()
            waitForIdle()

            // ---- 238 countries, many of them at once ----
            editor("ALLOWED_PHONE_COUNTRIES")
            shot("17-editor-many-of-a-set")
            onNodeWithTag("settings:field:open").performClick()
            waitForIdle()
            onNodeWithTag("settings:field:search").performTextInput("RU")
            waitForIdle()
            shot("18-editor-many-searched")
            onNodeWithTag("settings:field-RU").performClick()
            waitForIdle()
            shot("19-editor-many-picked")

            // ---- an array of lines: a field and a «−» on each, «+ Добавить» under them ----
            editor("AUTH_REDIRECT_ALLOWED_ORIGINS")
            shot("20-editor-array-of-lines")
            onNodeWithTag("settings:field:add").performClick()
            waitForIdle()
            onNodeWithTag("settings:field-0").performTextInput("https://stand.example")
            waitForIdle()
            shot("21-editor-array-of-lines-added")

            // ---- an array of objects: the hours of the restaurant, a card per rule ----
            editor("WORK_TIME")
            shot("22-editor-array-of-objects")
            onNodeWithTag("settings:field:add").performClick()
            waitForIdle()
            shot("23-editor-array-of-objects-added")

            // ---- an object by its properties, with a loose tail kept as JSON ----
            editor("DELIVERY_ZONE_SYNC_CONFIG")
            shot("24-editor-object")
            // `additionalProperties: true`: what the schema does not describe is still the operator's
            // value, and it is shown as JSON rather than dropped on the next save. This is the same
            // field a schema this form cannot draw falls back to.
            onNodeWithTag("settings:field:tail").performTextClearance()
            onNodeWithTag("settings:field:tail").performTextInput("{\"legacyMode\": true}")
            waitForIdle()
            shot("25-editor-object-loose-tail")

            // ---- a map: the keys are the operator's, the values follow one schema ----
            editor("NOTIFICATION_CHANNELS_STATE")
            shot("26-editor-map")

            // ---- a secret: written, never read ----
            card("JWT_SECRET")
            waitUntil(timeoutMillis = 20_000L) { ready("settings:settings.update") }
            shot("27-card-secret")
            assertTrue(!hasText("stand-jwt-secret-0123456789abcdef0123"), "a secret never reaches the client")
            onNodeWithTag("settings:settings.update").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("settings:editor") }
            shot("28-editor-secret")
            onNodeWithTag("settings:field").performTextInput("short")
            waitUntil(timeoutMillis = 10_000L) { exists("settings:editor-problem") }
            shot("29-editor-secret-too-short")

            // ---- a value that only applies at boot: the reminder is left on the screen underneath ----
            editor("SYNC_PRODUCTS_INTERVAL_SECONDS")
            onNodeWithTag("settings:field").performTextClearance()
            onNodeWithTag("settings:field").performTextInput("300")
            waitUntil(timeoutMillis = 10_000L) { !exists("settings:editor-problem") }
            onNodeWithTag("settings:editor-save").performClick()
            waitUntil(timeoutMillis = 30_000L) { !exists("settings:editor") }
            waitUntil(timeoutMillis = 10_000L) { exists("settings:notice") }
            shot("30-restart-reminder")

            // ---- the transfer: the export, the difference, the report ----
            toList()
            onNodeWithTag("settings:add").performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("settings:transfer") }
            waitUntil(timeoutMillis = 30_000L) { !hasText("…") }
            shot("31-transfer-export")
            onNodeWithTag("settings:transfer-copy").performClick()
            waitUntil(timeoutMillis = 10_000L) { exists("settings:transfer-message") }
            shot("32-transfer-copied")

            // A small file of four keys, put where «Вставить из буфера» looks: one that changes, one
            // that does not, one the environment pins and one this server has never heard of. The
            // whole dump of the stand pastes just as well, and reports nothing to change — which is
            // what importing a server's own export should do.
            clipboard(
                """
                {"settings": [
                  {"key": "PROJECT_NAME", "value": "from-the-file"},
                  {"key": "ALLOW_BONUS_SPENDING", "value": false},
                  {"key": "TZ", "value": "Europe/Berlin"},
                  {"key": "NO_SUCH_SETTING", "value": 1}
                ]}
                """.trimIndent(),
            )
            onNodeWithTag("settings:transfer-paste").performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("settings:transfer-selected") }
            shot("33-transfer-difference")
            onNodeWithTag("settings:transfer-select-changed").performClick()
            waitForIdle()
            shot("34-transfer-chosen")
            if (hasText("Применить 0")) {
                println("nothing differs from what the server holds: the report is not reached")
            } else {
                onNodeWithTag("settings:transfer-apply").performClick()
                waitUntil(timeoutMillis = 30_000L) { exists("settings:transfer-close") }
                shot("35-transfer-report")
            }

            println("screenshots in ${out.absolutePath}: ${taken.joinToString(", ")}")
        }
    }
}
