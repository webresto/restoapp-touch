package cx.m42.restoapp.demo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The data of «Настройки», «Каналы продаж» and «Система» on the demo server. The settings of core, the types of
 * channel and their capabilities are core's (`settings/`, `SalesChannelRegistry`), cut to a set that shows every state
 * a screen can be in. The settings of the providers, the programs and the marketplace are made up: core has no such
 * rows, and a real marketplace is reached by a licence.
 */

// ---- «Настройки» ----

/** One setting as its manifest declares it. What was set lives in [DemoState.settingValues], by key. */
internal data class DemoSetting(
    val key: String,
    val type: String,
    val name: Pair<String, String>,
    val description: Pair<String, String>? = null,
    val default: JsonElement? = null,
    val schema: JsonObject? = null,
    val patternHint: Pair<String, String>? = null,
    val required: Boolean = false,
    val restart: Boolean = false,
    val readOnly: Boolean = false,
    val secret: Boolean = false,
    /** What the environment pins it to; the operator cannot write over it. */
    val env: JsonElement? = null,
    /** The module it came with: the rows of a module that is not installed are not there. */
    val module: String? = null,
)

private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

private fun enumSchema(vararg values: String) = buildJsonObject {
    put("type", "string")
    put("enum", strings(*values))
}

private fun integerSchema(minimum: Int, maximum: Int? = null) = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    maximum?.let { put("maximum", it) }
}

private fun listSchema(items: JsonObject) = buildJsonObject {
    put("type", "array")
    put("items", items)
}

internal object DemoSettings {
    private val countries = arrayOf<String>("RU", "BY", "KZ", "AM", "GE", "RS", "TR", "AE")

