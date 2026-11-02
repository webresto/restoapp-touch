# RestoApp mobile admin

A Kotlin Multiplatform / Compose Multiplatform host for administering the RestoApp servers —
`cx.m42.restoapp`. Built on [Superizer](https://github.com/adminization/superizer) the same way
Unitool (`../client`) is: the host is a list of apps, and each app is a module of its own.

Empty for now: no apps yet, and desktop only. What comes next — apps per backend, and a global context that decides
which of them the shell offers — is in [`.ai-notes`](.ai-notes/README.md).

## Requirements

| | |
|---|---|
| JDK | 21 |
| Gradle | none — use the bundled `./gradlew` |
| Superizer | its sources at `../superizer` (`git clone git@github.com:adminization/superizer.git ../superizer`), also reachable as `./superizer` — a symlink, so the library is edited from this project's tree; `SUPERIZER_FROM_MAVEN=1` builds against mavenLocal instead |

## Running it

```bash
./gradlew :composeApp:run              # a 420×820 window
./gradlew :composeApp:desktopTest      # the tests
```

## How it is put together

```
composeApp/src/
  commonMain/RestoAppHost.kt   the host: a name, a scheme, the languages, and — later — register() calls
  desktopMain/main.kt          the window
  desktopTest/                 the host starts and draws the shell
```

Adding an app is what it is in Unitool: `include(":apps:<name>")` in `settings.gradle.kts`, a module
that depends on `superizer:core` and `superizer:ui` only, `implementation(project(":apps:<name>"))` in
`composeApp/build.gradle.kts`, and `register(<Name>App())` in `RestoAppHost.kt`.
