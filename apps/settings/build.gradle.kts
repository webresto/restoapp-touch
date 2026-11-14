import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The screens «Настройки» draws itself: the editor of one value, and the transfer of them all
 * (`settings.plan.md`). The list, the card, the search and the chips of the app are the universal
 * renderer on the server's declaration, exactly as everywhere else — what cannot be declared is a
 * form over a JSON Schema that arrives with the record, and that form lives in `:render`
 * (`SchemaEditor`), because the forms of models will want it too.
 *
 * `commonMain` only, no JVM API: every platform is coming (D5, Q28).
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
            // The native-action registry, the schema form, the rows and the notices.
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
    namespace = "cx.m42.restoapp.settings"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
