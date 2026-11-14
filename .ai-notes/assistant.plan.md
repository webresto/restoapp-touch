# «Ассистент» — ассистент админки контекста в приложении (D15)

> v1.1, 2026-11-14. Облачная сторона выложена на dev (`57fb2d6`), клиент проверен против dev.resto.cloud с настоящей моделью. Ветка `assistant` (worktree `../restoapp-assistant`). Сделано с трёх сторон: клиент (`apps/assistant`),
> облако (cloud-panel, `layers/app-mobile-api/lib/assistant.ts`), RestoApp (restoapp-dev, ветка `mobile-assistant` от
> `mobile-api-init`: `api/hooks/app-mobile-api/apps/assistant.ts`).

## Что просил владелец

«Внутри приложения restoapp-touch сделай новый app-ассистент, который будет работать с облаком или сервером в зависимости
от контекста, но механизм будет одинаковый… Надо поддержать именно того ассистента, который сделан в админке, чтобы мы его
UI и методы работы с бэком использовали. Ассистент будет выбираться из контекста: он или облачный, или серверный».

## Решение

- **Ассистент — не второй, а тот же, что AI-панель adminizer контекста.** Облако — Resto Cloud Assistant
  (`cloud-assistant`, `/console`), RestoApp — его OpenHarness-ассистент (`openharness`, `/admin`). Модель, инструменты,
  права (`ai-assistant-<model>`), диалоги и биллинг — панели; телефон и браузер одного человека в одном диалоге.
- **Один механизм для обоих контекстов.** Контекст объявляет приложение `assistant` с `client: {native: "assistant"}` и
  одним методом `assistant.session`. Клиент не различает облако и сервер — он спрашивает сессию у *своего* контекста.
- **Бэк — родные маршруты AI-панели** (`<prefix>/api/ai-assistant/agent/<model>/…`, их зовёт бандл панели
  `src/assets/js/ai-assistant/agent/runtime.ts`): `status`, `meta`, `history`, `conversations` (+ `select`, `DELETE`),
  `POST runs` → `{runId, stream}`, SSE `runs/<id>/stream` (`event: agent`, `id:` — номер кадра, переподключение по
  `Last-Event-ID`, ран живёт 10 минут). Ничего своего на сервере, кроме выдачи сессии.
- **Сессия.** adminizer узнаёт пользователя только по cookie `adminizer_jwt` (`bindReqFunctions.js`), Bearer не читает.
  `assistant.session` (по токену устройства) выдаёт JWT формы входа (`{id, login, isAdministrator}`, секрет
  `adminizer.jwtSecret`) **на час**: `{transport: "adminizer-agent", model, title, base, cookie: {name, value}, csrf:
  {cookie: "XSRF-TOKEN", header: "X-XSRF-TOKEN"}, expiresAt}`. Клиент просит новую за 2 минуты до конца и на 401.
  Отозванное устройство новую не получит. Не MCP-инструмент: личный API-ключ не должен выпускать сессии консоли.
- **CSRF.** На каждый POST adminizer сверяет cookie `XSRF-TOKEN` с заголовком `X-XSRF-TOKEN` (double submit): клиент
  шлёт свою пару в каждом запросе.
- **UI — как у панели, нативно (Compose).** WebView панели не взят: на трёх платформах это отдельная зависимость (KCEF на
  десктопе), а вход в панель по cookie в WebView — ещё одна сессия. Повторено поведение `runtime.ts`: история — сообщения
  ai-sdk, ассистент + последующие `tool` — один пузырь, результат инструмента прикреплён к вызову по `toolCallId`;
  поток: `text.delta` растит открытую часть, `text.done` закрывает её, `tool.start/done/error` — свёрнутый блок
  «Инструмент: имя» с параметрами и результатом, `reasoning.*` — «Размышления», `ui.method navigate` и ссылки
  Markdown на пути панели открываются в браузере на адресе контекста. Приветствие, подсказки, плейсхолдер и экран «не
  подключён» — из `status.schema` панели. «Стоп» — как в панели: перестать читать поток (отмены рана у панели нет).
- **Сеть.** `NetworkService` хоста не умеет читать тело по мере прихода и режет всё на 15 с, поэтому модуль берёт
  платформенный клиент Superizer (`defaultHttpClient`, один на открытое приложение) с `HttpTimeout` и бесконечным
  временем запроса для потока (простой — 90 с, потом переподключение). Токен устройства по-прежнему у хоста:
  приложение видит только короткую сессию панели.
