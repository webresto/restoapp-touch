/**
 * The RestoApp mobile API, protocol 1, as the client sees it (server-apps-and-rights.plan.md,
 * section 5): the declaration, the envelopes and the error codes; the HTTP client over Superizer's
 * `NetworkService`; the PoW solver for the login CAPTCHA.
 *
 * No Compose and no host: the renderer, the demo server and the host all speak through this.
 * Desktop only for now (D5); the solver is JVM code, iOS and the web will need their own big integers.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    jvm("desktop")

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

// The live test starts the hook of ../restoapp-dev on fake deps (tools/fake-server); it finds the
// checkout through this, and skips itself when there is none.
tasks.withType<Test>().configureEach {
    systemProperty("restoapp.dev", rootProject.file("../restoapp-dev").absolutePath)
    systemProperty("restoapp.tools", rootProject.file("tools").absolutePath)
}
