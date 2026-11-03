/**
 * «Заказы»: the orders of the server the host is pointed at — for now a demo set kept in the app's
 * own storage (D7), so each server's storage holds its own orders.
 *
 * An ordinary KMP library that implements the contract, as in Unitool: no `superizer:host`, no
 * `:composeApp`, no other app. Desktop only for now (D5).
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
            api(libs.superizer.core)
            implementation(libs.superizer.ui.theme)
            implementation(libs.superizer.ui)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(libs.superizer.testing)
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
            implementation(kotlin("test"))
        }
    }
}
