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

// A server's apps are not modules any more (plan section 4): the server declares them and one
// renderer draws them all. The protocol, the in-process demo server, and that renderer.
include(":protocol")
include(":demo")
include(":render")
