# 1. Superizer 0.11.0 — адаптивная оболочка и кит

Рабочее дерево `/prj/restoapp-touch/superizer-adaptive`, ветка `adaptive` от `origin/master`.
Архитектура — `../.ai-notes/ui-port.plan.md` §7.

## Сделано (2026-11-18, не закоммичено)

- [x] S1 — `ui-kit`: `WindowClass`/`WindowClassProvider`/`byWindow`; акцент `KitAccent`, `KitPalette`,
      `KitTheme(dark, accent)`; `KitTextStyles`; `Tone`, `kitClickable`; компоненты страницы,
      выбора, статусов, поверхностей, графиков и оверлеев (31 тест).
- [x] S2 — `core`: `AppInstance.layout` (`AppWidth`, `upTitle`), `AppSetupContext.badge`,
      `AppHandler.badges`.
- [x] S3 — `ui`: `ShellLayout.Adaptive` (выдвижное меню / рельс / боковая панель), `ShellFrame`,
      ширина по объявлению приложения, без бара у широкого приложения на `Expanded`.
- [x] S4 — `AppSections`: разделы приложения (нижняя панель на телефоне, вкладки шире).
- [x] S5 — `AppHandler.reopenAfterExpiry`: последнее приложение без срока, состояние — сутки.
- [x] Акцент в Settings хоста (`HostSettingsPort.accent/chooseAccent/offersAccent`, `HostKeys.ACCENT`).
- [x] Регрессии других хостов закрыты: выбор акцента и возврат приложения — только по включению,
      класс окна — по колонке приложения, без `BoxWithConstraints` (ломал таймер простоя).
      328 тестов, `checkLegacyAbi`, `checkDependencyRules` — зелёные; wasm и iosArm64 собираются.

## Осталось

- [x] **Сборщик хоста** — хост сейчас не может задать то, что включили в S5 и в акценте:
      `AppLocaleService` создаётся внутри `Superizer.build` и закрыт. Добавить в `SuperizerHostBuilder`:
      `accent(default: String?, picker: Boolean = true)` и `reopenLastApp()`; тест на каждый.
- [x] **Вкладки `AppSections` под шапкой страницы.** (`PageHeader` рисует их сам) Сейчас на широком окне они над `PageHeader`.
      Вариант: параметр `header: @Composable () -> Unit` — шапка рисуется до вкладок, в ширине `Page`.
- [x] **Иконки в выдвижном меню телефона.** `MenuEntry` — data class, расширять нельзя без поломки
      ABI; рисовать меню телефона из того же `NavItem`, что рельс и панель, а `MenuEntry` оставить
      для `AppScaffold` хостов, которые зовут его сами. Бейджи — там же.
- [x] Шапка оболочки на `Medium` для широкого приложения: сейчас «‹ upTitle» и заголовок в баре;
      сверить с прототипом (там шапка внутри страницы и на планшете).
- [ ] Кнопка поиска (⌘K) — **не в 0.11**: нужна модель «что искать» от приложений; отложено.
- [x] Доки: 05 (хост: `ShellLayout`, сборщик), 06 (компоненты), 12 (kit: окно, акцент, новые
      компоненты), 13 (гайд: снять «черновик» с того, что стало кодом; 1000 dp вместо 840).
      Ветка доков `docs/ui-kit-guide` — влить в `adaptive` или выпустить вместе.
- [x] Плавающий `LockShellTest.leftAloneTheUnlockedAppIsClosedAndTheNextLookAsksAgain` (2026-11-18) —
      **старый, не регрессия**: на `origin/master` 2229798 под нагрузкой 1 падение из 3 некэшированных
      прогонов (5,3 с при пределе 5 с). Причина: закрытие по простою идёт по реальному времени на общем
      потоке `DefaultExecutor`, а хосты прежних тестов класса не отменяются. Правка — только в тесте:
      пределы `LockShellTest.kt:252`/`:291` до 20 с и отмена scope хоста в `@AfterTest` (`:79`);
      строже — виртуальное время. **Применено** (2026-11-18): `@AfterTest` отменяет хосты, пределы 20 с.
- [x] Галерея (2026-11-18, субагент): приложение `kit` (`KitGalleryApp`, «Все приложения → Kit») в
      `apps/test-app` — все семейства kit по панелям, шесть акцентов; `fixture` `KitGalleryTest` →
      `fixture/build/screenshots/kit/` (420/800/1280 × светлая/тёмная + оверлеи). Дамп ABI test-app
      klib правлен вручную — подтвердить `checkLegacyAbi`. Найденные недочёты kit: у `Select` нет
      disabled/invalid (`Overlays.kt:436`); `FactRow(copyable)` всегда по-английски (`Surfaces.kt:193`);
      заголовок `Alert` Warning плохо читается в светлой (`Feedback.kt:131,146`, нужен `Tone.textColor()`);
      выбранный `Chip` чёрный при любом акценте (`Selection.kt:53`) — решить. Исправлено 2026-11-18: заголовок `Alert` — `Tone.textColor()`; `FactRow(copyLabel, copiedLabel)`. **Дамп ABI ui-kit не обновлён** — `updateLegacyAbi` упал на Metaspace при параллельных сборках, повторить. `Select` disabled/invalid — не сделано. Было в плане:
      галерея компонентов в `apps/test-app` (замена экрана `kit` прототипа) — по ней проверять тему
      и акценты на всех платформах.
- [x] Чужие хосты (2026-11-18, субагент; `0.11.0-SNAPSHOT` из `mavenLocal`, common+desktop, временные
      worktree, `SUPERIZER_FROM_MAVEN=1`): touch-vn 96/0, app-learn 251 (2 таймаута под нагрузкой — по
      отдельности зелёные на старой и новой), Unitool 876/0 — **без правок исходников**. Android/iOS/wasm
      хостов не собирались. Попутно: скриншот-тест touch-vn `21-food-bonus-sent` открывает настоящий
      браузер (`Platform.desktop.kt:41`) и вешает прогон — не от 0.11.0. `~/.m2` вычищен, `<latest>`
      в maven-metadata выставлен в 0.10.0 (предположение). Было в плане:
      Проверка на чужих хостах: `mavenLocal` `0.11.0-SNAPSHOT`, временно в Unitool, app-learn,
      touch-vn → сборка и их тесты (версии вернуть). Ожидание — без изменений (см. разбор в
      `../.ai-notes/ui-port.plan.md`).
- [x] Версия `0.11.0` в `gradle.properties`, CHANGELOG. - [ ] Коммит, PR, публикация
      в GitHub Packages.

## Готово, когда

`./gradlew desktopTest checkLegacyAbi checkDependencyRules` зелёные; сборка wasm/iOS/Android есть;
чужие хосты собираются на снимке; 0.11.0 опубликован; RestoApp собирается с `SUPERIZER_FROM_MAVEN=1`.

**Оценка:** 2–3 дня.
