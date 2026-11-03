/**
 * Prototype, not part of the app: QuickJS (quickjs-kt) runs a Preact bundle, Compose renders the
 * mutations it commits. A standalone build — run it from the repo root with
 * `./gradlew -p .ai-notes/quick/kt desktopTest` (JDK 17, see ../README.md).
 */
plugins {
    kotlin("multiplatform") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

kotlin {
    jvm("desktop")
    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
                implementation("org.jetbrains.compose.ui:ui:1.11.1")
                implementation(compose.desktop.currentOs)
                implementation("io.github.dokar3:quickjs-kt:1.0.15")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.compose.ui:ui-test:1.11.1")
            }
        }
    }
}

/** ../js/dist/bundle.js: npm install on the first run, then esbuild. */
val bundleJs by tasks.registering(Exec::class) {
    val js = file("../js")
    workingDir = js
    commandLine("sh", "-c", "[ -d node_modules ] || npm ci --silent; npm run --silent build")
    inputs.dir(js.resolve("src"))
    inputs.file(js.resolve("package-lock.json"))
    outputs.file(js.resolve("dist/bundle.js"))
}

tasks.withType<Test>().configureEach {
    dependsOn(bundleJs)
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
