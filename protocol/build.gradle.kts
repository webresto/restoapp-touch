import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The RestoApp mobile API, protocol 1, as the client sees it (server-apps-and-rights.plan.md,
 * section 5): the declaration, the envelopes and the error codes; the HTTP client over Superizer's
 * `NetworkService`; the PoW solver for the login CAPTCHA.
 *
 * No Compose and no host: the renderer, the demo server and the host all speak through this.
 * The solver is JVM code, in `jvmAndAndroidMain`: the desktop and Android share it, iOS has no solver
 * yet, and it and the web will need their own big integers.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    // The default hierarchy (iosMain and the rest) and one group more: the two JVMs, so the JDK's
    // big integers and crypto are written once for the desktop and Android.
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

    sourceSets {
        commonMain.dependencies {
            api(libs.superizer.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.json.schema.validator)
            implementation(project(":demo"))
        }
    }
}

android {
    namespace = "cx.m42.restoapp.protocol"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The live test starts the hook of ../restoapp-dev on fake deps (tools/fake-server); it finds the
// checkout through this, and skips itself when there is none. `-Prestoapp.dev=…` points it at
// another one — a git worktree of the server, which is where a change of both sides is written.
tasks.withType<Test>().configureEach {
    // Which checkout of the server: `-Prestoapp.dev=…` when given, then `restoapp-dev` in the project
    // root (a symlink in a workspace with a server of its own, so two workspaces never share one),
    // then the `../restoapp-dev` next door.
    val dev = (findProperty("restoapp.dev") as String?)?.let { file(it) }
        ?: rootProject.file("restoapp-dev").takeIf { it.exists() }
        ?: rootProject.file("../restoapp-dev")
    systemProperty("restoapp.dev", dev.absolutePath)
    systemProperty("restoapp.tools", rootProject.file("tools").absolutePath)
}
