@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.system

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.render.NativeActionScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The progress of a job against a scripted server: what it asks, what it sends, and every ending of a job in
 * words. The whole way through the real manager is `RealServerWalkTest` and the mocha test of the server.
 */
class ModuleProgressTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun method(name: String, title: String, confirm: String? = null): DeclaredMethod =
        json.decodeFromString(
            """{"name":"$name","kind":"mutation","title":"$title","input":{"type":"object"},
               "ui":{"view":"action","control":{"type":"native","screen":"module-progress"}${confirm?.let { ""","confirm":"$it"""" } ?: ""}}}""",
        )

    private fun item(vararg fields: Pair<String, String>): JsonElement = buildJsonObject {
        put("item", buildJsonObject { fields.forEach { (key, value) -> put(key, value) } })
    }

    /** What the screen sent, and what it did with itself. */
    private class Script(val answer: (method: String, params: JsonObject) -> JsonElement) {
        val calls = mutableListOf<String>()
        val submitted = mutableListOf<JsonObject>()
        var closed = 0
    }

    private fun ComposeUiTest.show(method: DeclaredMethod, script: Script) {
        val record = buildJsonObject {
            put("id", "stand-hello")
            put("appId", "stand-hello")
        }
        setContent {
            ModuleProgress(
                NativeActionScope(
                    record = record,
                    method = method,
                    facts = null,
                    lang = "ru",
                    busy = false,
                    failure = null,
                    call = { name, params ->
                        script.calls += name + " " + params["appId"]?.jsonPrimitive?.content.orEmpty() + params["id"]?.jsonPrimitive?.content.orEmpty() + params["when"]?.jsonPrimitive?.content.orEmpty()
                        script.answer(name, params)
                    },
                    submit = { script.submitted += it },
                    submitThen = { params, then ->
                        script.submitted += params
                        then()
                    },
                    notice = {},
                    close = { script.closed += 1 },
                ),
                pollMs = 10,
            )
        }
        waitForIdle()
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private val done = item(
        "id" to "job-1",
        "state" to "done",
        "title" to "Установка: stand-hello",
        "doneText" to "Программа установлена",
        "log" to "Module stand-hello installed successfully",
        "restartText" to "Перезагрузка запланирована на 00:00 по времени сервера.",
        "restartConfirm" to "Перезапустить сервер сейчас?",
    )

    private fun installs(vararg jobAnswers: JsonElement): Script {
        var asked = 0
        return Script { name, _ ->
            when (name) {
                "system.install" -> item("id" to "stand-hello", "jobId" to "job-1")
                "system.job" -> jobAnswers[minOf(asked++, jobAnswers.size - 1)]
                "system.restart" -> item("id" to "server", "restartText" to "Сервер перезапускается")
                else -> error("unexpected $name")
            }
        }
    }

    @Test
    fun aJobStartsAtOnceAndIsWatchedToTheEnd() = runComposeUiTest {
        val running = item("id" to "job-1", "state" to "running", "title" to "Установка: stand-hello", "stepText" to "Скачиваем из маркетплейса")
        val script = installs(running, running, done)
        show(method("system.install", "Установить"), script)

        waitUntil(timeoutMillis = 5_000L) { exists("module-progress:done") }
        // The job's title is the page's: the header of the kit, which has no tag of its own.
        onNodeWithText("Установка: stand-hello").assertExists()
        onNodeWithText("Программа установлена").assertExists()
        onNodeWithTag("module-progress:log").assertTextEquals("Module stand-hello installed successfully")
        // The request for a restart: new code is not running until the process comes back.
        onNodeWithTag("module-progress:restart").assertTextEquals("Перезагрузка запланирована на 00:00 по времени сервера.")
        assertEquals("system.install stand-hello", script.calls.first())
        assertEquals(3, script.calls.count { it == "system.job job-1" })

        // «Готово» starts nothing: it asks for the record as the job left it.
        onNodeWithTag("module-progress:finish").performClick()
        waitForIdle()
        assertEquals("stand-hello", script.submitted.single()["appId"]!!.jsonPrimitive.content)
        assertEquals("job-1", script.submitted.single()["job"]!!.jsonPrimitive.content)
    }

    @Test
    fun whatIsDangerousAsksFirstAndCancellingSendsNothing() = runComposeUiTest {
        val script = Script { _, _ -> error("nothing may be sent") }
        show(method("system.remove", "Удалить", "Удалить программу? Её файлы и настройки будут удалены."), script)

        onNodeWithText("Удалить программу? Её файлы и настройки будут удалены.").assertExists()
        assertTrue(script.calls.isEmpty())
        onNodeWithTag("module-progress:no").performClick()
        waitForIdle()
        assertEquals(1, script.closed)
        assertTrue(script.calls.isEmpty())
    }

    @Test
    fun yesStartsTheRemoval() = runComposeUiTest {
        val removed = item("id" to "job-1", "state" to "done", "doneText" to "Программа удалена")
        val script = Script { name, _ -> if (name == "system.remove") item("jobId" to "job-1") else removed }
        show(method("system.remove", "Удалить", "Удалить программу?"), script)

        onNodeWithTag("module-progress:yes").performClick()
        waitUntil(timeoutMillis = 5_000L) { exists("module-progress:done") }
        onNodeWithText("Программа удалена").assertExists()
        assertEquals("system.remove stand-hello", script.calls.first())
    }

    @Test
    fun aRefusalIsShownInTheWordsOfTheServer() = runComposeUiTest {
        val script = Script { _, _ ->
            throw MobileApiException("conflict", "Сейчас идёт другое задание, дождитесь его конца: stand-slow", 409, false, null, null, null)
        }
        show(method("system.install", "Установить"), script)

        waitUntil(timeoutMillis = 5_000L) { exists("module-progress:refused") }
        onNodeWithText("Сейчас идёт другое задание, дождитесь его конца: stand-slow").assertExists()
        assertEquals(1, script.calls.size)
        onNodeWithTag("module-progress:close").performClick()
        waitForIdle()
        assertEquals(1, script.closed)
    }

    @Test
    fun aFailedJobSaysWhyAndOffersNoRestart() = runComposeUiTest {
        val failed = item("id" to "job-1", "state" to "failed", "errorText" to "Программа недоступна по вашей лицензии")
        show(method("system.install", "Установить"), installs(failed))

        waitUntil(timeoutMillis = 5_000L) { exists("module-progress:error") }
        onNodeWithText("Программа недоступна по вашей лицензии").assertExists()
        assertTrue(!exists("module-progress:restart-now"))
        assertTrue(!exists("module-progress:finish"))
    }

    @Test
    fun aServerThatForgotTheJobIsSaidAndNotAskedAgain() = runComposeUiTest {
        var asked = 0
        val script = Script { name, _ ->
            if (name == "system.install") {
                item("jobId" to "job-1")
            } else {
                asked += 1
                throw MobileApiException("not_found", "Сервер не помнит это задание", 404, false, null, null, null)
            }
        }
        show(method("system.install", "Установить"), script)

        waitUntil(timeoutMillis = 5_000L) { exists("module-progress:lost") }
        onNodeWithText("Сервер не помнит это задание").assertExists()
        mainClock.advanceTimeBy(100)
        waitForIdle()
        assertEquals(1, asked)
    }

    @Test
    fun theConnectionGoingAwayDoesNotEndTheJob() = runComposeUiTest {
        var asked = 0
        val script = Script { name, _ ->
            if (name == "system.install") {
                item("jobId" to "job-1")
            } else {
                asked += 1
                if (asked <= 2) throw MobileApiException(MobileApiException.NETWORK, "no route", null, true, null, null, null)
                done
            }
        }
        show(method("system.install", "Установить"), script)

        waitUntil(timeoutMillis = 5_000L) { exists("module-progress:done") }
        assertTrue(!exists("module-progress:offline"), "it comes back, and the notice goes")
        assertEquals(3, asked)
    }

    @Test
    fun theRequestToRestartAsksThenOrdersItAndSaysSo() = runComposeUiTest {
        val script = installs(done)
        show(method("system.install", "Установить"), script)
        waitUntil(timeoutMillis = 5_000L) { exists("module-progress:restart-now") }

        onNodeWithTag("module-progress:restart-now").performClick()
        waitUntil(timeoutMillis = 5_000L) { exists("module-progress:restart-now:confirm") }
        onNodeWithText("Перезапустить сервер сейчас?").assertExists()
        assertTrue(script.calls.none { it.startsWith("system.restart") })

        onNodeWithTag("module-progress:restart-now:yes").performClick()
        waitUntil(timeoutMillis = 5_000L) { exists("module-progress:restarting") }
        onNodeWithText("Сервер перезапускается").assertExists()
        assertEquals("system.restart now", script.calls.last())
        assertTrue(!exists("module-progress:restart-now"))
    }
}
