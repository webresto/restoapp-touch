rootProject.name = "restoapp-mobile-admin"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

/**
 * The library, from its sources next door (D4): RestoApp is built *with* Superizer, and a change
 * there — the global context, say — is visible here on the next build.
 *
 * Build files still write Maven coordinates (`cx.m42.superizer:core:…`), never `project(":core")`,
 * so `SUPERIZER_FROM_MAVEN=1` can resolve the same text out of mavenLocal instead. Superizer is not
 * on Maven Central, so without the checkout and without that there is nothing to build against.
 */
val superizerDir = file("../superizer")
if (System.getenv("SUPERIZER_FROM_MAVEN") != "1") {
    check(superizerDir.exists()) {
        "Superizer's sources are expected at ${superizerDir.canonicalPath}: " +
            "git clone git@github.com:adminization/superizer.git ${superizerDir.canonicalPath}"
    }
    includeBuild(superizerDir)
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// The host. Apps go into apps/<name> next to it, as in Unitool.
include(":composeApp")

// The one native app of a server (order-entry.plan.md): a POS receipt and catalog, which no
// declaration describes. Everything else a server declares is drawn by :render.
include(":apps:pos")

// And the one native *action*: the editor of a promotion of «Маркетинг» (marketing.plan.md, Q43).
// The section itself — the summary, the lists, the cards — is the declaration and :render.
include(":apps:marketing")

/** The screens of «Настройки»: the editor of a value by its JSON Schema, and the transfer. */
include(":apps:settings")

// A server's apps are not modules any more (plan section 4): the server declares them and one
// renderer draws them all. The protocol, the in-process demo server, and that renderer.
include(":protocol")
// Apps that come as JS from the server (D12): the QuickJS runtime and the screens it produces.
include(":script")
include(":demo")
include(":render")