    val all: List<DemoSetting> = listOf(
        DemoSetting(
            "ALLOW_BONUS_SPENDING", "boolean", "Списание бонусов" to "Allow bonus spending",
            "Может ли гость платить бонусами" to "Whether a guest may pay with bonuses",
            default = JsonPrimitive(false), required = true,
        ),
        DemoSetting(
            "ALLOWED_PHONE_COUNTRIES", "json", "Страны телефонов" to "Allowed phone countries",
            "С каких номеров можно войти" to "Which numbers may sign in",
            schema = listSchema(enumSchema(*countries)), required = true,
        ),
        DemoSetting(
            "CITY", "string", "Город" to "City",
            schema = buildJsonObject {
                put("type", "string")
                put("minLength", 2)
                put("maxLength", 100)
            },
            required = true,
        ),
        DemoSetting("COUNTRY_ISO", "string", "Страна" to "Country ISO", schema = enumSchema(*countries), required = true),
        DemoSetting("DEFAULT_CURRENCY_ISO", "string", "Валюта" to "Default currency ISO", schema = enumSchema("RUB", "BYN", "KZT", "AMD", "GEL", "EUR", "USD"), required = true),
        DemoSetting("DEFAULT_LOCALE", "string", "Язык по умолчанию" to "Default locale", schema = enumSchema("en", "ru", "fr", "de"), required = true),
        DemoSetting(
            "DELIVERY_MAX_RADIUS_KM", "number", "Максимальное расстояние доставки, км" to "Maximum delivery distance, km",
            "От кухни до гостя; 0 — без ограничения" to "From the kitchen to the guest; 0 means no limit",
            default = JsonPrimitive(0),
        ),
        DemoSetting(
            "DISH_PLACE_BALANCE_MODE", "string", "Режим остатков по точкам" to "Product balance mode by place",
            default = JsonPrimitive("minimum"), schema = enumSchema("local-only", "rms-only", "minimum"), required = true,
        ),
        DemoSetting(
            "FRONTEND_CHECKOUT_PAGE", "string", "Страница оформления на сайте" to "Frontend checkout page",
            required = true, module = "admin-frontend",
        ),
        DemoSetting(
            "IMAGES_URL", "string", "Адрес картинок" to "Images URL",
            schema = buildJsonObject {
                put("type", "string")
                put("pattern", "^https?://.+/$")
            },
            env = JsonPrimitive("https://img.demo.example/"),
        ),
        DemoSetting(
            "JWT_SECRET", "string", "Секрет токенов" to "Token secret",
            "Им подписаны токены гостей" to "What the tokens of guests are signed with",
            required = true, restart = true, secret = true, module = "graphql",
        ),
        DemoSetting(
            "KITCHEN_RESOLVE_CHAIN", "json", "Выбор кухни" to "Kitchen resolution chain",
            default = strings("delivery-zone", "rms", "nearest-geo", "single-point"),
            schema = listSchema(enumSchema("delivery-zone", "rms", "nearest-geo", "single-point")),
            required = true,
        ),
        DemoSetting(
            "MEDIAFILE_PARALLEL_TO_DOWNLOAD", "number", "Картинок качать одновременно" to "Media files to download at once",
            default = JsonPrimitive(2), schema = integerSchema(1, 4),
        ),
        DemoSetting(
            "PROJECT_NAME", "string", "Идентификатор проекта" to "Project ID",
            schema = buildJsonObject {
                put("type", "string")
                put("pattern", "^[a-z0-9\\-]+$")
                put("minLength", 3)
            },
            patternHint = "Только строчные латинские буквы, цифры и дефис" to "Lowercase Latin letters, digits and a hyphen only",
            required = true,
        ),
        DemoSetting("RMS_ADAPTER", "string", "Адаптер RMS" to "RMS adapter", restart = true),
        DemoSetting(
            "RMS_LAST_SUCCESSFUL_MENU_DISHES_SYNC_AT", "string", "Последняя синхронизация меню" to "Last successful menu sync",
            default = JsonPrimitive(""), readOnly = true,
        ),
        DemoSetting(
            "SALES_CHANNEL_MAX_TOKEN", "string", "Токен бота MAX" to "MAX bot token",
            required = true, secret = true, module = "sales-channel-max",
        ),
        DemoSetting(
            "SALES_CHANNEL_QR_MENU_URL", "string", "Адрес меню по QR" to "QR menu address",
            required = true, module = "sales-channel-qr",
        ),
        DemoSetting(
            "SALES_CHANNEL_TELEGRAM_TOKEN", "string", "Токен Telegram-бота" to "Telegram bot token",
            "Его выдаёт @BotFather" to "@BotFather gives it out",
            required = true, secret = true, module = "sales-channel-telegram",
        ),
        DemoSetting(
            "SALES_CHANNEL_VK_APP_ID", "string", "ID мини-приложения VK" to "VK mini app id",
            required = true, module = "sales-channel-vk",
        ),
        DemoSetting(
            "SHOW_UNAVAILABLE_DISHES", "boolean", "Показывать блюда на стопе" to "Show unavailable dishes",
            default = JsonPrimitive(false),
        ),
        DemoSetting(
            "SYNC_PRODUCTS_INTERVAL_SECONDS", "number", "Как часто синхронизировать меню, с" to "Sync products interval, s",
            default = JsonPrimitive(600), schema = integerSchema(120), required = true, restart = true,
        ),
        DemoSetting(
            "TZ", "string", "Часовой пояс" to "Timezone",
            schema = enumSchema("Europe/Kaliningrad", "Europe/Moscow", "Europe/Samara", "Asia/Yekaterinburg", "Asia/Novosibirsk", "Asia/Vladivostok", "Etc/UTC"),
            restart = true,
        ),
        DemoSetting(
            "WORK_TIME", "json", "Часы работы" to "Work time",
            default = JsonArray(
                listOf(
                    buildJsonObject {
                        put("start", "10:00")
                        put("stop", "22:00")
                    },
                ),
            ),
            schema = listSchema(
                buildJsonObject {
                    put("type", "object")
                    put("required", strings("start", "stop"))
                    put(
                        "properties",
                        buildJsonObject {
                            put("start", buildJsonObject { put("type", "string") })
                            put("stop", buildJsonObject { put("type", "string") })
                        },
                    )
                },
            ),
        ),
    )

