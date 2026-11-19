# 3. Рендер деклараций — новый вид

Зависит от этапов 1–2. **Протокол не меняется**: всё, что ниже, рисуется из нынешних подсказок
(`list`/`detail`/`action`, `ItemUi`, `chips`, `search`, `groupBy`, контролы). После этого этапа
«Заказы», «Блюда», «Клиенты», «Маркетинг» (списки), «Настройки сервера», «Программы», «Каналы
продаж», а в облаке «Серверы» и «Аккаунт» — в новом виде на **любом** сервере.

Файлы: `render/src/commonMain/kotlin/cx/m42/restoapp/render/` — `Screens.kt` (879), `Components.kt`
(859), `ServerAppInstance.kt` (826), `Values.kt`, `Unsaved.kt`, `SchemaEditor.kt` (713).

## Итог 2026-11-18 (субагент, влито; render 68/0, apps 5 модулей и composeApp — зелёные)

Сделано: (1) `layout` Adaptive + `upTitle`, фон кадра — от оболочки; (2) `Page` + `PageHeader`, крошки из
стека кадров (с вопросом о несохранённом), точка — `Select` в шапке; (3) список — строки в `Panel` на
`Compact`, таблица (`RecordTable` — копия `DataTable` ради тегов строк) на `Medium+`, фильтры, группы,
`EmptyState`, `Skeleton`, ошибка обновления над списком; (4) запись — две колонки на `Expanded`.
Частично: (5) формы — `Panel`, две колонки коротких полей, `Pill` «Не сохранено», `ConfirmDialog` при уходе;
(6) тоны → `Tone`, бейджи/заметки kit, подтверждения — диалоги kit. Новый файл `Parts.kt`; старые общие
компоненты (`Group`, `Notice`, `BottomSheet`…) не тронуты, пока ими пользуются `apps/*`.
`marketing:title` теперь на всей шапке записи — тесты проверяют потомка (`MarketingScreenTest`,
`PromotionEditorTest`). Скриншоты: dishes/dish/clients/client-form/codes добавлены в `AdaptiveShotsTest`.

Не сделано: кнопки статусов на `Medium+` — все в шапке, не `⋯` (иначе их нет без открытия меню — живые
тесты облака); `⋯` у кнопок строки; «Сохранить» в шапке/`ActionBar` (пока внизу формы); выбор в формах —
чипы, не `Select`; запоминание точки по набору (нужно в `RestoServer` хоста); нативное действие; чистка;
прогон против старого сервера. Пробелы вида: у блюда нет героя и панели по точкам (этап 4); заголовки
колонок — имена полей, бывают пустые (первая колонка у заказов); на телефоне заголовок дважды (бар
оболочки + `PageHeader`); форма действия списка — на странице, а в прототипе диалог.
Недочёты kit: `PageHeader` — нет тега заголовка; `DataTable` — нет тега/модификатора строки; `Select`,
`ConfirmDialog`/`DialogActions` — нельзя пометить пункты/кнопки; `EmptyState` требует описание; `Notice`
требует вторую строку; `QtyStepper` не умеет слово («Нет в наличии»); `StatusBadge` — два текстовых узла.

## Шаги

- [ ] **Ширина и «назад».** `ServerAppInstance` переопределяет `layout`: `AppWidth.Adaptive`,
      `upTitle` — заголовок кадра под верхним (`frames[size-2]` → название метода/записи).
