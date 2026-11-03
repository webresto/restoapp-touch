/**
 * «Склад»: what is left of each dish on the server the host is pointed at — RestoApp core's
 * `Dish.balance`, where -1 is unlimited and 0 is on stop. A demo set in the app's own storage for
 * now (D7).
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