    /** What the server starts with: the facts of a working restaurant, and one thing changed off its default. */
    val initial: Map<String, JsonElement> = mapOf(
        "ALLOWED_PHONE_COUNTRIES" to strings("RU", "BY", "KZ"),
        "CITY" to JsonPrimitive("Москва"),
        "COUNTRY_ISO" to JsonPrimitive("RU"),
        "DEFAULT_CURRENCY_ISO" to JsonPrimitive("RUB"),
        "DEFAULT_LOCALE" to JsonPrimitive("ru"),
        "DELIVERY_MAX_RADIUS_KM" to JsonPrimitive(15),
        "FRONTEND_CHECKOUT_PAGE" to JsonPrimitive("/checkout"),
        "JWT_SECRET" to JsonPrimitive("demo-secret"),
        "PROJECT_NAME" to JsonPrimitive("demo-restaurant"),
        "RMS_LAST_SUCCESSFUL_MENU_DISHES_SYNC_AT" to JsonPrimitive("05.11.2026 09:00"),
        "SALES_CHANNEL_QR_MENU_URL" to JsonPrimitive("/qr"),
        "SHOW_UNAVAILABLE_DISHES" to JsonPrimitive(true),
        "TZ" to JsonPrimitive("Europe/Moscow"),
    )
}

// ---- «Каналы продаж» ----

/** A type of channel, as core's registry has it. */
internal data class DemoChannelType(
    val id: String,
    val title: Pair<String, String>,
    val category: String,
    val capabilities: List<String>,
    /** Null: it ships with the server, or has no provider by design. */
    val provider: String? = null,
    val countries: List<String> = emptyList(),
    val comingSoon: Boolean = false,
    /** What the matrix of core recommends for the country of this server. */
    val recommended: Boolean = false,
) {
    val tag: String get() = "sales-channel:$id"
}

internal object DemoChannelTypes {
    private val messenger = listOf("orders:create", "menu:browse", "customers:identify", "notifications:reply", "webhooks:incoming")

    val all: List<DemoChannelType> = listOf(
        DemoChannelType(
            "web-storefront", "Сайт" to "Website", "storefront",
            listOf("orders:create", "orders:pay", "orders:status", "menu:browse", "menu:concept-filter", "customers:anonymous", "assets:frontend"),
            provider = "admin-frontend", recommended = true,
        ),
        DemoChannelType(
            "mobile-pos", "Касса персонала (приложение)" to "Staff POS (mobile app)", "staff",
            listOf("orders:create", "admin:order-entry", "menu:browse", "customers:identify"),
        ),
        DemoChannelType("telegram-bot", "Telegram-бот" to "Telegram bot", "messenger", messenger, provider = "sales-channel-telegram", countries = listOf("RU", "BY", "KZ"), recommended = true),
        DemoChannelType("vk-miniapp", "Мини-приложение VK" to "VK mini app", "social", listOf("orders:create", "menu:browse", "customers:identify"), provider = "sales-channel-vk", countries = listOf("RU", "BY"), recommended = true),
        DemoChannelType("max-bot", "Бот в MAX" to "MAX bot", "messenger", messenger, provider = "sales-channel-max", countries = listOf("RU"), recommended = true),
        DemoChannelType("qr-table-order", "Заказ со стола по QR" to "QR table ordering", "kiosk", listOf("orders:create", "menu:browse"), provider = "sales-channel-qr"),
        DemoChannelType("mobile-app", "Мобильное приложение" to "Mobile app", "mobile", listOf("orders:create", "orders:pay", "menu:browse"), provider = "sales-channel-mobile", comingSoon = true, recommended = true),
        DemoChannelType("kiosk", "Киоск самообслуживания" to "Self-order kiosk", "kiosk", listOf("orders:create", "menu:browse"), provider = "sales-channel-kiosk", comingSoon = true),
        DemoChannelType("whatsapp-business", "WhatsApp Business" to "WhatsApp Business", "messenger", messenger, provider = "sales-channel-whatsapp", comingSoon = true),
        DemoChannelType("custom", "Свой канал" to "Custom channel", "custom", listOf("orders:create"), comingSoon = true),
    )

