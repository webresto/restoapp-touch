/**
 * The demo server (plan section 4): protocol 1 answered in process, on the demo data of the old
 * native «Склад» and «Заказы», at two points, with a role switch — so the renderer is seen working
 * without a network, and switching points or roles shows at once what changes.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()

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
