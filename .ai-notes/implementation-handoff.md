# Передача в работу: «Каналы продаж» и «Система» — что, в каком порядке и какими файлами

> Версия 1.0, 2026-11-04. Это **наряд для исполняющего агента**, а не план: планы —
> `sales-channels.plan.md` (v1.0) и `system.plan.md` (v1.1), и все решения приняты там. Здесь —
> очередь срезов, файлы на каждый срез, чем проверять и что обязательно покрыть тестом.
> Вопросы Q51–Q56 и Q57–Q67 в планах **не закрыты владельцем**. Пока ответа нет, действует правило
> этих заметок: берётся рекомендация из плана («по дефолту»), и если владелец скажет иначе — срез
> переделывается. Ни один шаг ниже не зависит от альтернативного варианта.

## 0. Что прочитать перед первой строкой кода

| Файл | Зачем |
|---|---|
| `.ai-notes/system.plan.md` (v1.1) | программы, перезапуск, мастер установки, дом «Системы»; задачи C34–C45, вопросы Q57–Q67 |
| `.ai-notes/sales-channels.plan.md` (v1.0) | каналы продаж; задачи C29–C33, вопросы Q51–Q56 |
| `.ai-notes/settings.plan.md` (v1.1, **соседняя сессия**) | схемный редактор форм в `:render`, `NativeActionScope.failure`, шторка с поиском — **от этого зависят срезы 7 и 9** |
| `.ai-notes/server-apps-and-rights.plan.md` | протокол 1, права, устройство декларации; «Статус реализации» |
| `.ai-notes/real-server-walkthrough.md` | как поднять настоящий сервер и прогнать по нему клиент |
| `api/hooks/app-mobile-api/docs.md` (в `restoapp-dev`) | контракт хука одним файлом |
| `local_modules/core/docs/SalesChannels.md` | правила провайдеров каналов |

Планы лежат в `/prj/restoapp-mobile-admin/.ai-notes/` и **не закоммичены**. Первым коммитом своей
ветки клиента перенеси их в свою ветку (как `settings.plan.md` уже перенесён в `main`), чтобы план
ехал вместе с кодом.

## 1. Рабочее место

**Отдельный workspace, своя ветка, от самого свежего коммита.** Несколько сессий работают в общих
чекаутах — не трогай чужие.

Два репозитория:

| Репозиторий | Откуда откалываться | Куда | Права |
|---|---|---|---|
| Клиент — `webresto/restoapp-touch` | `/prj/restoapp-main`, ветка `main`, коммит **`26032c1`** (самый свежий; `/prj/restoapp-mobile-admin` на `mobile-client` `0eab7de` **отстал**, брать его нельзя) | новый worktree, например `/prj/restoapp-system`, ветка `system-apps` | push в `origin` **есть** |
| Сервер — `webresto/restoapp` | `/prj/restoapp-dev`, ветка `mobile-app`, коммит **`d5ad834`** | новый worktree, например `/prj/restoapp-dev-system`, ветка `system-api` | **только локально**: `webresto/restoapp` и `webresto/core` на чтение |

```bash
cd /prj/restoapp-main      && git worktree add /prj/restoapp-system     -b system-apps  26032c1
cd /prj/restoapp-dev       && git worktree add /prj/restoapp-dev-system -b system-api   d5ad834
```

Две вещи, без которых новый worktree не работает:

- **Сервер: `node_modules` в worktree нет.** Нужна ферма симлинков (`node_modules`,
  `local_modules/core/node_modules`, `local_modules/graphql/node_modules`, `.tmp`, `database.json`,
  `modules/`) на `/prj/restoapp-dev`, иначе тесты незаметно побегут против основного чекаута.
  Проверяй `require.resolve` и путь в выводе первого же прогона.
