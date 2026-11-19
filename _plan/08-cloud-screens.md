# 8. Экраны Resto Cloud один в один с прототипом

Решения владельца (2026-11-19): экраны облачного аккаунта — как в `prototype-rikka/cloudApp` (маршруты `home`, `services`,
`services/<id>[/events|logs|billing|settings]`, `assistant`, `balance`, `tokens`, `settings`, `more`). Данных, которых у облака
не было, облако добавляет само (не прячем): метрики — сборщик `/metrics` RestoApp в облаке; валюта — отображение поверх кредитов;
уведомления — пока только настройки. Главная кнопка сервиса — «Открыть управление» (сервер в приложении), веб-админка —
неприметной ссылкой внизу. Остаток денег виден везде.

Сервер: `/prj/worktrees/cloud-metrics` (cloud-panel, ветка `cloud-metrics`): MCP `cloud.resources.metrics[.summary]`,
`cloud.resources.inspect`, `cloud.billing.daily`, `cloud.billing.balance` (+ деньги), `cloud.account.currency[.set]`,
`cloud.account.apiKey.info/issue/revoke`, `cloud.account.notifications[.set]`. Мобильный API — `layers/app-mobile-api/lib/apps.ts`.

## Контракт (протокол 1, только добавления — старый клиент не ломается)

### Факты и деньги
- `describe.server.currency` облака = валюта аккаунта (ISO, `RUB`). Все денежные поля облака — в деньгах аккаунта (не в
  кредитах), формат поля `money`. Кредиты в мобильный API больше не выходят.

### Приложения облака (порядок = порядок меню)
| app id | Название | Что |
|---|---|---|
| `home` | Обзор | `home.summary` — запись ниже; клиент рисует свой экран (как ресторанная «Главная»), не плитки |
| `services` | Сервисы | список, карточка с вкладками (P5 `section`), действия |
| `assistant` | Ассистент | как сейчас |
| `balance` | Баланс | `balance.get` (detail + P7), `balance.transactions` (list, section «Операции») |
| `apiaccess` | Доступ к API | `apikey.get` (detail), `apikey.issue`/`apikey.revoke` (действия) |
| `account` | Настройки аккаунта | `account.get` (detail), `account.update` (форма: имя), `account.currency` (действие buttons, только пока `canChange`), `account.notify` (переключатели `toggle` по виду уведомления) |
| `accounts` | Аккаунты (админ) | как сейчас |

### `home.summary` → `{item}` (облако)
```jsonc
{
  "id": "home", "kind": "cloud",
  "name": "Анна",                                  // для «Добрый день, …»
  "attention": [                                   // сервисы с ошибкой / ждут перезапуска; пусто — блока нет
    {"id": "res-uuid", "title": "Бургерная на Ленина", "status": "error", "tone": "danger",
     "text": "Не хватило памяти на тарифе «Старт» · упал 26 мин назад",
     "_actions": [{"method": "services.power", "input": {"id": "res-uuid", "power": "restart"}}],
     "_open": {"method": "services.get", "params": {"id": "res-uuid"}}}
  ],
  "running": 2, "total": 6, "starting": 1, "failed": 1,
  "balance": 1840.0, "daysLeft": 42, "balanceTone": null,   // "warning" < 7 дней, "danger" < 3 или ≤ 0
  "perDay": 43.2, "perMonth": 1296.0,
  "ordersToday": 271,                              // нет метрик ни у одного — ключа нет
  "services": [{"id": "…", "title": "…", "host": "pizza-north.restocloud.app", "status": "online", "tone": "success",
                "load": [0.31, 0.4, …]}],          // ≤ 32 точек, доля (rps нормированный) — для спарклайна; нет — ключа нет
  "spend": [{"x": "19 окт", "y": 28.8}, …],        // 30 дней, деньги
  "spendTotal": 1017.0,
  "share": [{"title": "Пицца Север", "value": 489.6}, …]
}
```
Только то, на что у человека есть права (как `home.summary` ресторана); ключа нет — блока нет.

### Список сервисов `services.list`
- поля записи: `title`, `host`, `status` (enum + `x-tones`), `plan` (название тарифа), `perDay` (money), `rps` (text «38 запр/с»),
  `latency` (text «84 мс»), `load` (массив чисел 0..1, не в модели).
- `ui.item`: `{title, subtitle: "host", badge: "status", meta: "plan", value: "perDay", series: "load"}` —
  **P8 `item.series`**: имя поля записи с массивом чисел; клиент рисует спарклайн в карточке. Старый клиент — не знает ключа, пропускает.
- `chips` по статусу с `counts`, `search`.

