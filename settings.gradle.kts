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
 * so `SUPERIZER_FROM_MAVEN=1` resolves the same text as a built release instead — the version in
 * `gradle/libs.versions.toml` — from mavenLocal or from Superizer's GitHub Packages. CI builds that
 * way: the library is not compiled again on every run, on the Mac least of all.
 */
val superizerFromMaven = System.getenv("SUPERIZER_FROM_MAVEN") == "1"
val superizerDir = file("../superizer")
if (!superizerFromMaven) {
    check(superizerDir.exists()) {
        "Superizer's sources are expected at ${superizerDir.canonicalPath}: " +
            "git clone git@github.com:adminization/superizer.git ${superizerDir.canonicalPath}"
    }
    includeBuild(superizerDir)
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        /**
         * Superizer's releases (its `publish.yml`). GitHub Packages asks for a token even to read a
         * public package: a classic PAT with `read:packages`, as `SuperizerPackagesUsername` and
         * `SuperizerPackagesPassword` in `~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_*` in
         * the environment (CI: the run's own `GITHUB_TOKEN`, or a `SUPERIZER_PACKAGES_TOKEN` secret if set). Only the library's groups are
         * asked here, and only when the library is not built from its sources.
         *
         * Read as optional values, not `credentials(PasswordCredentials::class)`: that one fails the
         * build at configuration without the token, even when the release is already in mavenLocal.
         */
        if (superizerFromMaven) {
            fun property(name: String) = providers.gradleProperty(name)
                .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_$name"))
            maven {
                name = "SuperizerPackages"
                url = uri("https://maven.pkg.github.com/adminization/superizer")
                credentials {
                    username = property("SuperizerPackagesUsername").orNull
                    password = property("SuperizerPackagesPassword").orNull
                }
                mavenContent { includeGroupAndSubgroups("cx.m42.superizer") }
            }
        }
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

// And another: the progress of a job of «Система», which installs, updates or removes a program
// (system.plan.md, slice C). The app itself is the declaration and :render.
include(":apps:system")

// «Ассистент»: the assistant of the context's own admin panel — adminizer's AI panel of the cloud or of a
// RestoApp server — in a chat drawn here (assistant.plan.md). The model, tools and history are the panel's.
include(":apps:assistant")

// A server's apps are not modules any more (plan section 4): the server declares them and one
// renderer draws them all. The protocol, the in-process demo server, and that renderer.
include(":protocol")
// Apps that come as JS from the server (D12): the QuickJS runtime and the screens it produces.
include(":script")
include(":demo")
include(":render")