- **Клиент: собирать на JDK 17.** На этой машине JDK 21 вешает Gradle-демон насмерть (README, D5):
  `JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-17.0.21+9/Contents/Home ./gradlew …`.
  Симлинки `superizer -> ../superizer` и `restoapp-dev -> ../restoapp-dev` в корне нового worktree
  создай руками — они не в git.

**Настоящий сервер владельца не трогать ничем**: ни установкой модуля, ни удалением, ни
перезапуском. Всё — только на поднятом стенде (`tools/real-server/serve.ts`) и на `restoapp-dev`.

## 2. Как работать

- **Срез за срезом, снизу вверх**: сервер → декларация → клиент → тест → скриншот. Срез считается
  готовым, только когда он виден на живом стенде (раздел 5) и закрыт тестами (раздел 4).
- **Задачи `C…` делаются до того среза, которому они нужны**, а не «потом»: в планах у каждого среза
  написано, какие именно. C35/C36 — до кнопки перезапуска, C34/C37 — до установки, C41/C42 — до
  выключателя, C45 — до мастера.
- **Ничего не переписывать «по дороге».** Если по пути нашлась ошибка, которой нет в планах — пиши
  её в план (новым `C…`) и спрашивай, а не правь молча.
- **Коммиты как в этих репозиториях**: `feat(system): …`, `test(channels): …`, `docs(ai-notes): …` —
  посмотри `git log --oneline -20`, язык сообщений английский.
- **Контракт — один на всех.** `schema/declaration.schema.json` в `restoapp-dev` и
  `protocol/.../Declaration.kt` в клиенте правятся **в одном шаге** (срез 5), иначе контрактные
  тесты клиента (`DemoContractTest`) разъедутся с сервером.

## 3. Очередь

| № | Срез | План | Нужны задачи | Клиентский код | Зависит от |
|---|---|---|---|---|---|
| 1 | Дом «Системы» и чтение программ | `system.plan.md` A | C38 | нет | — |
| 2 | Каналы: список, карточка, выключатель, удаление | `sales-channels.plan.md` A+B | C29, C30, C31, C32 | нет | — |
| 3 | Перезапуск сервера | `system.plan.md` B | **C35, C36** | нет | 1 |
| 4 | Установка, обновление, удаление программ (задания) + поиск | `system.plan.md` C | **C34, C37**, C43, C44 | `apps/system`, экран `module-progress` | 1, 3 |
| 5 | Типы каналов, рекомендации, переходы между приложениями, `open: {url}` | `sales-channels.plan.md` C | — | контракт + рендер | 2, 4 |
| 6 | *(соседняя сессия)* схемный редактор форм, шторка с поиском, `NativeActionScope.failure` | `settings.plan.md` B+C | C23–C28 | `:render` | — |
| 7 | Редактор канала | `sales-channels.plan.md` D | — | `apps/channels`, экран `channel-editor` | 2, 6 |
| 8 | Включение, выключение, миграции программ | `system.plan.md` D | **C41, C42** | нет | 4 |
| 9 | Мастер установки | `system.plan.md` E | **C39, C45** | экран `wizard-step` | 6, 8 |
| 10 | Хвосты | оба плана E/F | C33, C40, Q62, Q66 | мелочи | всё выше |

Шаги 1 и 2 независимы — можно делать параллельно или в любом порядке; дальше очередь жёсткая.
Шаг 6 делает другая сессия: если к сроку его нет, делай 8 вперёд 7, а 7 и 9 держи последними.

### Шаг 1. Дом «Системы» и чтение программ

**Сервер** (`/prj/restoapp-dev-system`):

- создать `api/hooks/app-mobile-api/apps/system.ts` — `registerApp({id: "system", …})`,
  `system.home`, `system.modules`, `system.module`;
- создать `api/hooks/app-mobile-api/adapters/system.ts` — **единственное место**, которое знает
  Waterline и `sails.hooks['app-manager-proto']` (C38): список из `Module.find({isDeleted != true})`
  + память `getModules()` + `MM_SYSTEM_MODULES`, кеш `latestVersion`;