### Карточка `services.get` (section «Обзор»)
- `ui.summary` (P7): `metric cpu` (format percent, значение 0..100) + `series cpuSeries`; то же `memory`, `disk`,
  `metric rps` + `series rpsSeries`, `metric latency`, `metric ordersToday`. Поля-массивы — `[{x, y}]` (как ресторан).
  Метрик нет (`available:false`) — этих полей нет, вместо них поле `metricsNote` (text) с причиной.
- `_links`: первым — `{"title": "Открыть управление", "open": {"server": "<resource id>"}}` — **P9 `open.server`**:
  клиент открывает этот сервер аккаунта в приложении (`RestoAppHost.connectCloudServer`), если сервис online; последним —
  `{"title": "Веб-админка", "quiet": true, "open": {"url": "https://host/admin"}}` — **P9 `quiet`**: рисуется мелкой серой
  ссылкой внизу, не кнопкой.
- `_actions`: `services.power` (P3 `primary` — когда сервис не online: «Запустить»/«Перезапустить»), `services.update`, `services.delete`.
- Остальные вкладки — отдельные методы с `ui.section`: `services.events` («События», list: время, заголовок, тон; из
  `resource_event` + события k8s `inspect`), `services.logs` («Логи», detail: поле `text` моноширинное — format `text`,
  параметр `container` enum restoapp/postgres), `services.spend` («Расходы», detail: P7 metric `perDay`, series `days`
  из `cloud.billing.daily` по этому сервису), `services.update` — «Настройки» (форма как сейчас).

### `balance.get` → `{item}`
- поля: `balance` (money), `daysLeft` (number), `perDay` (money), `perHour` (money), `monthToDate` (money),
  `previousMonth` (money), `currency` (text «Российский рубль, ₽»), `nextCharge` (relative).
- `ui.summary`: metric `balance` (+ tone через `balanceTone`), series `days` (30 дней `[{x,y}]`), share `byService` (`[{title,value}]`).
- `_actions`: пополнение — пока нет (платёжки нет); в тестовом режиме — без изменений.

### Шапка: остаток денег
- `describe` облака кладёт в `server` (или `user`) объект **P10 `balance`**: `{"amount": 1840.0, "daysLeft": 42, "tone": null}`;
  клиент показывает плашку «1 840 ₽» в шапке аккаунта (телефон — вместо поиска), строку остатка в списке серверов, полосу
  «Осталось … — хватит на N дней · Пополнить» при tone warning/danger — в аккаунте и во всех его серверах. Обновляется с
  `describe` (ревизия) и раз в 5 мин.

## Проверка
- Облако: vitest (`layers/app-mobile-api/__tests__`), схема `schema/declaration.schema.json` дополнена (P8–P10).
- Клиент: снимки облачного контекста в трёх ширинах против снимков прототипа (`screenshots/cloud/…`), живой вход на стенд.

## Сделано — клиент (2026-11-19, не закоммичено)
- Протокол: `ItemUi.series` (P8), `Open.server` и `RecordLink.quiet` (P9), `AccountBalance` в `server`/`user` (P10),
  `SummaryBlock.tone` (поле тона метрики, как у облака) — всё мягко: неверная форма = нет ключа. Схема в тестах — копия
  `cloud-metrics` (e6fb7e6). `CloudContractTest`.
- Рендер: карточки сервисов со спарклайном (телефон/планшет), колонка «Нагрузка» в таблице (десктоп); метрика percent —
  кольцо + спарклайн `<field>Series`; `open.server` — главная кнопка «Открыть управление», если хост может открыть сервер
  (он online), действия питания — вторичные (на телефоне — за «⋯»); `quiet` — серая ссылка внизу карточки.
- Хост: «Обзор» облака (`CloudHome.kt`), меню облака по контракту, баланс — плашка на телефоне, рельс, карточка сайдбара,
  строка в переключателе, полоса warning/danger в аккаунте и его серверах (новый слот `ShellChrome.notice` в Superizer,
  плюс `homeTitle`/`homeIcon`). Баланс хранится в профиле аккаунта, обновляется с `describe` и раз в 5 мин.
- Фейковое облако: `demo/DemoCloud.kt` + `DemoNetwork.kt`; `CloudShotsTest` → `composeApp/build/screenshots-cloud/<ширина>/`.
- Не сделано: вкладки карточки сервиса (`services.events/logs/spend/update` с `section`) — рендер разделов только у
  приложения; «Разобраться» и строки сервисов «Обзора» открывают список сервисов, а не карточку (у хоста нет открытия
  приложения с целью).
