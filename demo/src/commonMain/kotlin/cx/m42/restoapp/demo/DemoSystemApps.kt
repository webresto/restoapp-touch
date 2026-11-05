package cx.m42.restoapp.demo

import cx.m42.restoapp.protocol.Control
import cx.m42.restoapp.protocol.Controls
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.Formats
import cx.m42.restoapp.protocol.Icon
import cx.m42.restoapp.protocol.ItemUi
import cx.m42.restoapp.protocol.Model
import cx.m42.restoapp.protocol.Special
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.protocol.Ui
import cx.m42.restoapp.protocol.Views
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * «Каналы продаж», «Настройки» and «Система» as the hook declares them (`apps/channels.ts`, `apps/settings.ts`,
 * `apps/system.ts`), method for method and field for field, with the words of its `locales/`. They belong to the
 * administrator: all three are rights of the admin panel, and no other role of the demo holds them.
 */

internal const val CHANNELS = "channels"
internal const val SETTINGS = "settings"
internal const val SYSTEM = "system"

private fun DemoWords.systemCategory() = t("Система", "System")

private fun idInput() = objectSchema(listOf("id"), mapOf("id" to stringProperty(100, minLength = 1)))

/** Material "store". */
private const val CHANNELS_ICON = "M20 4H4v2h16V4zm1 10v-2l-1-5H4l-1 5v2h1v6h10v-6h4v6h2v-6h1zm-9 4H6v-4h6v4z"