- переписать `api/hooks/app-mobile-api/adapters/modules.ts` на ту же функцию, чтобы декларация и
  приложение не расходились (там же свести имя параметра лицензии: `token` против `license`);
- `api/hooks/app-mobile-api/lib/hook.ts` — импорт и вызов `registerSystem` рядом с
  `registerMarketing`;
- слова — `api/hooks/app-mobile-api/locales/ru.json`, `en.json`
  (`mobile.app.system.title`, `mobile.category.system`, бейджи состояний);
- ссылки дома — `_links` с `app: "settings"` / `app: "channels"` только через `ctx.canOpen` (пока
  тех приложений нет, ссылок просто не будет — это нормально и должно быть в тесте).

**Тесты**: `tests/mobile-api.system.test.ts` (образец — `tests/mobile-api.marketing.test.ts`,
фальшивые `deps`).

**Проверка на стенде**: поднять `tools/real-server/serve.ts`, войти `admin`/`secret-password`,
увидеть «Систему», два системных модуля (core, graphql) и `simple-navigation`, если он подложен
симлинком в `modules/`.

**Скриншоты**: дом «Системы», список программ, карточка программы.

### Шаг 2. Каналы: чтение и выключатель

**Core** (`/prj/restoapp-dev-system/local_modules/core`):

- создать `lib/sales-channels/view.ts` (C29) — `describeChannel()` со словами; на неё перевести
  `lib/adminpanel/src/controller/sales-channels-helpers.ts` (`mapChannel`) **и**
  `hook/mcp/sales-channels.ts` (`mapChannel`) — две копии исчезают, обе страницы и MCP продолжают
  работать как раньше (это проверяется существующими тестами
  `test/old/integration/models/sales-channel-providers.test.ts`);
- создать `lib/sales-channels/apply.ts` (C30) — уникальность `key`, слаг, «тип не меняется», какие
  поля вообще берутся; на неё перевести `lib/adminpanel/src/controller/upsert-sales-channel.ts` и
  MCP-инструмент;
- `lib/SalesChannelProviders.ts` — отдать текст отказа наружу переводимым (C31);
- `models/SalesChannel.ts` — кеш готовности на 5–10 с (C32), рядом с `refreshStatus`.

**Сервер** (хук): `apps/channels.ts`, `adapters/channels.ts`, регистрация в `lib/hook.ts`, слова в
`locales/*`; методы `channels.list`, `channels.get`, `channels.toggle`, `channels.delete`.
`since` в `channels.list` **не объявлять** (иначе клиент начнёт опрашивать провайдеров).

**Тесты**: `tests/mobile-api.channels.test.ts` — права двух уровней, отказы правил, `describeItem`.

**Проверка на стенде**: канал `web-storefront` без живого провайдера — включение отказывает
человеческой фразой; у пользователя без `sales-channels-manage` нет действий и нет `settingsUrl`.

**Скриншоты**: список каналов, карточка с плашкой «провайдер не установлен», отказ при включении.

### Шаг 3. Перезапуск

**Хук менеджера модулей** (`api/hooks/app-manager-proto`):

- `helpers/restartScheduler.js` — состояние в базу и один таймер на кластер (C36); перезапуск
  уровня приложения через pm2 с запасом на `process.exit(0)` (C35); на это же перевести
  `actions/restart.js` и оставить `api/mcp/tools/restart.js` в покое;
- миграция под таблицу состояния/заданий — `api/hooks/app-manager-proto/migrations/` (по образцу
  существующих трёх).

**Сервер** (хук мобильного API): `system.restart` в `apps/system.ts`, состояние перезапуска в
`system.home`.

**Тесты**: сервер — состояние видно из «другого воркера» (подделать `pm_id`), планирование
идемпотентно; клиент — подтверждение обязательно.

**Проверка**: на стенде в кластере (`PM2_MODE=CLUSTER`, `-i 2`) — перезапуск поднимает оба воркера,
плашка одинаковая из любого.

