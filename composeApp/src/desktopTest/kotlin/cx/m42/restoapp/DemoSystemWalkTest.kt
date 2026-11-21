package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * «Система», «Настройки» and «Каналы продаж» on the demo server, through the real window: the tiles are on Home, the
 * screens draw, the native screens of the settings and of a job run on it, and the lines between the three apps
 * lead where they say. The PNGs in `composeApp/build/screenshots` are for looking at.
 */
@OptIn(ExperimentalTestApi::class)
class DemoSystemWalkTest {

    private val out = File("build/screenshots").apply { mkdirs() }
    private var now = 1_700_000_000_000

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        // The whole scene, so the editor and the transfer (popups over the page — a second root) are in it.
        val image = (this as? androidx.compose.ui.test.SkikoComposeUiTest)?.captureToImage() ?: onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(out, "demo-system-$name.png"))
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.tap(tag: String) {
        waitUntil(timeoutMillis = 10_000L) { exists(tag) }
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.see(tag: String) = waitUntil(timeoutMillis = 10_000L) { exists(tag) }

    private fun ComposeUiTest.read(text: String) = waitUntil(timeoutMillis = 10_000L) { hasText(text) }

    /**
     * The chevron of the window: it walks the screens of the app; its first screen is a destination of the
     * bottom bar, with no chevron — «Главная» of the bar goes Home from there.
     */
    private fun ComposeUiTest.backHome() {
        var step = 0
        while (!exists("home:summary") && step++ < 8) {
            if (exists("shell:back")) onNodeWithTag("shell:back").performClick() else openHome()
            mainClock.advanceTimeBy(600)
            waitForIdle()
        }
        see("home:summary")
    }

    private fun host() = RestoAppHost(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        store = DesktopServerStore(Files.createTempDirectory("restoapp-demo-system").toFile()),
        debug = true,
        clock = { now },
    ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

    @Test
    fun theHomeOfTheSystemAndItsDoors() = runDesktopComposeUiTest(width = 420, height = 820) {
        val host = host()
        setContent { RestoApp(host) }
        see("home:summary")
        assertApp("system")
        assertApp("channels")
        assertApp("settings")
        shot("01-home")

        openApp("system")
        see("system:detail")
        read("Демо-ресторан")
        shot("02-server-card")

        tap("system:system.restart-midnight")
        read("Перезагрузка запланирована")
        shot("03-restart-planned")

        tap("system:link-system.modules")
        see("system:row-core")
        shot("04-programs")
        tap("system:row-admin-frontend")
        read("Обновление 1.5.0")
        see("system:system.update")
        shot("05-program-card")
        shellBack()
        see("system:row-delivery-zones")
        tap("system:row-delivery-zones")
        read("Миграции не пройдены")
        shot("06-program-migrations")
        shellBack()
        see("system:row-core")
        shellBack()
        see("system:link-system.catalog")

        tap("system:link-system.catalog")
        see("system:row-loyalty-bonuses")
        shot("07-marketplace")
        tap("system:row-loyalty-bonuses")
        read("Недоступен по лицензии")
        assertTrue(!exists("system:system.install"), "what the licence refuses has no button")
        shot("08-marketplace-blocked")
    }

    /** The way out of «Требует настройки», through two apps. */
    @Test
    fun aChannelIsSetUpThroughTheSettings() = runDesktopComposeUiTest(width = 420, height = 820) {
        val host = host()
        setContent { RestoApp(host) }
        see("home:summary")
        openApp("channels")
        see("channels:row-ch-telegram")
        assertTrue(exists("channels:row-channel-types"))
        shot("10-channels")

        tap("channels:row-ch-telegram")
        read("Не заполнено")
        shot("11-channel-needs-setup")
        tap("channels:channels.toggle")
        see("channels:error")
        read("Сначала настройте канал")
        shot("12-channel-refused")

        tap("channels:link-settings.list")
        see("settings:row-SALES_CHANNEL_TELEGRAM_TOKEN")
        assertTrue(!exists("settings:row-CITY"), "the line leads to the rows of the provider")
        shot("13-settings-of-the-provider")
        tap("settings:row-SALES_CHANNEL_TELEGRAM_TOKEN")
        see("settings:settings.update")
        tap("settings:settings.update")
        see("settings:editor")
        shot("14-secret-editor")
        onNodeWithTag("settings:field").performTextInput("123:abc")
        waitForIdle()
        tap("settings:editor-save")
        waitUntil(timeoutMillis = 10_000L) { !exists("settings:editor") }
        read("••••••••")
        shot("15-secret-saved")

        // Out of the settings and into the channel again: it no longer needs a setup.
        shellBack()
        see("settings:row-SALES_CHANNEL_TELEGRAM_TOKEN")
        shot("16-provider-row-filled")
        backHome()
        openApp("channels")
        tap("channels:row-ch-telegram")
        see("channels:detail")
        read("Работает")
        assertTrue(!hasText("Требует настройки"), "the card under the settings is not what it was before them")
        shot("17-channel-ready")
        tap("channels:channels.toggle")
        waitForIdle()
        assertTrue(!exists("channels:error"), "a channel that is set up switches on")
        shot("18-channel-on")
    }

    @Test
    fun theScreensOfTheSettings() = runDesktopComposeUiTest(width = 420, height = 820) {
        val host = host()
        setContent { RestoApp(host) }
        see("home:summary")
        openApp("settings")
        see("settings:row-CITY")
        shot("20-settings")

        tap("settings:chip-env")
        see("settings:row-IMAGES_URL")
        tap("settings:row-IMAGES_URL")
        read("Из окружения")
        assertTrue(!exists("settings:settings.update"), "what the environment pins has no action")
        shot("21-setting-from-env")
        shellBack()
        see("settings:chip-all")
        tap("settings:chip-all")
        see("settings:row-CITY")
        onNodeWithTag("settings:row-WORK_TIME").performScrollTo()
        shot("20b-settings-bottom")
        onNodeWithTag("settings:row-RMS_LAST_SUCCESSFUL_MENU_DISHES_SYNC_AT").performScrollTo()
        shot("20c-settings-long-key")

        tap("settings:row-PROJECT_NAME")
        see("settings:settings.update")
        tap("settings:settings.update")
        see("settings:editor")
        onNodeWithTag("settings:field").performTextInput("Не Латиница")
        see("settings:editor-problem")
        shot("22-setting-refused-by-its-schema")
        tap("settings:editor-cancel")
        waitForIdle()
    }

    /** From a channel with no provider, through the marketplace and the job, to a provider in its place. */
    @Test
    fun aMissingProviderIsInstalledFromItsChannel() = runDesktopComposeUiTest(width = 420, height = 820) {
        val host = host()
        setContent { RestoApp(host) }
        see("home:summary")
        openApp("channels")
        tap("channels:row-ch-vk")
        read("Не работает: провайдер не установлен")
        assertTrue(!exists("channels:channels.toggle"), "no live provider, no switch")
        shot("30-channel-without-provider")

        tap("channels:link-system.catalog")
        see("system:row-sales-channel-vk")
        tap("system:row-sales-channel-vk")
        see("system:system.install")
        shot("31-provider-in-the-marketplace")
        tap("system:system.install")
        see("module-progress")
        read("Установка: sales-channel-vk")
        shot("32-job-going")
        now += 60_000
        waitUntil(timeoutMillis = 30_000L) { exists("module-progress:done") }
        shot("33-job-done")

        // «Готово»: the card of the program under the screen; and the channel now has a provider, and asks for what
        // the provider needs.
        tap("module-progress:finish")
        waitUntil(timeoutMillis = 10_000L) { !exists("module-progress") }
        read("Установлен")
        assertTrue(!exists("system:system.install"), "what is installed has no button to install")
        shot("34-provider-installed")
        // «Этот сервер» asks for the restart: the new module runs only after it (P12).
        backHome()
        openApp("system")
        see("system:summary-notice")
        read("Нужен перезапуск сервера")
        read("Изменены модули")
        shot("35-server-needs-restart")
        backHome()
        openApp("channels")
        tap("channels:row-ch-vk")
        see("channels:detail")
        read("Требует настройки")
        shot("36-channel-now-needs-setup")
    }
}