internal fun channelsApp(words: DemoWords): DeclaredApp {
    val binding = words.t("Привязка концепций", "Concept binding")
    val type = words.t("Тип канала", "Channel type")
    val links = words.t("Ссылки", "Links")
    val provider = words.t("Провайдер", "Provider")
    val status = words.t("Состояние", "Status")
    val category = words.t("Категория", "Category")
    val capabilities = words.t("Возможности", "Capabilities")
    val countries = words.t("Страны", "Countries")
    val needsSetup = words.t("Требует настройки", "Needs setup")
    val failed = words.t("Ошибка", "Error")
    val all = words.t("Все", "All")
    return DeclaredApp(
        id = CHANNELS,
        version = "1.0.0",
        title = words.t("Каналы продаж", "Sales channels"),
        icon = Icon(CHANNELS_ICON),
        category = words.systemCategory(),
        home = "channels.list",
        scope = DeclaredApp.SCOPE_SERVER,
        models = mapOf(
            "channel" to Model(
                listOf(
                    Field(
                        "status", status, Formats.ENUM, readonly = true,
                        specials = listOf(
                            Special(JsonPrimitive("ready"), words.t("Работает", "Working"), Tones.SUCCESS),
                            Special(JsonPrimitive("needs_setup"), needsSetup, Tones.WARNING),
                            Special(JsonPrimitive("draft"), words.t("Черновик", "Draft"), Tones.INFO),
                            Special(JsonPrimitive("disabled"), words.t("Выключен", "Disabled"), Tones.INFO),
                            Special(JsonPrimitive("error"), failed, Tones.DANGER),
                        ),
                    ),
                    Field("statusMessage", words.t("Провайдер сказал", "The provider says"), Formats.TEXT, readonly = true),
                    Field("providerText", provider, Formats.TEXT, readonly = true),
                    Field("providerHint", words.t("Что делать", "What to do"), Formats.TEXT, readonly = true),
                    Field("enabledText", words.t("Включён", "Enabled"), Formats.BOOLEAN, readonly = true),
                    Field("conceptsText", words.t("Концепции", "Concepts"), Formats.TEXT, readonly = true, section = binding),
                    Field("countriesText", countries, Formats.TEXT, readonly = true, section = binding),
                    Field("platformsText", words.t("Платформы", "Platforms"), Formats.TEXT, readonly = true, section = binding),
                    Field("typeTitle", words.t("Тип", "Type"), Formats.TEXT, readonly = true, section = type),
                    Field("category", category, Formats.TEXT, readonly = true, section = type),
                    Field("capabilitiesText", capabilities, Formats.TEXT, readonly = true, section = type),
                    Field("url", words.t("Ссылка", "Link"), Formats.TEXT, readonly = true, section = links),
                    Field("updatedAt", words.t("Изменён", "Updated"), Formats.RELATIVE, readonly = true, section = links),
                ),
            ),
            "channelType" to Model(
                listOf(
                    Field(
                        "availability", status, Formats.ENUM, readonly = true,
                        specials = listOf(Special(JsonPrimitive("coming_soon"), words.t("Скоро появится", "Coming soon"), Tones.INFO)),
                    ),
                    Field("stateText", provider, Formats.TEXT, readonly = true),
                    Field("providerText", words.t("Модуль", "Module"), Formats.TEXT, readonly = true),
                    Field("category", category, Formats.TEXT, readonly = true),
                    Field("capabilitiesText", capabilities, Formats.TEXT, readonly = true),
                    Field("countriesText", countries, Formats.TEXT, readonly = true),
                ),
            ),
        ),
        methods = listOf(
            DeclaredMethod(
                name = "channels.list",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Каналы продаж", "Sales channels"),
                model = "channel",
                input = objectSchema(
                    emptyList(),
                    mapOf(
                        "q" to stringProperty(100),
                        "state" to enumProperty(
                            listOf("all", "active", "off", "needs_setup", "error"), "all",
                            mapOf(
                                "all" to all,
                                "active" to words.t("Работают", "Working"),
                                "off" to words.t("Выключены", "Switched off"),
                                "needs_setup" to needsSetup,
                                "error" to failed,
                            ),
                        ),
                        "cursor" to stringProperty(200),
                    ),
                ),
                ui = Ui(
                    view = Views.LIST,
                    item = ItemUi(title = "title", subtitle = "subtitle", meta = "providerText", badge = "status"),
                    search = "q",
                    chips = "state",
                    open = "channels.get",
                    empty = words.t("Каналов продаж пока нет", "No sales channels yet"),
                ),
            ),
            DeclaredMethod(
                name = "channels.get",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Канал продаж", "Sales channel"),
                model = "channel",
                input = idInput(),
                ui = Ui(view = Views.DETAIL, item = ItemUi(title = "title", subtitle = "subtitle", badge = "status")),
            ),
            DeclaredMethod(
                name = "channels.toggle",
                kind = DeclaredMethod.KIND_MUTATION,
                title = words.t("Включён", "Enabled"),
                input = objectSchema(listOf("id", "enabled"), mapOf("id" to stringProperty(100, minLength = 1), "enabled" to booleanProperty())),
                ui = Ui(
                    view = Views.ACTION,
                    on = "channel",
                    placement = listOf("row", "detail"),
                    control = Control(type = Controls.TOGGLE, param = "enabled", bind = "enabledText"),
                ),
            ),
            DeclaredMethod(
                name = "channels.delete",
                kind = DeclaredMethod.KIND_MUTATION,
                title = words.t("Удалить канал", "Delete the channel"),
                input = idInput(),
                ui = Ui(
                    view = Views.ACTION,
                    on = "channel",
                    placement = listOf("detail"),
                    control = Control(type = Controls.BUTTONS),
                    confirm = words.t("Удалить канал? Существующие заказы сохранят источник для отчётов.", "Delete the channel? Existing orders keep their source for reports."),
                ),
            ),
            DeclaredMethod(
                name = "channels.types",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Типы каналов", "Channel types"),
                model = "channelType",
                input = objectSchema(
                    emptyList(),
                    mapOf(
                        "kind" to enumProperty(
                            listOf("recommended", "all"), "recommended",
                            mapOf("recommended" to words.t("Рекомендуем", "Recommended"), "all" to all),
                        ),
                        "cursor" to stringProperty(200),
                    ),
                ),
                ui = Ui(
                    view = Views.LIST,
                    item = ItemUi(title = "title", subtitle = "stateText", badge = "availability"),
                    chips = "kind",
                    open = "channels.type",
                    empty = words.t("Всё рекомендованное уже здесь. Посмотрите все типы.", "Everything recommended is already here. See all the types."),
                ),
            ),
            DeclaredMethod(
                name = "channels.type",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Тип канала", "Channel type"),
                model = "channelType",
                input = idInput(),
                ui = Ui(view = Views.DETAIL, item = ItemUi(title = "title", badge = "availability")),
            ),
        ),
    )
}

