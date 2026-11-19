import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    // The default hierarchy (iosMain and the rest) and one group more: the two JVMs, so the engine
    // on its own thread is written once for the desktop and Android.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()
    // The web build (`:composeApp:wasmJsBrowser…`): the same screens in a browser tab.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            api(project(":render"))
            implementation(libs.superizer.ui.theme)
            implementation(libs.superizer.ui)
            implementation(libs.superizer.ui.kit)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        val jvmAndAndroidMain by getting
        jvmAndAndroidMain.dependencies {
            // The JS engine of the desktop and Android, one engine for both; iOS takes the same
            // library, the web none.
            implementation(libs.quickjs)
        }
        iosMain.dependencies {
            implementation(libs.quickjs)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(libs.superizer.testing)
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "cx.m42.restoapp.script"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The live test runs the bundle the hook really serves; it finds the server checkout the way the
// other modules do — `-Prestoapp.dev=…`, then this workspace's own `restoapp-dev`, then the one next door.
tasks.withType<Test>().configureEach {
    val dev = (findProperty("restoapp.dev") as String?)?.let { file(it) }
        ?: rootProject.file("restoapp-dev").takeIf { it.exists() }
        ?: rootProject.file("../restoapp-dev")
    systemProperty("restoapp.dev", dev.absolutePath)
    systemProperty("restoapp.tools", rootProject.file("tools").absolutePath)
}
