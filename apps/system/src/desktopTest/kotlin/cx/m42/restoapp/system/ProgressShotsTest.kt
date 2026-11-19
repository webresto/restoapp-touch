package cx.m42.restoapp.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.render.NativeActionScope
import cx.m42.superizer.ThemeMode
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.KitAccent
import cx.m42.superizer.kit.WindowClassProvider
import cx.m42.superizer.kit.kitTypography
import cx.m42.superizer.kit.palette
import cx.m42.superizer.ui.shell.HostTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Not a check but a look (05-native-apps.md §5.5): the progress of an installation — running, done with a restart
 * to ask for, failed — in 420 / 800 / 1280, light and dark: `apps/system/build/screenshots-native/<width>-<theme>/`.
 */
@OptIn(ExperimentalTestApi::class)
class ProgressShotsTest {

    @Test fun phoneLight() = shots(420, ThemeMode.Light)
    @Test fun phoneDark() = shots(420, ThemeMode.Dark)
    @Test fun tabletLight() = shots(800, ThemeMode.Light)
    @Test fun tabletDark() = shots(800, ThemeMode.Dark)
    @Test fun desktopLight() = shots(1280, ThemeMode.Light)
    @Test fun desktopDark() = shots(1280, ThemeMode.Dark)

    private val method: DeclaredMethod = Json { ignoreUnknownKeys = true }.decodeFromString(
        """{"name":"system.install","kind":"mutation","title":"Установить","input":{"type":"object"},
           "ui":{"view":"action","control":{"type":"native","screen":"module-progress"}}}""",
    )

    private fun item(vararg fields: Pair<String, String>): JsonElement = buildJsonObject {
        put("item", buildJsonObject { fields.forEach { (key, value) -> put(key, value) } })
    }

    private val steps = listOf("Проверяем лицензию", "Скачиваем из маркетплейса", "Проверяем совместимость", "Ставим зависимости", "Загружаем программу")

    private fun running(upTo: Int) = item(
        "id" to "job-1", "state" to "running", "title" to "Установка: sales-channel-vk", "stepText" to steps[upTo],
        "log" to steps.take(upTo + 1).mapIndexed { i, s -> "14:22:0$i  info  $s" }.joinToString("\n"),
    )

    private fun shots(width: Int, theme: ThemeMode) {
        val out = File("build/screenshots-native/$width-${theme.name.lowercase()}").apply { mkdirs() }
        for ((name, ending) in listOf(
            "01-job-running" to null,
            "02-job-done" to item(
                "id" to "job-1", "state" to "done", "title" to "Установка: sales-channel-vk", "doneText" to "Программа установлена",
                "log" to steps.joinToString("\n") { "14:22:0X  info  $it" } + "\nModule sales-channel-vk installed successfully",
                "restartText" to "Новая программа заработает после перезапуска сервера.",
                "restartConfirm" to "Перезапустить сервер сейчас?",
            ),
            "03-job-failed" to item(
                "id" to "job-1", "state" to "failed", "title" to "Установка: sales-channel-vk", "stepText" to steps[2],
                "errorText" to "Программа не подходит этому серверу. Нужен core ≥ 2.15.0, установлен 2.14.1",
                "log" to steps.take(3).joinToString("\n") + "\nerror: core 2.14.1 < 2.15.0",
            ),
        )) {
            var asked = 0
            runDesktopComposeUiTest(width = width, height = 860) {
                val answers = (0..3).map { running(it) } + listOfNotNull(ending)
                setContent {
                    HostTheme(theme, KitAccent.Violet, kitTypography()) {
                        Box(Modifier.fillMaxSize().background(Kit.palette.canvas)) {
                            WindowClassProvider {
                                ModuleProgress(
                                    NativeActionScope(
                                        record = buildJsonObject { put("id", "sales-channel-vk"); put("appId", "sales-channel-vk") },
                                        method = method,
                                        facts = null,
                                        lang = "ru",
                                        busy = false,
                                        failure = null,
                                        call = { called, _ ->
                                            when (called) {
                                                "system.install" -> item("id" to "sales-channel-vk", "jobId" to "job-1")
                                                else -> answers[minOf(asked++, answers.lastIndex)]
                                            }
                                        },
                                        submit = {},
                                        submitThen = { _, _ -> },
                                        notice = {},
                                        close = {},
                                    ),
                                    pollMs = 10,
                                )
                            }
                        }
                    }
                }
                val tag = when {
                    ending == null -> "module-progress:step"
                    name.endsWith("done") -> "module-progress:done"
                    else -> "module-progress:error"
                }
                waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() && (ending != null || asked >= 4) }
                mainClock.advanceTimeBy(200)
                waitForIdle()
                ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "$name.png"))
            }
        }
    }
}
