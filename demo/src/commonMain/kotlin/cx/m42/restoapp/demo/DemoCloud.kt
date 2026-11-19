package cx.m42.restoapp.demo

import cx.m42.restoapp.protocol.AccountBalance
import cx.m42.restoapp.protocol.Answer
import cx.m42.restoapp.protocol.ClientRender
import cx.m42.restoapp.protocol.CloudServer
import cx.m42.restoapp.protocol.CloudServerAccess
import cx.m42.restoapp.protocol.Declaration
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.DeclaredUser
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.Formats
import cx.m42.restoapp.protocol.Icon
import cx.m42.restoapp.protocol.ItemUi
import cx.m42.restoapp.protocol.LoginResult
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.Model
import cx.m42.restoapp.protocol.PROTOCOL_VERSION
import cx.m42.restoapp.protocol.ScriptRef
import cx.m42.restoapp.protocol.ServerAbout
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.protocol.SignedInUser
import cx.m42.restoapp.protocol.SummaryBlock
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.protocol.Ui
import cx.m42.restoapp.protocol.Views
import cx.m42.restoapp.protocol.Control
import cx.m42.restoapp.protocol.Controls
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * A Resto Cloud account answered in process (`_plan/08-cloud-screens.md`): the declaration of the contract —
 * «Обзор» (`home.summary`, kind cloud), «Сервисы» with the load of each service (P8 `item.series`) and its card
 * with gauges and «Открыть управление» (P9 `open.server`, the web admin `quiet`), «Ассистент», «Баланс», «Доступ
 * к API», «Настройки аккаунта» — and the balance in `describe` (P10). Money is the account's, in roubles; the
 * cloud's credits never come out of it. For the client's tests and screenshots, without a cloud panel.
 */

/** One service of the demo account, as the prototype has it. */
public class DemoCloudService(
    public val id: String,
    public val title: String,
    public val host: String,
    public val status: String,
    public val plan: String,
    public val perHour: Double,
    internal val cpu: Int = 0,
    internal val memory: Int = 0,
    internal val disk: Int = 0,
    internal val rps: Int? = null,
    internal val latency: Int? = null,
    internal val ordersToday: Int? = null,
    internal val seed: Int = 1,
    internal val base: Double = 0.35,
    internal val note: String? = null,
) {
    internal val running: Boolean get() = status == "online"
    internal val perDay: Double get() = if (running) perHour * 24 else 0.0
}