- [ ] **Страница.** Корень каждого кадра — `Page` (`Default`; лог и широкие таблицы — `Wide`).
      `PageGutter` и свои отступы рендера уходят.
      Фон: сейчас `Screens.kt:124` красит кадр старым `tokens.pageBackground` (#F2F2F7) — в светлой
      теме на планшете и десктопе колонка 480 dp видна серой полосой на `Kit.palette.canvas`
      оболочки (скриншоты `AdaptiveShotsTest`, 2026-11-18). Фон кадра — от оболочки, кадр не красит.
- [ ] **Шапка.** `PageHeader`: заголовок — название приложения (список) или запись (`item.title`);
      `badge` → `StatusBadge`; `subtitle`/`meta` → описание; действия записи (`placement: detail`) —
      главное (первое `buttons`) + остальные в `⋯` (`Menu`); `listAction` («+ Новый клиент») — главная
      кнопка шапки списка; на `Expanded` — крошки из стека кадров (`Crumb` → `instance.back` до уровня).
- [ ] **Точка (В3).** `PlaceSwitch` → `Select` с иконкой точки в `meta` шапки; одна точка — просто
      подпись. Запоминание выбора по приложению — как сейчас (`instance.server.place`); приложение с
      тем же набором точек начинает с последнего выбора (`RestoServer`, по ключу набора).
- [ ] **Список** (`ListScreen`, `RecordRow`):
  - `Compact` — карточки/строки: `title`, `StatusBadge(badge)`, `subtitle`, `meta`, `value` справа;
  - `Medium`/`Expanded` — `DataTable`: колонки из `ItemUi` (title, subtitle, badge, meta, value) —
    пока `ui.columns` нет (этап 4);
  - `search` + `chips` → строка фильтров (`Input` + `Chip` с числами `counts`), всегда видна;
  - `groupBy` → `SectionTitle` + `Panel(flush)` на группу;
  - контролы строки: `stepper` → `QtyStepper`, `toggle` → `Switch`, `buttons` → `⋯`;
  - пусто — `EmptyState` в двух смыслах («пока нет» с действием списка / «ничего не найдено» со
    сбросом фильтра); загрузка — `Skeleton`-строки; ошибка обновления — строка над списком, список
    не очищается.
- [ ] **Запись** (`DetailScreen`, `RecordCard`, `HomeRecordScreen`): `TwoColumn` на `Expanded`
      (основное: поля по `section` в `Panel`, ссылки `_links`, контролы; рельс: `FactRow` с
      идентификаторами — копируемые, даты, ссылки), одна колонка уже; `StepperCard`/`ButtonsCard`/
      `RecordFormCard` → `Panel` с kit-контролами.
- [ ] **Формы** (`FormCard`, `SchemaEditor`): `Field` + `Input`/`Select`/`Switch`; две колонки коротких
      полей на `Medium+`; «Сохранить» — в шапке на `Medium+`, в `ActionBar` на `Compact`; отметка
      «Не сохранено» (`Pill`); вопрос при уходе — `ConfirmDialog` вместо `UnsavedQuestion`.
- [ ] **Тоны.** `toneOf(tone)` протокола (`info/warning/success/danger`) → `Tone` kit; `Badge` рендера
      → `StatusBadge`; `Notice` рендера → `Notice` kit.
- [ ] **Шторки и выбор** (`ChoiceSheet`, `BottomSheet`) → `Select`/`Dialog`/`SideSheet` kit.
- [ ] **Нативное действие** (`NativeActionScreen`): поверх кадра — без изменений протокола; вид — по
      этапу 5.
- [ ] **Чистка.** Из `Components.kt` уходит всё, что заменил kit; остаётся только своё рендеру
      (`ValueText` форматов, `FieldLine`). Цель — файл в 2–3 раза меньше.

## Проверка

- [ ] Тесты рендера и живые тесты против restoapp-dev и облака — зелёные (поправить `testTag`
      только там, где менялась структура, и в том же коммите).
- [ ] Демо-сервер (`demo/`) — все его приложения на скриншотах 3 × 2, сверка с экранами прототипа:
      заказы ↔ `r-orders`, заказ ↔ `r-order`, блюда ↔ `r-dishes`, клиенты ↔ `r-clients`, коды и
      акции ↔ `r-codes`/`r-promotions`, настройки ↔ `r-settings`, программы ↔ `r-programs`,
      облако ↔ `services`, `balance`.
- [ ] Старый сервер (декларация без новых ключей) — без падений, то же поведение.

**Оценка:** 7–9 дней.