**Скриншоты**: плашка «Перезапуск запланирован», подтверждение, экран ожидания.

### Шаг 4. Установка, обновление, удаление + поиск

**Хук менеджера модулей** — главная работа:

- создать `lib/modules/jobs.ts` (C34): `startInstall`, `startUpdate`, `startRemove` отдают `jobId`
  сразу, пишут шаг, хвост лога и исход в таблицу; замок — один на кластер (C37);
- `actions/upgrade.js`, `actions/remove.js` — становятся тонкими вызовами этой службы (веб-страница
  начинает показывать тот же ход работы);
- `helpers/moduleHelper.ts` (`canBeInstalled`) + `helpers/loadHelper.ts` (`checkModule`, код 5) —
  отказ по совместимости человеческой фразой (C43);
- `actions/remove.js` — проверять `modulemanager.forbidDelete` (C44).

**Сервер** (хук мобильного API): `system.catalog`, `system.catalogItem`, `system.install`,
`system.update`, `system.remove`, `system.job`; поиск по установленным (`q` по названию, `appId`,
описанию, `keywords`) и чипсы `kind`.

**Клиент** (`/prj/restoapp-system`):

- `settings.gradle.kts` — `include(":apps:system")`;
- новый модуль `apps/system/` по образцу `apps/marketing/` (`build.gradle.kts`,
  `src/commonMain/kotlin/cx/m42/restoapp/system/SystemScreens.kt`, `ModuleProgress.kt`);
- `composeApp/src/commonMain/kotlin/cx/m42/restoapp/RestoAppHost.kt` — рядом с
  `MarketingScreens.register()` добавить `SystemScreens.register()`.

**Тесты**: хук — жизненный цикл задания и «идёт другое задание»; установщик — рядом с
`tests/app-manager-installer.test.js` (учти: `npm test` берёт только `tests/**/*.test.ts`, так что
новый тест пиши на `.ts` или расширь glob); клиент — `apps/system/src/desktopTest/…` по образцу
`apps/marketing/src/desktopTest/kotlin/.../PromotionEditorTest.kt`.

**Проверка на стенде**: поддельный реестр (как в `app-manager-installer.test.js`) — установка живёт
дольше 15 с и доходит до конца; экран хода можно закрыть и вернуться.

**Скриншоты**: каталог с поиском, карточка модуля в каталоге, экран хода (идёт / удачно / отказ).

### Шаг 5. Типы каналов и переходы

- контракт: `api/hooks/app-mobile-api/schema/declaration.schema.json` (`definitions/open` → `url`),
  `lib/registry.ts` (валидация), и **в том же шаге** клиент:
  `protocol/src/commonMain/kotlin/cx/m42/restoapp/protocol/Declaration.kt` (`Open.url`),
  `render/src/commonMain/kotlin/cx/m42/restoapp/render/ServerAppInstance.kt` (`openLink` →
  `runtime.navigation.openUrl`), `render/.../Screens.kt` (`LinkRow`);
- сервер: `channels.types`, `channels.type` в `apps/channels.ts`, ссылки «Установить провайдера» и
  «Все каналы продаж в маркетплейсе» на `system.catalog` через `ctx.canOpen`;
- контрактные тесты клиента: `protocol/src/desktopTest/.../DemoContractTest.kt`.

**Проверка**: с карточки типа «Telegram bot» — переход в каталог «Системы», уже отфильтрованный по
`appId`; у человека без `modules-process-upgrade` этой строки нет.

**Скриншоты**: список типов, карточка типа, переход в каталог.

### Шаг 7. Редактор канала

- клиент: `settings.gradle.kts` → `include(":apps:channels")`, новый `apps/channels/` с экраном
  `channel-editor` поверх схемного редактора и шторки с поиском из шага 6;
  регистрация в `RestoAppHost.kt`;
