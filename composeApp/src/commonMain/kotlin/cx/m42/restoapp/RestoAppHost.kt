package cx.m42.restoapp

import androidx.compose.runtime.Composable
import cx.m42.superizer.LanguageOption
import cx.m42.superizer.Superizer
import cx.m42.superizer.SuperizerContract
import cx.m42.superizer.host.SuperizerBuilder
import cx.m42.superizer.host.build
import cx.m42.superizer.host.platform.currentPlatform
import cx.m42.superizer.runtime.HostInfo
import cx.m42.superizer.ui.shell.SuperizerShell
import kotlinx.coroutines.CoroutineScope

/**
 * RestoApp, in one function: what makes it a product rather than the framework.
 *
 * Empty for now (D3) — a name, a scheme, two languages and no apps. The apps that administer a
 * server come next, and with them the global context: which backend this session is connected to,
 * and so which apps and which menu the shell offers (.ai-notes, Q1–Q2).
 */
public fun buildRestoApp(
    scope: CoroutineScope,
    debug: Boolean,
    extra: SuperizerBuilder.() -> Unit = {},
): Superizer = Superizer.build(scope) {
    host(
        HostInfo(
            name = "RestoApp",
            version = BuildInfo.VERSION,
            build = BuildInfo.COMMIT + if (BuildInfo.DIRTY) "*" else "",
            platform = currentPlatform(),
            contractVersion = SuperizerContract.VERSION,
            debug = debug,
        ),
    )

    // `restoapp://app/<id>/<path>` and `restoapp://activate?…` — on the desktop, `--link=`.
    scheme(SCHEME)

    languages(
        LanguageOption("ru", "Русский"),
        LanguageOption("en", "English"),
        // Russian: the people who run these servers read it first.
        fallback = "ru",
    )

    extra()
}

/** The root composable every entry point calls. */
@Composable
public fun RestoApp(superizer: Superizer) {
    SuperizerShell(superizer)
}

public const val SCHEME: String = "restoapp"
