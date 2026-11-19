import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The demo server (plan section 4): protocol 1 answered in process, on the demo data of the old
 * native «Склад» and «Заказы», at two points, with a role switch — so the renderer is seen working
 * without a network, and switching points or roles shows at once what changes.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
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
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "cx.m42.restoapp.demo"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