- **Демо.** Демо-администратор объявляет «Ассистента» с `transport: "demo"` — `DemoAssistantTransport` отвечает теми же
  событиями без модели (вызов инструмента на «инструменты», эхо в Markdown).

## Где что

| Сторона | Файлы |
|---|---|
| Клиент | `apps/assistant`: `AssistantApp` (выбор транспорта по `assistant.session`), `AssistantInstance` (состояние), `AssistantScreen` + `Markdown`, `AgentProtocol` (сессия, статус, история, события), `AssistantTransport` (`AdminizerAgentTransport`, SSE), `DemoAssistantTransport`; регистрация — `RestoAppHost` (`AssistantApp.canDraw`) |
| Облако | cloud-panel `layers/app-mobile-api/lib/assistant.ts`, `http/router.ts` (объявление и `assistant.session`), порт в `index.ts` (модель — как `GET /api/ai-assistant/models` панели) |
| RestoApp | restoapp-dev `api/hooks/app-mobile-api/apps/assistant.ts`, регистрация в `lib/hook.ts` только при `config.adminpanel.aiAssistant.enabled`, право `ai-assistant-<defaultModel>` |
| Проверка | `tools/fake-llm/serve.mjs` — OpenAI-совместимая заглушка LLM для панели без ключа |

## Тесты

- `apps/assistant`: `AgentProtocolTest` (SSE, события, история, сессия, Markdown), `AssistantAppTest` (демо: подсказки,
  инструмент, ответ, диалоги, ссылки), `AssistantLiveTest` — транспорт против живой панели (`-Passistant.url`).
- `composeApp`: `AssistantWalkTest` — через окно: демо и аккаунт облака (`-Pcloud.url`).
- cloud-panel: `layers/app-mobile-api/__tests__/mobileApi.test.ts` («Ассистент»); RestoApp: `tests/mobile-api.assistant.test.ts`.

```bash
node tools/fake-llm/serve.mjs 17321 &
# cloud-panel: env -u NODE_ENV PORT=17320 DB_STORAGE=/tmp/a.sqlite CLOUD_AI_ENABLED=true CLOUD_AI_API_KEY=fake \
#   CLOUD_AI_BASE_URL=http://127.0.0.1:17321/v1 CLOUD_AI_MODEL=fake npx tsx --tsconfig ./tsconfig.runtime.json ./index.ts
./gradlew :apps:assistant:desktopTest :composeApp:desktopTest --rerun --tests '*Assistant*' \
  -Passistant.url=http://127.0.0.1:17320 -Pcloud.url=http://127.0.0.1:17320 -Pcloud.login=user1 -Pcloud.password=user1
```

## Затыки (проверены)

1. Мобильный токен (`rcm_…` облака, токен устройства RestoApp) **не открывает** маршруты adminizer — только cookie. Отсюда
   `assistant.session`.
2. Без CSRF-пары любой POST панели — `403 Invalid CSRF token` (проверено curl'ом).
3. `emit` из `execute {}` Ktor — другая корутина: `flow {}` ломается («exception transparency»), поток — `channelFlow`.
4. `AppTextField` вешает модификатор на само поле, обёртка занимает всю ширину: вес — у `Box` вокруг, иначе кнопка
   «Отправить» нулевой ширины.
5. Язык слов панели — по `Accept-Language`, клиент его шлёт.
6. На RestoApp ассистент сейчас выключен в коде (`config/adminpanel.js`: `aiAssistant.enabled: false`) — приложение там
   не объявляется, пока его не включат; право `ai-assistant-openharness` adminizer не регистрирует — проходят только
   администраторы, как и в вебе.
7. Сервер держит один активный диалог на пользователя: выбор диалога на телефоне меняет его и в браузере.
8. Отмены рана у панели нет: «Стоп» только перестаёт читать, ход доводится и списывается.
9. Найдено на dev.resto.cloud: «Новый диалог» чистил экран после ответа сервера, и вопрос, отправленный раньше, пропадал.
   Пока создаётся/выбирается/удаляется диалог — `busy`, отправка закрыта; а история, начатая до отправки, не ложится
   поверх нового сообщения (`AssistantRaceTest`).

## Не сделано

- Вложения (файлы) — у панели это multipart `runs` с полем `files`; в приложении пока только текст.
- Удалённое управление моделью (`POST model`) — облако его не поддерживает (501).
