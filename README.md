# RestoApp mobile admin

A Kotlin Multiplatform / Compose Multiplatform host for administering the RestoApp servers —
`cx.m42.restoapp`. Built on [Superizer](https://github.com/adminization/superizer) the same way
Unitool (`../client`) is.

Each server is a context (Superizer 0.8's `ContextPort`): the row at the top of the drawer says which
one you are on, a tap lists the others — as ChatGPT and GitHub switch accounts — and every server has
its own apps, menu, Home and storage. The apps are not modules: a server *declares* them — the
`app-mobile-api` hook of restoapp-dev, protocol 1 — and one renderer draws them all from the
declaration. Two exceptions are drawn by the client itself: the POS, which a server declares with
`client: {native: "pos"}` (`apps/pos`, `order-entry.plan.md`) — a receipt with a catalog behind a
«+» is not a list with actions on a record — and the editor of a promotion, one *action* of
«Маркетинг» declared with `control: {type: "native", screen: "promotion-editor"}` (`apps/marketing`,
`marketing.plan.md`). The demo server answers the same protocol in process.
Built for the desktop, Android and the iPhone, written for every platform. The decisions are in
[`.ai-notes`](.ai-notes/README.md), the protocol in `server-apps-and-rights.plan.md`.

## Requirements

| | |
|---|---|
| JDK | 17 (on this machine 21 hangs the Gradle daemon, `.ai-notes` D5) |
| Gradle | none — use the bundled `./gradlew` |
| Superizer | its sources at `../superizer` (`git clone git@github.com:adminization/superizer.git ../superizer`); to edit the library from this project's tree, link it with `ln -s ../superizer superizer` (git ignores the link); `SUPERIZER_FROM_MAVEN=1` builds against mavenLocal instead |
| restoapp-dev | optional: `../restoapp-dev` on branch `mobile-app` with `node_modules` — the live tests run the hook from there, and skip themselves without it. `-Prestoapp.dev=…` points them at another checkout — a git worktree of the server, where both sides of a change are written; without it, a `restoapp-dev` link in the project root wins over `../restoapp-dev`. The tests of a module's app also need `modules/simple-navigation` 1.8.0 in that checkout (`base_modules`, branch `simple-navigation-mobile-ui`) — without it they fail, they do not skip |

## Running it

```bash
./gradlew :composeApp:run              # a 420×820 window
./gradlew desktopTest                  # every module's tests, the live ones included
```

A real server is "Подключить сервер → Свой сервер": its address, then the admin panel's login and
password; the CAPTCHA is solved on the device. Without a RestoApp at hand, the hook on fake deps:

```bash
cd ../restoapp-dev && npx tsx ../restoapp-mobile-admin/tools/fake-server/serve.ts 4851
# then connect to http://127.0.0.1:4851 as stock / secret-password (or admin, operator)
```

## Resto Cloud

«Подключить сервер → Resto Cloud» signs in to the cloud (`https://dev.resto.cloud`, `ServerProfile.CLOUD_ADDRESS`)
with a Cloud Console account: the cloud answers the same protocol 1 (`layers/app-mobile-api` of cloud-panel) and
declares «Серверы» (the account's services: start, stop, restart, delete, a new one), «Аккаунт» (balance,
transactions) and, for an administrator, «Аккаунты». Each account is one more server in the switcher, named
«Resto Cloud · <login>», so several accounts of one cloud live side by side. A cloud panel on this computer:
`./gradlew :composeApp:run --args=--cloud=http://localhost:17280` (or `RESTOAPP_CLOUD_URL`). The walk through the
window against it:

```bash
./gradlew :composeApp:desktopTest --rerun --tests '*CloudServerTest*' -Pcloud.url=http://localhost:17280 \
  -Pcloud.login=user2 -Pcloud.password=user2 -Pcloud.login2=admin -Pcloud.password2=admin
```

## iPhone

`iosApp/` is the Xcode project, `composeApp/src/iosMain` the host on iOS. Linking needs a Mac with
Xcode; only the klibs compile without it (`./gradlew :composeApp:compileKotlinIosArm64`).
`.github/workflows/ios.yml` builds on `main`, on `ios-ipa` and by hand, and leaves an **unsigned**
`RestoApp-<run>.ipa` in the run's artifacts. A phone installs it only after it is signed.

What does not work on iOS yet (`order-entry.plan.md`, «Платформы»):

- A real server: there is no PoW solver for the login CAPTCHA, so only the demo servers open. iOS also
  refuses plain `http://` addresses until `Info.plist` allows them.
- Storage per server: `PrefsStorage` has one suite, so Home, the language and the apps' data are shared.
- The device token is a protected file, not the Keychain.
- The app has no icon yet.

## Android

`composeApp/src/androidMain` is the host on Android — «RestoApp touch», `org.restoapp.touch`
(`.ai-notes` D14). Built on guibot2 only, never on the laptop:

```bash
./gradlew :composeApp:assembleDebug        # composeApp/build/outputs/apk/debug/composeApp-debug.apk
./gradlew :composeApp:bundleRelease        # composeApp/build/outputs/bundle/release/composeApp-release.aab
```

The release is signed by the upload key when `.secrets/keystore.properties` or the `ANDROID_KEY*`
variables are there, and unsigned otherwise — `RELEASING.md`. A real server works here (the PoW
solver is the desktop's, `jvmAndAndroidMain`), and each server has its own `SharedPreferences`.
Plain `http://` addresses are refused, as on iOS.

«Управление серверами → Перенос входов» (Android and the desktop) saves every server but the demo ones, with its
device token, to a file sealed by a password (`AuthFile.kt`: PBKDF2-HMAC-SHA256, ChaCha20, HMAC-SHA256 — common
code, so a file written on one platform opens on any) and loads such a file in another app. A copy of the
sign-in, not a move: to the servers both are one device. The file dialogs are the platform's (`AuthFiles`:
`DesktopAuthFiles`, `AndroidAuthFiles` over the Storage Access Framework); iOS has none yet, and no such section.

## How it is put together

```
protocol/                        protocol 1: the declaration and envelopes, HttpMobileApi over
                                 Superizer's NetworkService, the PoW solver (CryptoPuzzleSolver, JVM
                                 and Android: jvmAndAndroidMain)
demo/                            DemoBackend: the protocol in process — «Блюда» as the hook has them,
                                 «Заказы» in the shape of slice 2, «Клиенты», the POS and
                                 «Маркетинг», two points, four roles
render/                          ServerApp: one SuperizerApp per declared app; SchemaEditor — a form
                                 over a JSON Schema a record brought with it (settings now, the forms
                                 of models next); a stack of frames —
                                 list, detail, the links of a record (`_open`, `_links`) and «Назад»;
                                 stepper, toggle, buttons, a form, a «+» on the list, the point
                                 switch, the catalog shell; NativeActions — the screens the client
                                 has for one action; RestoServer — the app's only door
apps/pos/                        PosApp: the POS of a server, drawn natively — the drafts of the point
                                 and the receipt; every number comes from the server
apps/marketing/                  the editor of a promotion: the one action of «Маркетинг» the client
                                 draws itself — the discount, the products and groups from the
                                 catalog, a gift, the hours
apps/settings/                   the two screens of «Настройки»: the editor of one value, built from
                                 the JSON Schema that came with the record, and the export/import
composeApp/src/
  commonMain/Servers.kt          a server: name and address; the two demo servers of a fresh install
  commonMain/ServerSession.kt    one server connected: token, cached declaration, point, error codes
  commonMain/RestoAppHost.kt     the servers as contexts: one Superizer per server, rebuilt when its menu changes
  commonMain/ServerScreens.kt    connect a server, sign in, the demo's role, the Home banner, Settings
  desktopMain/main.kt            the window; ⌘/Ctrl+1…9 switch servers
  desktopMain/DesktopServerStore ~/.restoapp/servers.json, and ~/.restoapp/servers/<id>/ per server
  androidMain/MainActivity.kt    the activity; one host per process
  androidMain/AndroidServerStore the same layout under the app's files, SharedPreferences per server
tools/fake-server/serve.ts       the app-mobile-api hook on fake deps, for development and live tests
tools/real-server/serve.ts       the whole backend on sails-disk: adminizer, core, the hook — what the
                                 walkthrough tests drive the window against
tools/real-server/walk.ts        the mobile API walked against that stand, printed step by step
tools/pow-testdata.mjs           PoW tasks from the real crypto-puzzle, for the solver's tests
```

Per server, `~/.restoapp/servers/<id>/` holds `settings.properties` (Home, snapshots, language),
`session.json` (the device token, mode 600), `declaration.json` (the menu without a network),
`place.json` (the point last picked) and, for a demo, `demo.json`.

The contract — `schema/declaration.schema.json` of the hook — is copied into
`protocol/src/desktopTest/resources`; the demo server is tested against it. When the hook's schema
changes, copy it again.