/** Material Symbols "settings". */
private const val SETTINGS_ICON =
    "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"

internal fun settingsApp(words: DemoWords): DeclaredApp {
    val about = words.t("Описание", "Description")
    val fromEnv = words.t("Из окружения", "From the environment")
    val restart = words.t("Нужен перезапуск", "Restart required")
    val key = stringProperty(100, minLength = 1)
    val keys = buildJsonObject {
        put("type", "array")
        put("maxItems", 500)
        put("items", key)
    }
    return DeclaredApp(
        id = SETTINGS,
        version = "1.0.0",
        title = words.t("Настройки", "Settings"),
        icon = Icon(SETTINGS_ICON),
        category = words.systemCategory(),
        home = "settings.list",
        scope = DeclaredApp.SCOPE_SERVER,
        models = mapOf(
            "setting" to Model(
                listOf(
                    Field(
                        "type", words.t("Тип", "Type"), Formats.ENUM, readonly = true,
                        titles = mapOf(
                            "string" to words.t("Строка", "String"),
                            "number" to words.t("Число", "Number"),
                            "boolean" to words.t("Да / нет", "Yes / no"),
                            "json" to "JSON",
                        ),
                    ),
                    Field("valueText", words.t("Значение", "Value"), Formats.TEXT, readonly = true),
                    Field("defaultText", words.t("По умолчанию", "Default"), Formats.TEXT, readonly = true),
                    Field("state", words.t("Состояние", "State"), Formats.TEXT, readonly = true),
                    Field("module", words.t("Модуль", "Module"), Formats.TEXT, readonly = true),
                    Field("description", about, Formats.TEXT, readonly = true, section = about),
                    Field("tooltip", words.t("Подсказка", "Hint"), Formats.TEXT, readonly = true, section = about),
                ),
            ),
        ),
        methods = listOf(
            DeclaredMethod(
                name = "settings.list",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Настройки", "Settings"),
                model = "setting",
                input = objectSchema(
                    emptyList(),
                    mapOf(
                        "q" to stringProperty(100),
                        "kind" to enumProperty(
                            listOf("all", "changed", "env", "restart"), "all",
                            mapOf("all" to words.t("Все", "All"), "changed" to words.t("Изменённые", "Changed"), "env" to fromEnv, "restart" to restart),
                        ),
                        "cursor" to stringProperty(200),
                    ),
                ),
                ui = Ui(
                    view = Views.LIST,
                    item = ItemUi(title = "key", subtitle = "name", meta = "state", value = "valueText", badge = "type"),
                    search = "q",
                    chips = "kind",
                    open = "settings.get",
                    empty = words.t("Настроек не нашлось", "No settings found"),
                ),
            ),
            DeclaredMethod(
                name = "settings.get",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Настройка", "Setting"),
                model = "setting",
                input = idInput(),
                ui = Ui(view = Views.DETAIL, item = ItemUi(title = "key", subtitle = "name", badge = "type")),
            ),
            DeclaredMethod(
                name = "settings.update",
                kind = DeclaredMethod.KIND_MUTATION,
                title = words.t("Изменить значение", "Change the value"),
                // Any JSON: the shape of a value is the setting's own schema, which the server checks itself.
                input = objectSchema(listOf("id", "value"), mapOf("id" to key, "value" to JsonObject(emptyMap()))),
                ui = Ui(
                    view = Views.ACTION,
                    on = "setting",
                    placement = listOf("detail"),
                    control = Control(type = Controls.NATIVE, screen = "setting-editor"),
                ),
            ),
            DeclaredMethod(
                name = "settings.dump",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Выгрузка настроек", "Settings dump"),
                input = objectSchema(emptyList(), emptyMap()),
            ),
            DeclaredMethod(
                name = "settings.transfer",
                kind = DeclaredMethod.KIND_MUTATION,
                title = words.t("Экспорт и импорт", "Export and import"),
                input = objectSchema(
                    listOf("settings"),
                    mapOf(
                        "settings" to buildJsonObject {
                            put("type", "array")
                            put("maxItems", 500)
                            put("items", objectSchema(listOf("key"), mapOf("key" to key, "value" to JsonObject(emptyMap()))))
                        },
                        "keys" to keys,
                        "preview" to booleanProperty(),
                    ),
                ),
                ui = Ui(
                    view = Views.ACTION,
                    on = "list",
                    placement = listOf("list"),
                    control = Control(type = Controls.NATIVE, screen = "setting-transfer"),
                ),
            ),
        ),
    )
}

