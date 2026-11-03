# RestoApp mobile admin

A Kotlin Multiplatform / Compose Multiplatform host for administering the RestoApp servers —
`cx.m42.restoapp`. Built on [Superizer](https://github.com/adminization/superizer) the same way
Unitool (`../client`) is: the host is a list of apps, and each app is a module of its own.

Each server is a context (Superizer 0.8's `ContextPort`): the row at the top of the drawer says which
one you are on, a tap lists the others — as ChatGPT and GitHub switch accounts — and every server has
its own apps, menu, Home and storage. For now the servers are demos and so are the two apps, «Заказы»
and «Склад», modelled on RestoApp core. Desktop only. The decisions are in [`.ai-notes`](.ai-notes/README.md).

## Requirements

| | |
|---|---|
| JDK | 21 |
| Gradle | none — use the bundled `./gradlew` |
| Superizer | its sources at `../superizer` (`git clone git@github.com:adminization/superizer.git ../superizer`), also reachable as `./superizer` — a symlink, so the library is edited from this project's tree; `SUPERIZER_FROM_MAVEN=1` builds against mavenLocal instead |

## Running it

```bash
./gradlew :composeApp:run              # a 420×820 window
./gradlew desktopTest                  # the tests: the host and both apps
```

## How it is put together

```
composeApp/src/
  commonMain/Servers.kt          a server: name, address, which apps; the two demo servers
  commonMain/RestoAppHost.kt     the servers as contexts: one Superizer per server, switching between them
  commonMain/ServerScreens.kt    "Connect a server", "Manage servers", the Settings block
  desktopMain/main.kt            the window; ⌘/Ctrl+1…9 switch servers
  desktopMain/DesktopServerStore ~/.restoapp/servers.json, and ~/.restoapp/servers/<id>/ per server
apps/orders/                     «Заказы»: demo orders and their states, as RestoApp core's kanban
apps/stock/                      «Склад»: demo balances — unlimited, counted, on stop
```

Adding an app is what it is in Unitool: `include(":apps:<name>")` in `settings.gradle.kts`, a module
that depends on `superizer:core` and `superizer:ui` only, `implementation(project(":apps:<name>"))` in
`composeApp/build.gradle.kts`, and a line in `ServerApps` (`Servers.kt`) — after which any server
that lists its id has it in the menu.