- сервер: `channels.save`, `channels.create` на `lib/sales-channels/apply.ts`.

**Тесты**: отказ по занятой платформе, отказ по уникальности ключа, слаг из кириллического названия
(«Сайт» → `sajt`), тип заблокирован у существующего канала.

**Скриншоты**: редактор пустой и заполненный, отказ сервера в редакторе.

### Шаг 8. Включение, выключение, миграции

- `api/hooks/app-manager-proto/helpers/system.ts` — `setModuleEnabled()` вместо закороченного
  `updateSailsRC()` (C41): база + `.sailsrc` + миграции при включении + обязательный перезапуск,
  всё заданием;
- `helpers/migrationsHelper.ts` — видимое состояние миграций и «догнать» (C42);
- сервер: `system.setEnabled`, `system.migrate`.

**Проверка на стенде обязательна**: выключенный модуль **после перезапуска не загружается**; на
не-postgres «догнать миграции» не предлагается вовсе.

**Скриншоты**: карточка выключенной программы, карточка с непройденными миграциями.

### Шаг 9. Мастер установки

- `api/hooks/app-manager-proto/lib/installStepper/` — `describeWizard()`, `describeStep()`,
  `submitStep()` (C45); факт о мастере в декларацию (C39);
- сервер: `system.wizard`, `system.step`, `system.submitStep`;
- клиент: экран `wizard-step` поверх схемного редактора.

**Проверка на стенде**: модуль с обязательной незаполненной настройкой включается **с телефона**, без
захода в админку; шаг `ejs` честно отправляет в веб.

### Шаг 10. Хвосты

C33 (адаптер канала `mobile-pos` в `api/hooks/app-mobile-api` — «Касса» становится настоящим
каналом), C40 (регистрация или документирование прав `modules-process-exit` и
`process-install-step`), журнал заданий `system.jobs` (Q62), ссылка «Настройки программы» (Q66),
`restoapp://app/system` и `restoapp://app/channels/<id>`, иконка модуля в списке.

## 4. Что обязательно покрыть тестом

Это критические места: если они сломаются, сломается не экран, а сервер или право.

| Место | Тест |
|---|---|
| **Право закрывает метод** | без токена — `forbidden`, метода нет и в декларации (по образцу `mobile-api.marketing.test.ts`) |
| **Два уровня права каналов** | `view` без `manage`: нет действий, нет `settingsUrl`, нет `settings` в записи |
| **Отказы правил каналов** | включение без живого провайдера, вторая платформа на том же значении, удаление канала установленного провайдера, второй канал у `supportsMultipleInstances: false` |
| **Секреты** | `secretsRef` канала не приходит ни в списке, ни в карточке, ни в журнале |
| **Задание переживает таймаут** | установка длится дольше 15 с, `system.install` отвечает сразу, `system.job` доводит до исхода |
| **Один замок на кластер** | второе задание получает `conflict`, а не второй `npm install` в тот же каталог |
| **Перезапуск в кластере** | состояние видно из любого воркера; планирование идемпотентно |
| **Выключение действительно выключает** | `.sailsrc` изменён, после перезапуска хук не загружен |
| **Миграции** | модуль с непройденными миграциями видно в карточке; «догнать» меняет `migrationsCount`; на не-postgres действия нет |
| **Нельзя удалить то, что нельзя** | системный модуль, `forbidDelete`, модуль с зависимыми |
| **Безопасность архива** | тест `assertSafeArchiveEntry` на путь вне каталога и на симлинк наружу (есть, не сломать) |
| **Контракт** | `declaration.schema.json` и `Declaration.kt` согласованы (`DemoContractTest`), неизвестный ключ/контрол не ломает клиент |
| **Живой проход** | `composeApp/src/desktopTest/.../LiveServerTest.kt` и `RealServerWalkTest.kt`: вход, «Система», поиск программы, каналы — против поднятого сервера |

