@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.settings

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cx.m42.restoapp.protocol.jsonObjectOf
import cx.m42.restoapp.render.NativeActions
import cx.m42.restoapp.render.RestoServer
import cx.m42.restoapp.render.ServerApp
import cx.m42.restoapp.render.ServerAppConfig
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * The editor of a setting, opened the way a person opens it: from the list, to the card, to the
 * action the server declares with `control: {type: "native", screen: "setting-editor"}`.
 *
 * What is checked is the whole way through — the record read into a form built from its own schema,
 * the rule said before the round trip, the params that go back, and the refusal that comes back.
 */
class SettingEditorTest {

    @BeforeTest
    fun registerTheScreens() {
        SettingsScreens.register()
    }

    @AfterTest
    fun forgetTheScreens() {
        NativeActions.clear()
        RestartPending.clear()
    }

    private fun AppTestScope<*>.tap(tag: String) {
        runCatching { node(tag).performScrollTo() }
        click(tag)
    }

    private fun AppTestScope<*>.retype(tag: String, text: String) {
        runCatching { node(tag).performScrollTo() }
        ui.onNodeWithTag(tag).performTextReplacement(text)
        idle()
    }

    /** Straight to the editor of one setting, with the server the test wants. */
    private fun editor(key: String, server: SettingsServer = SettingsServer(), block: AppTestScope<ServerAppConfig>.(SettingsServer) -> Unit) {
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            tap("settings:row-$key")
            idle()
            tap("settings:settings.update")
            idle()
            assertShown("settings:editor")
            block(server)
        }
    }

    @Test
    fun theValueIsReadIntoAFormBuiltFromItsOwnSchema() = editor("PROJECT_NAME") { server ->
        // The field holds what the server sent, and the hint is the manifest's wording of the pattern.
        node("settings:field").assertTextEquals("resto-stand")
        assertShown("settings:field:note")
        tap("settings:editor-save")
        idle()
        val (method, params) = server.calls.last()
        assertEquals("settings.update", method)
        assertEquals(jsonObjectOf("id" to JsonPrimitive("PROJECT_NAME"), "value" to JsonPrimitive("resto-stand")), params)
    }

    @Test
    fun aValueThatBreaksTheRuleIsRefusedHereAndSaysWhy() = editor("PROJECT_NAME") { server ->
        retype("settings:field", "Resto Stand!")
        assertShown("settings:editor-problem")
        // «Сохранить» is shut, and nothing was sent anywhere.
        tap("settings:editor-save")
        idle()
        assertTrue(server.calls.none { it.first == "settings.update" }, "a value the schema refuses is not sent")
        retype("settings:field", "ab")
        assertShown("settings:editor-problem")
        retype("settings:field", "another-stand")
        assertAbsent("settings:editor-problem")
    }

    @Test
    fun aRefusalOfTheServerIsShownOnTheScreenThatCausedIt() {
        val server = SettingsServer(refuseWith = "Значение должно содержать только: строчные латинские буквы, цифры и дефис")
        editor("PROJECT_NAME", server) {
            retype("settings:field", "another-stand")
            tap("settings:editor-save")
            idle()
            // The screen stays, with the server's own sentence on it: a native screen covers the
            // notices of the app, and a save thrown out must not look like nothing happening.
            assertShown("settings:editor")
            assertShown("settings:editor-refusal")
        }
    }

    @Test
    fun theDefaultIsPutInTheFieldAndNotWritten() = editor("PROJECT_NAME") { server ->
        tap("settings:editor-default")
        idle()
        node("settings:field").assertTextEquals("restoapp")
        assertTrue(server.calls.none { it.first == "settings.update" }, "«К умолчанию» writes nothing by itself")
        tap("settings:editor-save")
        idle()
        assertEquals(JsonPrimitive("restoapp"), server.calls.last().second["value"])
    }

    @Test
    fun aSecretStartsEmptyAndCannotBeSavedUntilSomethingIsTyped() = editor("JWT_SECRET") { server ->
        assertShown("settings:editor-secret")
        node("settings:field").assertTextEquals("")
        tap("settings:editor-save")
        idle()
        assertTrue(server.calls.none { it.first == "settings.update" }, "an empty field leaves the stored secret alone")
        // Typed, it is checked like any other line of text: this one is too short for its schema.
        retype("settings:field", "short")
        assertShown("settings:editor-problem")
        retype("settings:field", "a-new-secret-value-0123456789abcdefgh")
        assertAbsent("settings:editor-problem")
        tap("settings:editor-save")
        idle()
        assertEquals("settings.update", server.calls.last().first)
    }

    @Test
    fun leavingWithEditsAsksFirst() = editor("PROJECT_NAME") { _ ->
        retype("settings:field", "another-stand")
        tap("settings:editor-cancel")
        idle()
        assertShown("unsaved:question")
        assertShown("settings:editor")
        tap("unsaved:stay")
        idle()
        assertAbsent("unsaved:question")
        tap("settings:editor-cancel")
        idle()
        tap("unsaved:leave")
        idle()
        assertAbsent("settings:editor")
    }

    @Test
    fun saveAndLeaveSendsTheValueThenLeaves() = editor("PROJECT_NAME") { server ->
        retype("settings:field", "another-stand")
        tap("settings:editor-cancel")
        idle()
        tap("unsaved:save")
        idle()
        assertEquals("settings.update", server.calls.last().first)
        assertAbsent("unsaved:question")
        assertAbsent("settings:editor")
    }

    @Test
    fun nothingEditedLeavesWithoutAsking() = editor("PROJECT_NAME") { _ ->
        tap("settings:editor-cancel")
        idle()
        assertAbsent("unsaved:question")
        assertAbsent("settings:editor")
    }

    @Test
    fun aSettingTheEnvironmentPinsHasNoEditorToOpen() {
        val server = SettingsServer()
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            tap("settings:row-TZ")
            idle()
            assertAbsent("settings:settings.update")
        }
    }
}
