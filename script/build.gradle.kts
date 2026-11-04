/**
 * Apps whose code comes from the server as one JS file (js-module.plan.md, D12; the long plan is
 * script-apps.plan.md). The engine is QuickJS — proved by the prototype in `.ai-notes/quick/` — and
 * it lives behind `ScriptEngine`, so common code never names it: a platform without QuickJS brings
 * its own engine (the web host has one already).
 *
 * JS decides, Kotlin draws: the script returns a screen as data, and the components that draw it are
 * the ones `:render` already has.
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
            api(project(":render"))
            implementation(libs.superizer.ui.theme)
            implementation(libs.superizer.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            // The JS engine of the desktop host; Android and iOS take the same library, the web none.
            implementation(libs.quickjs)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
        }
    }
}

// The live test runs the bundle the hook really serves; it finds the server checkout the way the
// other modules do — this workspace's own `restoapp-dev` first.
tasks.withType<Test>().configureEach {
    systemProperty(
        "restoapp.dev",
        (rootProject.file("restoapp-dev").takeIf { it.exists() } ?: rootProject.file("../restoapp-dev")).absolutePath,
    )
    systemProperty("restoapp.tools", rootProject.file("tools").absolutePath)
}