    fun of(id: String): DemoChannelType? = all.firstOrNull { it.id == id }
}

/** A configured channel: core's `SalesChannel` row. Its state is counted from the modules and the settings. */
@Serializable
internal data class DemoChannel(
    val id: String,
    val key: String,
    val titleRu: String,
    val titleEn: String,
    val type: String,
    val enabled: Boolean,
    val url: String? = null,
    val concepts: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    /** How long ago it was last changed when the server was set up. */
    val agoMinutes: Int = 0,
    /** The moment of a change made here, which wins over [agoMinutes]. */
    val changedAt: Long? = null,
)

internal object DemoChannels {
    val all: List<DemoChannel> = listOf(
        DemoChannel("ch-web", "web-main", "Сайт ресторана", "Restaurant website", "web-storefront", enabled = true, url = "https://demo-restaurant.example", countries = listOf("RU"), platforms = listOf("web"), agoMinutes = 3 * 24 * 60),
        DemoChannel("ch-pos", "mobile-pos", "Касса в приложении", "POS in the app", "mobile-pos", enabled = true, agoMinutes = 12 * 24 * 60),
        DemoChannel("ch-telegram", "telegram-main", "Telegram-бот", "Telegram bot", "telegram-bot", enabled = false, concepts = listOf("Пицца"), countries = listOf("RU"), agoMinutes = 95),
        DemoChannel("ch-vk", "vk-app", "Мини-приложение VK", "VK mini app", "vk-miniapp", enabled = true, countries = listOf("RU", "BY"), agoMinutes = 40 * 24 * 60),
        DemoChannel("ch-qr", "qr-hall", "QR на столах", "QR on the tables", "qr-table-order", enabled = false, concepts = listOf("Пицца", "Суши"), agoMinutes = 7 * 24 * 60),
    )
}

// ---- «Система» ----

/** A program of this server: core's `Module` row and what its package says. */
@Serializable
internal data class DemoModule(
    val appId: String,
    val name: String,
    val version: String,
    val latestVersion: String? = null,
    val system: Boolean = false,
    val enable: Boolean = true,
    val descriptionRu: String = "",
    val descriptionEn: String = "",
    val author: String = "",
    val license: String = "",
    val keywords: String = "",
    val dependencies: List<String> = emptyList(),
    val migrationsFiles: Int = 0,
    val migrationsProcessed: Int = 0,
    val forbidDelete: Boolean = false,
)

/** A program of the marketplace. */
internal data class DemoMarketItem(
    val appId: String,
    val name: String,
    val teaser: Pair<String, String>,
    val description: Pair<String, String>,
    val author: String,
    val tags: String,
    val latestVersion: String,
    /** Why it cannot be installed at all: `license`, `download`. */
    val block: String? = null,
    /** It is offered, and the job fails on the check of compatibility: what such a failure looks like. */
    val needsCore: String? = null,
)

internal object DemoModules {
    private const val TEAM = "RestoApp"
    private const val LICENSE = "MIT"

