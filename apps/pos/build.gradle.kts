import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * POS — the one app of a server this client draws itself (`order-entry.plan.md`): a receipt with a
 * catalog behind a «+». The server still owns it — rights, the point and every number come from the
 * mobile API through `RestoServer`, and nothing on this side adds anything up.
 *
 * `commonMain` only, no JVM API: the owner's answer to Q28 is "every platform", so the module is
 * written for all of them from the first line even while only the desktop is built (D5).
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            // The server service and the renderer's building blocks — rows, notices, sheets.
            api(project(":render"))
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

android {
    namespace = "cx.m42.restoapp.pos"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