Правило: **каждый отказ, который человек может увидеть, должен быть в тесте словами** — иначе он
рано или поздно превратится в «Request failed with status 409».

## 5. Чем проверять

```bash
# сервер: модульные тесты хука и ядра
cd /prj/restoapp-dev-system && npm test                      # mocha -r tsx/cjs tests/**/*.test.ts
cd /prj/restoapp-dev-system && npx mocha -r tsx/cjs tests/mobile-api.system.test.ts --exit

# поднять настоящий сервер (sails-disk, админка, хук, живое ядро)
cd /prj/restoapp-dev-system && npx tsx /prj/restoapp-system/tools/real-server/serve.ts 1341
#   /admin — админка (сверять «один в один»), /api/mobile — API приложения
#   пользователи: admin, stock, operator, taker, marketer, promoter, nopoints, outsider
#   пароль у всех: secret-password

# клиент: тесты и живой проход против поднятого сервера
cd /prj/restoapp-system
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jdk-17.0.21+9/Contents/Home
./gradlew desktopTest
./gradlew :composeApp:desktopTest --tests '*RealServerWalkTest' -Prestoapp.serverUrl=http://127.0.0.1:1341
./gradlew :composeApp:desktopTest --tests '*RealServerShotsTest' -Prestoapp.serverUrl=http://127.0.0.1:1341

# приложение руками, глазами
./gradlew :composeApp:run
```

**Скриншоты — обязательная часть среза.** Берутся так же, как в
`composeApp/src/desktopTest/kotlin/cx/m42/restoapp/RealServerShotsTest.kt`
(`onRoot().captureToImage()` → png) и складываются в каталог вывода теста; в отчёте о срезе —
список файлов и одна строка на каждый: что на нём видно. Для каждого среза нужны **и удачный
экран, и экран отказа** — отказ важнее.

Сверка «один в один» делается не по памяти: открывай рядом веб-страницу стенда
(`/admin/modules/versions`, `/admin/modules/catalog`, `/admin/sales-channels-manager`,
`/admin/settings-manager`) и сравнивай словами и состояниями. В `tools/real-server/` есть
`web-login.mjs` и `web-admin.mjs` — ими админка открывается скриптом.

## 6. Срез готов, когда

1. задачи `C…`, перечисленные у среза в плане, сделаны (а не отложены);
2. `npm test` на сервере зелёный целиком, не только новый файл;
3. `./gradlew desktopTest` на клиенте зелёный целиком;
4. живой проход против поднятого сервера пройден, и в нём есть **отказ**, а не только удача;
5. скриншоты сняты и описаны;
6. критические места из раздела 4, относящиеся к срезу, покрыты;
7. в плане дописан абзац «Статус реализации»: что сделано, чем проверено, что осталось;
8. коммиты разделены: `feat(...)` код, `test(...)` тесты, `docs(ai-notes): ...` план.

## 7. Чего не делать

- **Не трогать настоящий сервер владельца** — ни установки, ни удаления, ни перезапуска.
- **Не пушить `webresto/restoapp`** (и `webresto/core`): права только на чтение, серверная ветка
  живёт локально. Клиент (`restoapp-touch`) пушить можно.
- **Не делать `npm install` модулей в основном чекауте** `/prj/restoapp-dev` — только в своём
  worktree и только на поддельном реестре.
- **Не отвечать на вопросы планов за владельца**: берётся рекомендация, альтернативы не
  реализуются «на всякий случай».
- **Не добавлять в контракт ничего, кроме `open: {url}`** (шаг 5). Любой новый ключ `ui` — это
  вопрос владельцу и правка плана, а не решение по ходу дела.
- **Не рисовать то, чего нет в вебе**, кроме прямо названного в планах (поиск по установленным,
  чипсы состояния, журнал заданий, включение-выключение): «один в один» — это требование владельца.
- **Не чинить мёртвые флаги хука** (`state.restartRequired`, `noModulesFound`, …) — Q67, отдельная
  уборка.