    val all: List<DemoModule> = listOf(
        DemoModule(
            "core", "RestoCore", "2.14.0", system = true, forbidDelete = true,
            descriptionRu = "Заказы, меню и правила ресторана", descriptionEn = "Orders, the menu and the rules of a restaurant",
            author = TEAM, license = "AGPL-3.0", keywords = "restaurant, orders", migrationsFiles = 42, migrationsProcessed = 42,
        ),
        DemoModule(
            "graphql", "GraphQL API", "2.14.0", system = true, forbidDelete = true,
            descriptionRu = "API для сайта и приложений гостей", descriptionEn = "The API of the site and of the guests' apps",
            author = TEAM, license = "AGPL-3.0", keywords = "api, graphql", dependencies = listOf("core"), migrationsFiles = 6, migrationsProcessed = 6,
        ),
        DemoModule(
            "app-mobile-api", "Мобильный API", "1.0.0", system = true, forbidDelete = true,
            descriptionRu = "То, с чем разговаривает это приложение", descriptionEn = "What this app talks to",
            author = TEAM, license = "AGPL-3.0", keywords = "api, mobile", dependencies = listOf("core"), migrationsFiles = 3, migrationsProcessed = 3,
        ),
        DemoModule(
            "simple-navigation", "Простая навигация", "1.2.1",
            descriptionRu = "Меню сайта", descriptionEn = "The menu of the site",
            author = TEAM, license = LICENSE, keywords = "navigation, menu", dependencies = listOf("core"),
        ),
        DemoModule(
            "admin-frontend", "Сайт ресторана", "1.4.2", latestVersion = "1.5.0",
            descriptionRu = "Витрина с заказом и оплатой", descriptionEn = "A storefront with ordering and payment",
            author = TEAM, license = LICENSE, keywords = "storefront, sales-channel", dependencies = listOf("core", "graphql", "simple-navigation"), migrationsFiles = 4, migrationsProcessed = 4,
        ),
        DemoModule(
            "sales-channel-telegram", "Telegram-бот", "0.9.3",
            descriptionRu = "Заказ прямо в Telegram", descriptionEn = "Ordering right in Telegram",
            author = TEAM, license = LICENSE, keywords = "sales-channel, telegram", dependencies = listOf("core"),
        ),
        DemoModule(
            "sales-channel-qr", "Заказ по QR", "1.1.0",
            descriptionRu = "Гость заказывает со стола", descriptionEn = "A guest orders from the table",
            author = TEAM, license = LICENSE, keywords = "sales-channel, qr", dependencies = listOf("core"),
        ),
        DemoModule(
            "delivery-zones", "Зоны доставки", "2.0.1",
            descriptionRu = "Зоны на карте и цена по ним", descriptionEn = "Zones on the map and the price by them",
            author = TEAM, license = LICENSE, keywords = "delivery", dependencies = listOf("core"), migrationsFiles = 5, migrationsProcessed = 3,
        ),
        DemoModule(
            "reports-legacy", "Старые отчёты", "0.3.0", enable = false,
            descriptionRu = "Отчёты прошлой версии админки", descriptionEn = "The reports of the previous admin panel",
            author = TEAM, license = LICENSE, keywords = "reports", dependencies = listOf("core"),
        ),
    )

    val market: List<DemoMarketItem> = listOf(
        DemoMarketItem(
            "sales-channel-vk", "Мини-приложение VK", "Заказ внутри VK" to "Ordering inside VK",
            "Гости заказывают внутри VK, а заказы приходят сюда же." to "Guests order inside VK and the orders come here.",
            TEAM, "sales-channel, sales-channel:vk-miniapp", "1.3.0",
        ),
        DemoMarketItem(
            "sales-channel-max", "Бот в MAX", "Заказ в мессенджере MAX" to "Ordering in the MAX messenger",
            "Меню и заказ в чате, статус заказа там же." to "The menu and the order in a chat, with the order's state in it.",
            TEAM, "sales-channel, sales-channel:max-bot", "0.8.0",
        ),
        DemoMarketItem(
            "loyalty-bonuses", "Бонусная программа", "Бонусы и уровни гостей" to "Bonuses and levels of guests",
            "Начисление и списание бонусов, уровни и рассылки." to "Earning and spending bonuses, levels and mailings.",
            "Партнёр", "loyalty", "3.1.0", block = "license",
        ),
        DemoMarketItem(
            "kitchen-screen", "Экран кухни", "Заказы на экране повара" to "Orders on the cook's screen",
            "Заменяет бумажные чеки на кухне." to "Replaces the paper tickets in the kitchen.",
            TEAM, "kitchen", "0.2.0", block = "download",
        ),
        DemoMarketItem(
            "payment-terminal", "Оплата на терминале", "Карта на терминале кассы" to "A card at the till's terminal",
            "Касса принимает карту прямо на своём терминале." to "The POS takes a card right at its terminal.",
            TEAM, "payment", "2.0.0", needsCore = "3.0.0",
        ),
    )

    fun marketOf(appId: String): DemoMarketItem? = market.firstOrNull { it.appId == appId }
}