public class DemoCloud(
    /** What is left on the account, in roubles: 1 840 lasts 42 days, 172.8 — four, and the strip turns amber. */
    private val money: Double = 1840.0,
    public val login: String = "anna",
    private val fullName: String = "Анна Орлова",
    public val services: List<DemoCloudService> = SERVICES,
) : MobileApi {

    /** Today's spend: the services that run. */
    private val perDay: Double get() = services.sumOf { it.perDay }

    public val daysLeft: Int get() = if (perDay > 0) (money / perDay).toInt() else 0

    /** Under a week — amber, under three days or nothing left — red; plenty — nothing to say. */
    public val balanceTone: String?
        get() = when {
            money <= 0 || (perDay > 0 && daysLeft < 3) -> Tones.DANGER
            perDay > 0 && daysLeft < 7 -> Tones.WARNING
            else -> null
        }

    override suspend fun about(): ServerAbout = ServerAbout("Resto Cloud", VERSION, registration = false, cloud = true)

    override suspend fun challenge(): ByteArray? = null

    override suspend fun login(login: String, password: String, captchaSolution: String?, device: DeviceInfo): LoginResult =
        LoginResult(TOKEN, SignedInUser(JsonPrimitive(7), this.login, fullName), PROTOCOL_VERSION)

    override suspend fun logout(token: String) {}

    override suspend fun script(token: String, ref: ScriptRef): String =
        throw MobileApiException(MobileApiException.NOT_FOUND, "The demo cloud has no script apps")

    override suspend fun describe(token: String, lang: String): Answer<Declaration> {
        val declaration = declaration(lang)
        return Answer(declaration, declaration.revision, PROTOCOL_VERSION)
    }

    /** `GET /api/mobile/cloud/servers`: every service of the account. */
    public fun servers(): List<CloudServer> =
        services.map { CloudServer(it.id, it.title, it.status, url = "https://${it.host}", adminUrl = "https://${it.host}/admin") }

    /** `POST /api/mobile/cloud/servers/:id/connect`: the address and the administrator the cloud made for it. */
    public fun access(id: String): CloudServerAccess {
        val service = services.firstOrNull { it.id == id } ?: throw MobileApiException(MobileApiException.NOT_FOUND, "No such service")
        return CloudServerAccess("https://${service.host}", "admin", "cloud-made-password", service.status)
    }

    public fun declaration(lang: String): Declaration = declaration(DemoWords(lang))

    private fun declaration(words: DemoWords): Declaration = Declaration(
        protocolVersion = PROTOCOL_VERSION,
        revision = "cloud-1-${money.roundToInt()}",
        server = ServerFacts(
            name = "Resto Cloud",
            version = VERSION,
            currency = "RUB",
            timezone = "Asia/Yekaterinburg",
            balance = AccountBalance(money, daysLeft, balanceTone),
        ),
        user = DeclaredUser(JsonPrimitive(7), login, fullName),
        apps = listOf(
            homeApp(words),
            servicesApp(words),
            assistantApp(words).copy(category = null),
            balanceApp(words),
            apiApp(words),
            accountApp(words),
        ),
    )

    override suspend fun call(token: String, method: String, params: JsonObject, place: String?, lang: String): Answer<JsonElement> {
        if (token != TOKEN) throw MobileApiException(MobileApiException.UNAUTHORIZED, "Sign in")
        val words = DemoWords(lang)
        val result: JsonElement = when (method) {
            "home.summary" -> item(summary(words))
            "services.list" -> list(params)
            "services.get", "services.power" -> item(service(words, serviceOf(params)))
            "services.events" -> JsonObject(mapOf("items" to JsonArray(events(words, serviceOf(params))), "nextCursor" to kotlinx.serialization.json.JsonNull))
            "services.spend" -> item(spendOf(serviceOf(params)))
            "balance.get" -> item(balance(words))
            "balance.transactions" -> JsonObject(mapOf("items" to JsonArray(transactions(words)), "nextCursor" to kotlinx.serialization.json.JsonNull))
            "apikey.get" -> item(buildJsonObject { put("id", "key"); put("title", words.t("Ключ API", "API key")); put("prefix", "rc_live_7f3a…"); put("lastUsed", words.t("вчера", "yesterday")) })
            "account.get" -> item(buildJsonObject {
                put("id", login); put("title", fullName); put("login", login); put("email", "anna@pizzasever.ru")
                put("currency", words.t("Российский рубль, ₽", "Russian rouble, ₽"))
            })
            "assistant.session" -> assistantSession(words)
            else -> throw MobileApiException(MobileApiException.FORBIDDEN, "Method is not available")
        }
        return Answer(result, null, PROTOCOL_VERSION)
    }

    private fun item(record: JsonObject) = JsonObject(mapOf("item" to record))

    private fun serviceOf(params: JsonObject): DemoCloudService {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull
        return services.firstOrNull { it.id == id } ?: throw MobileApiException(MobileApiException.NOT_FOUND, "No such service")
    }

    // ───────────────────────────── «Обзор» ─────────────────────────────

    private fun summary(words: DemoWords): JsonObject = buildJsonObject {
        put("id", "home")
        put("kind", "cloud")
        put("name", fullName.substringBefore(' '))
        putJsonArray("attention") {
            services.filter { it.status == "error" }.forEach { s ->
                add(buildJsonObject {
                    put("id", s.id)
                    put("title", s.title)
                    put("status", s.status)
                    put("tone", Tones.DANGER)
                    put("text", s.note ?: words.t("Сервис остановился", "The service stopped"))
                    putJsonArray("_actions") { add(buildJsonObject { put("method", "services.power"); putJsonObject("input") { put("id", s.id); put("power", "restart") } }) }
                    putJsonObject("_open") { put("app", "services"); put("method", "services.get"); putJsonObject("params") { put("id", s.id) } }
                })
            }
        }
        put("running", services.count { it.running })
        put("total", services.size)
        put("starting", services.count { it.status == "starting" })
        put("failed", services.count { it.status == "error" })
        put("balance", money)
        put("daysLeft", daysLeft)
        balanceTone?.let { put("balanceTone", it) }
        put("perDay", perDay)
        put("perMonth", perDay * 30)
        put("ordersToday", services.sumOf { it.ordersToday ?: 0 })
        putJsonArray("services") {
            services.forEach { s ->
                add(buildJsonObject {
                    put("id", s.id)
                    put("title", s.title)
                    put("host", s.host)
                    put("status", s.status)
                    put("statusTitle", statusTitle(words, s.status))
                    put("tone", STATUS_TONES[s.status])
                    loadOf(s)?.let { put("load", it) }
                    putJsonObject("_open") { put("app", "services"); put("method", "services.get"); putJsonObject("params") { put("id", s.id) } }
                })
            }
        }
        putJsonArray("spend") { SPEND.forEachIndexed { i, y -> add(buildJsonObject { put("x", dayLabel(i)); put("y", y) }) } }
        put("spendTotal", SHARE.sumOf { it.second })
        putJsonArray("share") { SHARE.forEach { (title, value) -> add(buildJsonObject { put("title", title); put("value", value) }) } }
    }

    // ───────────────────────────── «Сервисы» ─────────────────────────────

    private fun loadOf(s: DemoCloudService): JsonArray? =
        if (s.status == "online" || s.status == "starting" || s.status == "error") JsonArray(series(s.seed, base = s.base).map { JsonPrimitive((it * 100).roundToInt() / 100.0) }) else null

    private fun points(values: List<Double>, label: (Int) -> String = { "${it * 15 / 60}:${(it * 15 % 60).toString().padStart(2, '0')}" }) =
        JsonArray(values.mapIndexed { i, y -> buildJsonObject { put("x", label(i)); put("y", (y * 100).roundToInt() / 100.0) } })

    private fun record(words: DemoWords, s: DemoCloudService): JsonObject = buildJsonObject {
        put("id", s.id)
        put("title", s.title)
        put("host", s.host)
        put("status", s.status)
        put("plan", s.plan)
        put("perDay", s.perDay)
        s.rps?.let { put("rps", words.t("$it запр/с", "$it req/s")) }
        s.latency?.let { put("latency", words.t("$it мс", "$it ms")) }
        loadOf(s)?.let { put("load", it) }
    }

    private fun list(params: JsonObject): JsonObject {
        val status = (params["status"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "all" }
        val query = (params["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val words = DemoWords("ru")
        val shown = services.filter { status == null || it.status == status }
            .filter { query.isEmpty() || it.title.contains(query, true) || it.host.contains(query, true) }
        return buildJsonObject {
            put("items", JsonArray(shown.map { record(words, it) }))
            put("nextCursor", kotlinx.serialization.json.JsonNull)
            putJsonObject("counts") {
                put("all", services.size)
                services.groupBy { it.status }.forEach { (key, list) -> put(key, list.size) }
            }
            put("total", shown.size)
        }
    }

    private fun service(words: DemoWords, s: DemoCloudService): JsonObject {
        val base = record(words, s)
        return JsonObject(base + buildJsonObject {
            put("region", "Москва")
            put("version", "2.14.1")
            if (s.running) {
                put("cpu", s.cpu)
                put("cpuSeries", points(series(s.seed, base = s.cpu / 100.0)))
                put("memory", s.memory)
                put("memorySeries", points(series(s.seed + 2, base = s.memory / 100.0 * 0.95, amp = 0.08)))
                put("disk", s.disk)
                put("diskSeries", points(List(32) { s.disk / 100.0 * (0.82 + it * 0.0056) }))
                put("rpsSeries", points(series(s.seed + 5, base = 0.4).map { it * (s.rps ?: 1) * 2 }))
                s.ordersToday?.let { put("ordersToday", it) }
            } else {
                put("metricsNote", s.note ?: words.t("Сервис не запущен — метрик нет", "The service is not running — no metrics"))
            }
            putJsonArray("_links") {
                add(buildJsonObject { put("title", words.t("Открыть управление", "Open the console")); putJsonObject("open") { put("server", s.id) } })
                add(buildJsonObject { put("title", words.t("Открыть сайт", "Open the site")); putJsonObject("open") { put("url", "https://${s.host}") } })
                add(buildJsonObject { put("title", words.t("Веб-админка", "Web admin")); put("quiet", true); put("meta", "admin"); putJsonObject("open") { put("url", "https://${s.host}/admin") } })
            }
            putJsonArray("_actions") {
                val to = when (s.status) {
                    "online" -> listOf("restart", "stop")
                    "stopped" -> listOf("start")
                    "error" -> listOf("restart")
                    else -> emptyList()
                }
                if (to.isNotEmpty()) add(buildJsonObject {
                    put("method", "services.power")
                    put("input", objectSchema(listOf("id", "power"), mapOf("power" to enumProperty(to, null, POWER.filterKeys { it in to }.mapValues { words.t(it.value.first, it.value.second) }))))
                })
            }
        })
    }

    private fun events(words: DemoWords, s: DemoCloudService): List<JsonObject> = listOf(
        buildJsonObject { put("id", "e1"); put("title", words.t("Перезапущен", "Restarted")); put("time", "12 мин назад") },
        buildJsonObject { put("id", "e2"); put("title", words.t("Изменён тариф на «${s.plan}»", "Plan changed to «${s.plan}»")); put("time", "вчера") },
    )

    private fun spendOf(s: DemoCloudService): JsonObject = buildJsonObject {
        put("id", s.id)
        put("perDay", s.perDay)
        put("days", points(List(30) { if (s.running) s.perDay * (0.92 + (it % 5) * 0.03) else 0.0 }, ::dayLabel))
    }

    // ───────────────────────────── «Баланс» ─────────────────────────────

    private fun balance(words: DemoWords): JsonObject = buildJsonObject {
        put("id", "balance")
        put("title", words.t("Баланс", "Balance"))
        put("balance", money)
        balanceTone?.let { put("balanceTone", it) }
        put("daysLeft", daysLeft)
        put("perDay", perDay)
        put("perHour", perDay / 24)
        put("monthToDate", SHARE.sumOf { it.second })
        put("previousMonth", 884.0)
        put("currency", words.t("Российский рубль, ₽", "Russian rouble, ₽"))
        put("days", points(SPEND, ::dayLabel))
        putJsonArray("byService") { SHARE.forEach { (title, value) -> add(buildJsonObject { put("title", title); put("value", value) }) } }
    }

    private fun transactions(words: DemoWords): List<JsonObject> = listOf(
        Triple("Пицца Север", "Стандарт · 1 час", -1.2),
        Triple("Грузинский дворик", "Старт · 1 час", -0.6),
        Triple("Бургерная на Ленина", "Возврат за простой · 3 часа", 1.8),
        Triple(words.t("Пополнение", "Top-up"), "Карта •• 4821", 1000.0),
    ).mapIndexed { i, (title, detail, amount) ->
        buildJsonObject { put("id", "t$i"); put("title", title); put("detail", detail); put("amount", amount) }
    }

    // ───────────────────────────── the declaration ─────────────────────────────

    private fun homeApp(words: DemoWords) = DeclaredApp(
        id = "home", version = "1.0.0", title = words.t("Обзор", "Overview"), home = "home.summary",
        icon = Icon(DASHBOARD_ICON),
        methods = listOf(DeclaredMethod("home.summary", DeclaredMethod.KIND_QUERY, words.t("Обзор", "Overview"), objectSchema(emptyList(), emptyMap()), ui = Ui(view = Views.DETAIL))),
        client = ClientRender(native = "home", min = 1),
    )

    private fun servicesApp(words: DemoWords): DeclaredApp {
        val statusTitles = STATUSES.associateWith { statusTitle(words, it) }
        val status = Field("status", words.t("Статус", "Status"), Formats.ENUM, readonly = true, titles = statusTitles, tones = STATUS_TONES)
        val metrics = words.t("Нагрузка", "Load")
        return DeclaredApp(
            id = "services", version = "1.0.0", title = words.t("Сервисы", "Services"), home = "services.list",
            icon = Icon(SERVICES_ICON),
            models = mapOf(
                "service" to Model(
                    listOf(
                        Field("title", words.t("Название", "Name"), readonly = true),
                        Field("host", words.t("Адрес", "Address"), readonly = true),
                        status,
                        Field("plan", words.t("Тариф", "Plan"), readonly = true),
                        Field("perDay", words.t("В сутки", "Per day"), Formats.MONEY, readonly = true),
                        Field("rps", words.t("Запросы", "Requests"), readonly = true, section = metrics),
                        Field("latency", words.t("Ответ", "Response"), readonly = true, section = metrics),
                        Field("cpu", words.t("Процессор", "CPU"), Formats.PERCENT, readonly = true, section = metrics),
                        Field("memory", words.t("Память", "Memory"), Formats.PERCENT, readonly = true, section = metrics),
                        Field("disk", words.t("Диск", "Disk"), Formats.PERCENT, readonly = true, section = metrics),
                        Field("ordersToday", words.t("Заказов сегодня", "Orders today"), Formats.NUMBER, readonly = true, section = metrics),
                        Field("metricsNote", words.t("Метрики", "Metrics"), readonly = true, section = metrics),
                        Field("region", words.t("Регион", "Region"), readonly = true, section = words.t("Сведения", "Details")),
                        Field("version", words.t("Версия RestoApp", "RestoApp version"), readonly = true, section = words.t("Сведения", "Details")),
                    ),
                ),
                "event" to Model(listOf(Field("title", words.t("Событие", "Event"), readonly = true), Field("time", words.t("Когда", "When"), readonly = true))),
                "spend" to Model(listOf(Field("perDay", words.t("В сутки", "Per day"), Formats.MONEY, readonly = true))),
            ),
            methods = listOf(
                DeclaredMethod(
                    "services.list", DeclaredMethod.KIND_QUERY, words.t("Сервисы", "Services"),
                    objectSchema(emptyList(), mapOf("status" to enumProperty(listOf("all") + STATUSES, "all", mapOf("all" to words.t("Все", "All")) + statusTitles), "query" to stringProperty(100))),
                    model = "service",
                    ui = Ui(
                        view = Views.LIST, open = "services.get", chips = "status", search = "query",
                        item = ItemUi(title = "title", subtitle = "host", badge = "status", meta = "plan", value = "perDay", series = "load"),
                    ),
                ),
                DeclaredMethod(
                    "services.get", DeclaredMethod.KIND_QUERY, words.t("Сервис", "Service"), objectSchema(listOf("id"), mapOf("id" to stringProperty(100))),
                    model = "service",
                    ui = Ui(
                        view = Views.DETAIL, section = words.t("Обзор", "Overview"),
                        item = ItemUi(title = "title", subtitle = "host", badge = "status"),
                        summary = listOf(
                            SummaryBlock(SummaryBlock.METRIC, "cpu", format = Formats.PERCENT),
                            SummaryBlock(SummaryBlock.SERIES, "cpuSeries", title = words.t("Процессор за сутки", "CPU, last day"), format = Formats.PERCENT),
                            SummaryBlock(SummaryBlock.METRIC, "memory", format = Formats.PERCENT),
                            SummaryBlock(SummaryBlock.SERIES, "memorySeries", title = words.t("Память за сутки", "Memory, last day"), format = Formats.PERCENT),
                            SummaryBlock(SummaryBlock.METRIC, "disk", format = Formats.PERCENT),
                            SummaryBlock(SummaryBlock.SERIES, "diskSeries", title = words.t("Диск за сутки", "Disk, last day"), format = Formats.PERCENT),
                            SummaryBlock(SummaryBlock.METRIC, "rps"),
                            SummaryBlock(SummaryBlock.SERIES, "rpsSeries", title = words.t("Запросов в секунду", "Requests per second"), format = Formats.NUMBER),
                            SummaryBlock(SummaryBlock.METRIC, "latency"),
                            SummaryBlock(SummaryBlock.METRIC, "ordersToday"),
                        ),
                    ),
                ),
                DeclaredMethod(
                    "services.events", DeclaredMethod.KIND_QUERY, words.t("События", "Events"), objectSchema(listOf("id"), mapOf("id" to stringProperty(100))),
                    model = "event", ui = Ui(view = Views.LIST, section = words.t("События", "Events"), item = ItemUi(title = "title", meta = "time")),
                ),
                DeclaredMethod(
                    "services.spend", DeclaredMethod.KIND_QUERY, words.t("Расходы", "Spend"), objectSchema(listOf("id"), mapOf("id" to stringProperty(100))),
                    model = "spend",
                    ui = Ui(view = Views.DETAIL, section = words.t("Расходы", "Spend"), summary = listOf(SummaryBlock(SummaryBlock.METRIC, "perDay", format = Formats.MONEY), SummaryBlock(SummaryBlock.SERIES, "days", format = Formats.MONEY))),
                ),
                DeclaredMethod(
                    "services.power", DeclaredMethod.KIND_MUTATION, words.t("Питание", "Power"),
                    objectSchema(listOf("id", "power"), mapOf("id" to stringProperty(100), "power" to enumProperty(POWER.keys.toList(), null, POWER.mapValues { words.t(it.value.first, it.value.second) }))),
                    ui = Ui(
                        view = Views.ACTION, on = "service", placement = listOf("detail"), primary = true,
                        control = Control(Controls.BUTTONS, param = "power", tones = mapOf("stop" to Tones.DANGER)),
                        confirm = words.t("Выключить сервис? Сайт и касса ресторана перестанут работать.", "Switch the service off? The site and the till stop."),
                    ),
                ),
            ),
        )
    }

    private fun balanceApp(words: DemoWords) = DeclaredApp(
        id = "balance", version = "1.0.0", title = words.t("Баланс", "Balance"), home = "balance.get",
        icon = Icon(WALLET_ICON),
        models = mapOf(
            "balance" to Model(
                listOf(
                    Field("balance", words.t("На счёте", "Balance"), Formats.MONEY, readonly = true),
                    Field("daysLeft", words.t("Хватит на, дней", "Lasts, days"), Formats.NUMBER, readonly = true),
                    Field("perDay", words.t("Расход в сутки", "Spend a day"), Formats.MONEY, readonly = true),
                    Field("perHour", words.t("В час", "An hour"), Formats.MONEY, readonly = true),
                    Field("monthToDate", words.t("С начала месяца", "This month"), Formats.MONEY, readonly = true),
                    Field("previousMonth", words.t("Прошлый месяц", "Last month"), Formats.MONEY, readonly = true),
                    Field("currency", words.t("Валюта", "Currency"), readonly = true),
                ),
            ),
            "transaction" to Model(listOf(Field("title", words.t("Операция", "Transaction"), readonly = true), Field("detail", words.t("Что", "What"), readonly = true), Field("amount", words.t("Сумма", "Amount"), Formats.MONEY, readonly = true))),
        ),
        methods = listOf(
            DeclaredMethod(
                "balance.get", DeclaredMethod.KIND_QUERY, words.t("Баланс", "Balance"), objectSchema(emptyList(), emptyMap()), model = "balance",
                ui = Ui(
                    view = Views.DETAIL, section = words.t("Обзор", "Overview"), item = ItemUi(title = "title"),
                    summary = listOf(
                        SummaryBlock(SummaryBlock.METRIC, "balance", title = words.t("Баланс", "Balance"), format = Formats.MONEY, tone = "balanceTone"),
                        SummaryBlock(SummaryBlock.METRIC, "perDay", format = Formats.MONEY),
                        SummaryBlock(SummaryBlock.METRIC, "monthToDate", format = Formats.MONEY),
                        SummaryBlock(SummaryBlock.METRIC, "previousMonth", format = Formats.MONEY),
                        SummaryBlock(SummaryBlock.SERIES, "days", title = words.t("Расход за 30 дней", "Spend, 30 days"), format = Formats.MONEY),
                        SummaryBlock(SummaryBlock.SHARE, "byService", title = words.t("По сервисам", "By service")),
                    ),
                ),
            ),
            DeclaredMethod(
                "balance.transactions", DeclaredMethod.KIND_QUERY, words.t("Операции", "Transactions"), objectSchema(emptyList(), emptyMap()), model = "transaction",
                ui = Ui(view = Views.LIST, section = words.t("Операции", "Transactions"), item = ItemUi(title = "title", subtitle = "detail", value = "amount")),
            ),
        ),
    )

    private fun apiApp(words: DemoWords) = DeclaredApp(
        id = "apiaccess", version = "1.0.0", title = words.t("Доступ к API", "API access"), home = "apikey.get",
        icon = Icon(KEY_ICON), category = words.t("Аккаунт", "Account"),
        models = mapOf("key" to Model(listOf(Field("prefix", words.t("Ключ", "Key"), readonly = true), Field("lastUsed", words.t("Последний вызов", "Last used"), readonly = true)))),
        methods = listOf(DeclaredMethod("apikey.get", DeclaredMethod.KIND_QUERY, words.t("Ключ API", "API key"), objectSchema(emptyList(), emptyMap()), model = "key", ui = Ui(view = Views.DETAIL, item = ItemUi(title = "title")))),
    )

    private fun accountApp(words: DemoWords) = DeclaredApp(
        id = "account", version = "1.0.0", title = words.t("Настройки аккаунта", "Account settings"), home = "account.get",
        icon = Icon(PERSON_ICON), category = words.t("Аккаунт", "Account"),
        models = mapOf(
            "account" to Model(
                listOf(
                    Field("login", words.t("Логин", "Login"), readonly = true),
                    Field("email", words.t("Почта", "Email"), readonly = true),
                    Field("currency", words.t("Валюта", "Currency"), readonly = true),
                ),
            ),
        ),
        methods = listOf(DeclaredMethod("account.get", DeclaredMethod.KIND_QUERY, words.t("Аккаунт", "Account"), objectSchema(emptyList(), emptyMap()), model = "account", ui = Ui(view = Views.DETAIL, item = ItemUi(title = "title", subtitle = "login")))),
    )

    public companion object {
        public const val TOKEN: String = "demo-cloud-token"
        public const val VERSION: String = "2026.11"

        private val STATUSES = listOf("online", "starting", "error", "stopped", "deleting")
        private val STATUS_TONES = mapOf("online" to Tones.SUCCESS, "starting" to Tones.INFO, "error" to Tones.DANGER, "stopped" to Tones.MUTED, "deleting" to Tones.MUTED)
        private val POWER = mapOf("start" to ("Запустить" to "Start"), "restart" to ("Перезапустить" to "Restart"), "stop" to ("Выключить" to "Switch off"))

        private fun statusTitle(words: DemoWords, status: String) = when (status) {
            "online" -> words.t("Работает", "Running")
            "starting" -> words.t("Запускается", "Starting")
            "error" -> words.t("Ошибка", "Error")
            "stopped" -> words.t("Выключен", "Stopped")
            else -> words.t("Удаляется", "Deleting")
        }

        /** The prototype's six services. */
        public val SERVICES: List<DemoCloudService> = listOf(
            DemoCloudService("pizza-north", "Пицца Север", "pizza-north.restocloud.app", "online", "Стандарт", 1.2, cpu = 34, memory = 61, disk = 42, rps = 38, latency = 84, ordersToday = 214, seed = 3, base = 0.38),
            DemoCloudService("georgian-yard", "Грузинский дворик", "dvorik.restocloud.app", "online", "Старт", 0.6, cpu = 18, memory = 83, disk = 27, rps = 9, latency = 112, ordersToday = 57, seed = 8, base = 0.22),
            DemoCloudService("sushi-market", "Суши Маркет — тест", "sushi-test.restocloud.app", "starting", "Старт", 0.6, seed = 5, base = 0.1, note = "Запускается: шаг 3 из 5"),
            DemoCloudService("burger-lenina", "Бургерная на Ленина", "burger-lenina.restocloud.app", "error", "Старт", 0.6, seed = 11, base = 0.7, note = "Не хватило памяти на тарифе «Старт» · упал 26 мин назад"),
            DemoCloudService("coffee-zerno", "Кофейня Зерно", "zerno.restocloud.app", "stopped", "Старт", 0.6),
            DemoCloudService("old-stand", "Старый стенд", "stand-old.restocloud.app", "deleting", "Старт", 0.6),
        )

        /** The account's spend over thirty days, oldest first. */
        private val SPEND: List<Double> = List(30) { i ->
            val base = if (i < 9) 28.8 else 43.2
            val extra = if (i in 12..16) 14.4 else 0.0
            ((base + extra + ((i * 37) % 7 - 3) * 0.4) * 10).roundToInt() / 10.0
        }

        private val SHARE = listOf("Пицца Север" to 489.6, "Грузинский дворик" to 244.8, "Бургерная на Ленина" to 172.2, "Кофейня Зерно" to 67.2, "Старый стенд" to 43.2)

        private fun dayLabel(i: Int): String = (19 + i).let { d -> if (d <= 31) "$d окт" else "${d - 31} ноя" }

        internal fun series(seed: Int, n: Int = 32, base: Double = 0.35, amp: Double = 0.25): List<Double> {
            var x = seed * 9301 + 49297
            return List(n) { i ->
                x = (x * 9301 + 49297) % 233280
                val noise = x / 233280.0 - 0.5
                (base + amp * sin(i / n.toDouble() * 2 * PI * 1.5 + seed) * 0.6 + noise * amp * 0.45).coerceIn(0.02, 0.98)
            }
        }

        private const val DASHBOARD_ICON = "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"
        private const val SERVICES_ICON =
            "M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"
        private const val WALLET_ICON =
            "M21 18v1c0 1.1-.9 2-2 2H5c-1.11 0-2-.9-2-2V5c0-1.1.89-2 2-2h14c1.1 0 2 .9 2 2v1h-9c-1.11 0-2 .9-2 2v8c0 1.1.89 2 2 2h9zm-9-2h10V8H12v8zm4-2.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z"
        private const val KEY_ICON = "M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"
        private const val PERSON_ICON = "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"
    }
}