/** Material Symbols "dns". */
private const val SYSTEM_ICON =
    "M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"

/** The two things the restart can be asked to do; a restart already planned is only ever brought forward. */
internal fun restartInput(words: DemoWords, whens: List<String> = listOf("midnight", "now")) = objectSchema(
    listOf("when"),
    mapOf(
        "when" to enumProperty(
            whens, default = null,
            mapOf(
                "midnight" to words.t("Перезапустить в полночь", "Restart at midnight"),
                "now" to words.t("Перезапустить сейчас", "Restart now"),
            ).filterKeys { it in whens },
        ),
    ),
)

internal fun systemApp(words: DemoWords): DeclaredApp {
    val server = words.t("Сервер", "Server")
    val programs = words.t("Программы", "Programs")
    val right = words.t("Что сейчас", "Right now")
    val versionSection = words.t("Версия", "Version")
    val readiness = words.t("Готовность", "Readiness")
    val aboutSection = words.t("О программе", "About")
    val relations = words.t("Связи", "Relations")
    val details = words.t("Подробности", "Details")
    val version = words.t("Версия", "Version")
    val latest = words.t("Версия в маркетплейсе", "Version in the marketplace")
    val migrations = words.t("Миграции", "Migrations")
    val state = words.t("Состояние", "State")
    val description = words.t("Описание", "Description")
    val author = words.t("Автор", "Author")
    val marketplace = words.t("Маркетплейс", "Marketplace")
    val systemWord = words.t("Системная", "System")
    val disabled = words.t("Выключена", "Switched off")

    fun job(name: String, title: String, on: String, confirm: String? = null) = DeclaredMethod(
        name = name,
        kind = DeclaredMethod.KIND_MUTATION,
        title = title,
        input = objectSchema(listOf("appId"), mapOf("appId" to stringProperty(100, minLength = 1), "job" to stringProperty(100))),
        ui = Ui(
            view = Views.ACTION,
            on = on,
            placement = listOf("detail"),
            control = Control(type = Controls.NATIVE, screen = "module-progress"),
            confirm = confirm,
        ),
    )

    return DeclaredApp(
        id = SYSTEM,
        version = "1.0.0",
        title = words.t("Система", "System"),
        icon = Icon(SYSTEM_ICON),
        category = words.systemCategory(),
        home = "system.home",
        scope = DeclaredApp.SCOPE_SERVER,
        models = mapOf(
            "serverCard" to Model(
                listOf(
                    Field("version", version, Formats.TEXT, readonly = true, section = server),
                    Field("timezone", words.t("Часовой пояс", "Time zone"), Formats.TEXT, readonly = true, section = server),
                    Field("currency", words.t("Валюта", "Currency"), Formats.TEXT, readonly = true, section = server),
                    Field("systemCount", words.t("Системных программ", "System programs"), Formats.NUMBER, readonly = true, section = programs),
                    Field("installedCount", words.t("Установленных программ", "Installed programs"), Formats.NUMBER, readonly = true, section = programs),
                    Field("disabledCount", words.t("Выключено", "Switched off"), Formats.NUMBER, readonly = true, section = programs),
                    Field("updatesCount", words.t("Доступно обновлений", "Updates available"), Formats.NUMBER, readonly = true, section = programs),
                    Field("restartText", words.t("Перезапуск", "Restart"), Formats.TEXT, readonly = true, section = right),
                    Field("migrationsText", migrations, Formats.TEXT, readonly = true, section = right),
                    Field("wizardText", words.t("Мастер установки", "Install wizard"), Formats.TEXT, readonly = true, section = right),
                    Field("marketplaceText", marketplace, Formats.TEXT, readonly = true, section = right),
                ),
            ),
            "module" to Model(
                listOf(
                    Field(
                        "state", state, Formats.ENUM, readonly = true,
                        specials = listOf(
                            Special(JsonPrimitive("ok"), words.t("Работает", "Working"), Tones.SUCCESS),
                            Special(JsonPrimitive("system"), systemWord, Tones.INFO),
                            Special(JsonPrimitive("update"), words.t("Обновление", "Update"), Tones.WARNING),
                            Special(JsonPrimitive("not-loaded"), words.t("Не загружена", "Not loaded"), Tones.WARNING),
                            Special(JsonPrimitive("disabled"), disabled, Tones.DANGER),
                            Special(JsonPrimitive("migrations"), words.t("Миграции не прошли", "Migrations did not pass"), Tones.DANGER),
                        ),
                    ),
                    Field("description", description, Formats.TEXT, readonly = true),
                    Field("version", version, Formats.TEXT, readonly = true, section = versionSection),
                    Field("latestVersion", latest, Formats.TEXT, readonly = true, section = versionSection),
                    Field(
                        "kind", words.t("Вид", "Kind"), Formats.ENUM, readonly = true, section = versionSection,
                        titles = mapOf("system" to systemWord, "installed" to words.t("Установленная", "Installed")),
                    ),
                    Field("enable", words.t("Включена", "Enabled"), Formats.BOOLEAN, readonly = true, section = versionSection),
                    Field("loaded", words.t("Загружена на сервере", "Loaded on the server"), Formats.BOOLEAN, readonly = true, section = versionSection),
                    Field("migrationsText", migrations, Formats.TEXT, readonly = true, section = readiness),
                    Field("settingsText", words.t("Настройки", "Settings"), Formats.TEXT, readonly = true, section = readiness),
                    Field("author", author, Formats.TEXT, readonly = true, section = aboutSection),
                    Field("license", words.t("Лицензия", "Licence"), Formats.TEXT, readonly = true, section = aboutSection),
                    Field("keywords", words.t("Ключевые слова", "Keywords"), Formats.TEXT, readonly = true, section = aboutSection),
                    Field("dependencies", words.t("Зависит от", "Depends on"), Formats.TEXT, readonly = true, section = relations),
                    Field("dependents", words.t("От неё зависят", "Depended on by"), Formats.TEXT, readonly = true, section = relations),
                    Field("removeText", words.t("Удаление", "Removal"), Formats.TEXT, readonly = true, section = relations),
                    Field("hookName", words.t("Имя хука", "Hook name"), Formats.TEXT, readonly = true, section = details),
                    Field("directoryName", words.t("Каталог", "Directory"), Formats.TEXT, readonly = true, section = details),
                    Field("repository", words.t("Откуда установлена", "Installed from"), Formats.TEXT, readonly = true, section = details),
                ),
            ),
            "catalogItem" to Model(
                listOf(
                    Field("name", words.t("Название", "Name"), Formats.TEXT, readonly = true),
                    Field("appId", words.t("Идентификатор", "Identifier"), Formats.TEXT, readonly = true),
                    Field("stateText", state, Formats.TEXT, readonly = true),
                    Field("latestVersion", latest, Formats.TEXT, readonly = true),
                    Field("author", author, Formats.TEXT, readonly = true),
                    Field("tags", words.t("Метки", "Tags"), Formats.TEXT, readonly = true),
                    Field("teaser", words.t("Коротко", "In short"), Formats.TEXT, readonly = true),
                    Field("description", description, Formats.TEXT, readonly = true),
                ),
            ),
        ),
        methods = listOf(
            DeclaredMethod(
                name = "system.home",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Этот сервер", "This server"),
                model = "serverCard",
                input = objectSchema(emptyList(), emptyMap()),
                ui = Ui(view = Views.DETAIL, item = ItemUi(title = "title", subtitle = "subtitle")),
            ),
            DeclaredMethod(
                name = "system.restart",
                kind = DeclaredMethod.KIND_MUTATION,
                title = words.t("Перезапуск сервера", "Restart the server"),
                input = restartInput(words),
                ui = Ui(
                    view = Views.ACTION,
                    on = "serverCard",
                    placement = listOf("detail"),
                    control = Control(type = Controls.BUTTONS, param = "when", tones = mapOf("now" to Tones.DANGER)),
                    confirm = words.restartConfirm,
                ),
            ),
            DeclaredMethod(
                name = "system.modules",
                kind = DeclaredMethod.KIND_QUERY,
                title = programs,
                model = "module",
                input = objectSchema(
                    emptyList(),
                    mapOf(
                        "q" to stringProperty(100),
                        "kind" to enumProperty(
                            listOf("all", "installed", "system", "disabled"), "all",
                            mapOf(
                                "all" to words.t("Все", "All"),
                                "installed" to words.t("Свои", "Own"),
                                "system" to words.t("Системные", "System"),
                                "disabled" to words.t("Выключенные", "Switched off"),
                            ),
                        ),
                        "cursor" to stringProperty(200),
                    ),
                ),
                ui = Ui(
                    view = Views.LIST,
                    item = ItemUi(title = "name", subtitle = "appId", value = "version", badge = "stateText"),
                    search = "q",
                    chips = "kind",
                    open = "system.module",
                    empty = words.t("Программ нет", "No programs"),
                ),
            ),
            DeclaredMethod(
                name = "system.module",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Программа", "Program"),
                model = "module",
                input = idInput(),
                ui = Ui(view = Views.DETAIL, item = ItemUi(title = "name", subtitle = "appId", badge = "stateText")),
            ),
            DeclaredMethod(
                name = "system.catalog",
                kind = DeclaredMethod.KIND_QUERY,
                title = marketplace,
                model = "catalogItem",
                input = objectSchema(
                    emptyList(),
                    mapOf(
                        "q" to stringProperty(100),
                        "appId" to stringProperty(100, minLength = 1),
                        "tags" to stringProperty(200),
                        "cursor" to stringProperty(200),
                    ),
                ),
                ui = Ui(
                    view = Views.LIST,
                    item = ItemUi(title = "name", subtitle = "teaser", value = "latestVersion", badge = "stateText"),
                    search = "q",
                    open = "system.catalogItem",
                    empty = words.t("В маркетплейсе нет программ для установки", "The marketplace has no programs to install"),
                ),
            ),
            DeclaredMethod(
                name = "system.catalogItem",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Программа в маркетплейсе", "Program in the marketplace"),
                model = "catalogItem",
                input = idInput(),
                ui = Ui(view = Views.DETAIL, item = ItemUi(title = "name", subtitle = "appId", badge = "stateText")),
            ),
            job("system.install", words.t("Установить", "Install"), "catalogItem"),
            job("system.update", words.t("Обновить", "Update"), "module"),
            job(
                "system.remove", words.t("Удалить", "Remove"), "module",
                confirm = words.t("Удалить программу? Её файлы и настройки будут удалены.", "Remove this program? Its files and settings will be deleted."),
            ),
            DeclaredMethod(
                name = "system.job",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Ход задания", "Job progress"),
                input = objectSchema(listOf("id"), mapOf("id" to stringProperty(100))),
            ),
        ),
    )
}

/** Asked before a restart, and by the screen of a job that ended with the server waiting for one. */
internal val DemoWords.restartConfirm: String
    get() = t(
        "Перезапустить сервер сейчас? Приложение на несколько секунд перестанет отвечать, а заказы, которые оформляются в эту минуту, могут не пройти.",
        "Restart the server now? The app will stop answering for a few seconds, and orders being placed this minute may fail.",
    )
