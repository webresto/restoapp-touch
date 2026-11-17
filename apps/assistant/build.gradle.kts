import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * «Ассистент» (`.ai-notes/assistant.plan.md`): the assistant of adminizer's AI panel of the context — Resto Cloud
 * Assistant of a cloud account, the RestoApp assistant of a server — in a chat this client draws. One mechanism for
 * both: the declaration says `client: {native: "assistant"}`, `assistant.session` gives the address of the panel's
 * agent and a short session of the account, and the app speaks the panel's own routes (runs and their SSE stream).
 *
 * `commonMain` only. The network is Superizer host's platform client (`defaultHttpClient`): a stream outlives the
 * fifteen seconds of the host's NetworkService, which also cannot read a body as it comes.
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
            api(project(":render"))
            api(libs.superizer.core)
            implementation(libs.superizer.ui.theme)
            implementation(libs.superizer.ui)
            implementation(libs.superizer.ui.kit)
            // defaultHttpClient(): OkHttp, CIO or Darwin — the engine the host already ships.
            implementation(libs.superizer.host)
            implementation(libs.ktor.client.core)
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
    namespace = "cx.m42.restoapp.assistant"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// AssistantLiveTest: a panel with its assistant on — `-Passistant.url=http://127.0.0.1:17320 -Passistant.login=user1
// -Passistant.password=user1` (the cloud panel with CLOUD_AI_* on tools/fake-llm, or dev.resto.cloud); skipped without.
tasks.withType<Test>().configureEach {
    listOf("assistant.url", "assistant.login", "assistant.password").forEach { name ->
        (findProperty(name) as String?)?.let { systemProperty(name, it) }
    }
}
