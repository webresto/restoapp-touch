/**
 * The universal renderer (plan section 4): one `SuperizerApp` per app the server declares, its
 * screens drawn from `ui` — lists, cards, steppers, toggles, buttons — and never a screen of its
 * own per app. The network is the host's: the app reaches its server only through `RestoServer`.
 *
 * Built from small composables with JSON-shaped parameters, so the script apps of
 * script-apps.plan.md can reuse them.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            api(libs.superizer.core)
            implementation(libs.superizer.ui.theme)
            implementation(libs.superizer.ui)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(project(":demo"))
            implementation(libs.superizer.testing)
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
        }
    }
}
