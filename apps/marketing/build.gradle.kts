/**
 * The editor of a promotion — one *action* of «Маркетинг» the client draws itself
 * (`marketing.plan.md`, Q43): the discount, the products and groups it is for, a gift, the hours.
 * Everything else of the section is the universal renderer on the server's declaration.
 *
 * `commonMain` only, no JVM API: every platform is coming (D5, Q28), and the catalog shell it walks
 * the menu with is the renderer's, shared with the «+» of the POS.
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
            // The native-action registry, the catalog shell, the rows and the notices.
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
