package cx.m42.restoapp.demo

import cx.m42.restoapp.protocol.MobileApiException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What «Каналы продаж», «Настройки» and «Система» answer on the demo server: the handlers of the hook's
 * `apps/channels.ts`, `apps/settings.ts` and `apps/system.ts` over the demo's own state.
 *
 * The three lean on each other as on a real server. A channel works when its provider is installed and the required
 * settings of the provider are filled in, so the way out of «Требует настройки» really goes through «Настройки», and
 * «Установить провайдера» really installs one.
 *
 * Two things differ, because the demo has neither a browser page nor a process to restart. «Настроить» of a channel
 * leads into «Настройки» on the rows of its provider, not to the provider's page of the admin panel (`settingsUrl`);
 * and a restart is a few seconds of «Сервер перезапускается», with nothing going down.
 */
internal class DemoSystem(
    private val clock: () -> Long,
    private val serverName: String,
    private val save: suspend (DemoState) -> Unit,
) {
    /** Jobs live in the memory of the process, as `helpers/moduleJobs.js` keeps them: a restart forgets them. */
    private val jobs = mutableMapOf<String, DemoJob>()
    private var nextJob = 1

    private class DemoJob(
        val id: String,
        val kind: String,
        val appId: String,
        val startedAt: Long,
        val failsWith: String? = null,
        var applied: Boolean = false,
    ) {
        val steps: List<String>
            get() = if (kind == "remove") listOf("start", "remove") else listOf("start", "license", "download", "check", "dependencies", "load")

        /** How far a job goes before it fails: the check is what refuses an incompatible program. */
        private val stepsToRun: Int get() = if (failsWith != null) steps.indexOf("check") + 1 else steps.size

        private fun passed(now: Long) = ((now - startedAt) / STEP_MS).toInt()

        fun running(now: Long) = passed(now) < stepsToRun

        fun step(now: Long) = steps[passed(now).coerceAtMost(stepsToRun - 1)]

        fun log(now: Long) = steps.take((passed(now) + 1).coerceAtMost(stepsToRun))
    }

    /**
     * What the time has done since the last call: a job that came to its end changes the server, and a restart that
     * came to its end is over. It runs before every call, so a job the person walked away from still finishes.
     */
    suspend fun settled(state: DemoState): DemoState {
        val now = clock()
        var next = state
        for (job in jobs.values) {
            if (job.applied || job.running(now)) continue
            job.applied = true
            if (job.failsWith == null) next = applied(job, next)
        }
        val ordered = next.restartOrderedAt
        if (ordered != null && now - ordered >= RESTART_MS) {
            jobs.clear()
            next = next.copy(restartOrderedAt = null, restartScheduled = false, boot = next.boot + 1, changedSinceBoot = emptyList())
        }
        if (next != state) save(next)
        return next
    }

    private fun applied(job: DemoJob, state: DemoState): DemoState = when (job.kind) {
        "install" -> DemoModules.marketOf(job.appId)?.let { item ->
            val module = DemoModule(
                appId = item.appId,
                name = item.name,
                version = item.latestVersion,
                descriptionRu = item.teaser.first,
                descriptionEn = item.teaser.second,
                author = item.author,
                keywords = item.tags,
                dependencies = listOf("core"),
            )
            // The module declares itself alive on its start, and its channel appears switched off.
            val fresh = DemoChannelTypes.all
                .filter { type -> type.provider == item.appId && state.channels.none { it.type == type.id } }
                .map { type ->
                    DemoChannel("ch-${type.id}", type.id, type.title.first, type.title.second, type.id, enabled = false, changedAt = clock())
                }
            state.copy(modules = state.modules + module, channels = state.channels + fresh, restartScheduled = true, changedSinceBoot = state.changedSinceBoot + "${item.name} ${item.latestVersion}")
        } ?: state
        "update" -> state.copy(
            modules = state.modules.map { module ->
                if (module.appId == job.appId) module.copy(version = module.latestVersion ?: module.version, latestVersion = null) else module
            },
            restartScheduled = true,
            changedSinceBoot = state.changedSinceBoot + state.modules.filter { it.appId == job.appId }.map { "${it.name} ${it.latestVersion ?: it.version}" },
        )
        else -> state.copy(
            modules = state.modules.filterNot { it.appId == job.appId },
            settingValues = state.settingValues.filterKeys { key -> DemoSettings.all.firstOrNull { it.key == key }?.module != job.appId },
            restartScheduled = true,
            changedSinceBoot = state.changedSinceBoot + state.modules.filter { it.appId == job.appId }.map { "${it.name} (удалён)" },
        )
    }

    // ---- what the three apps share ----

    private fun moduleOf(state: DemoState, appId: String?) = state.modules.firstOrNull { it.appId == appId }

    /** The required settings of a module with neither a value nor a default: what blocks it from working. */
    private fun unfilled(state: DemoState, appId: String?): List<DemoSetting> =
        DemoSettings.all.filter { it.module == appId && it.required && it.env == null && it.default == null && !hasValue(state, it) }

    private fun hasValue(state: DemoState, setting: DemoSetting): Boolean =
        setting.env != null || state.settingValues[setting.key].let { it != null && it !is JsonNull }

    private fun visibleSettings(state: DemoState) =
        DemoSettings.all.filter { it.module == null || moduleOf(state, it.module) != null }

    private fun link(title: String, method: String, app: String? = null, params: JsonObject? = null) = buildJsonObject {
        put("title", title)
        put(
            "open",
            buildJsonObject {
                app?.let { put("app", it) }
                put("method", method)
                params?.let { put("params", it) }
            },
        )
    }

    private fun offsetOf(params: JsonObject) = params.stringOrNull("cursor", 200)?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun JsonObject.search() = stringOrNull("q", 100)?.trim()?.lowercase()?.ifEmpty { null }

    // ---- «Каналы продаж» ----

    /** The channel as `describeChannel` of core counts it. */
    private inner class Channel(val row: DemoChannel, val state: DemoState, val words: DemoWords) {
        val type = DemoChannelTypes.of(row.type)
        val providerName = type?.provider
        val provider = moduleOf(state, providerName)
        val installed = providerName == null || provider != null
        val alive = installed && provider?.enable != false
        val missing = if (alive) unfilled(state, providerName) else emptyList()
        val status = when {
            !alive -> "error"
            missing.isNotEmpty() -> "needs_setup"
            else -> "ready"
        }
        val message: String? = when {
            !installed -> words.t("Не работает: провайдер не установлен", "Not working: provider is not installed")
            !alive -> words.t("Провайдер не загрузился", "Provider did not load")
            missing.isNotEmpty() -> words.t("Не заполнено", "Not filled in") + ": " + missing.joinToString { it.key }
            else -> null
        }
        val active = row.enabled && status == "ready"

        fun record(card: Boolean): JsonObject = buildJsonObject {
            val typeTitle = type?.title?.let { words.t(it.first, it.second) } ?: row.type
            put("id", row.id)
            put("key", row.key)
            put("title", words.t(row.titleRu, row.titleEn))
            put("subtitle", "${row.key} · $typeTitle")
            put("type", row.type)
            put("typeTitle", typeTitle)
            put("category", type?.category.orEmpty())
            put("status", status)
            message?.let { put("statusMessage", it) }
            put(
                "providerText",
                when {
                    !installed -> "${words.t("Не работает: провайдер не установлен", "Not working: provider is not installed")} ($providerName)"
                    !alive -> words.t("Провайдер не загрузился: перезапустите сервер, чтобы закончить установку", "Provider did not load: restart the server to finish installing it")
                    providerName != null -> "$providerName · ${words.t("Модуль установлен", "Provider installed")}"
                    else -> words.t("Модуль установлен", "Provider installed")
                },
            )
            if (!installed) put("providerHint", words.t("Установите модуль-провайдер, чтобы канал заработал.", "Install the provider module to make this channel work."))
            // A channel whose provider is not in this process is shown off, whatever is stored.
            put("enabledText", row.enabled && alive)
            put("conceptsText", if (row.concepts.isEmpty()) words.t("Все концепции", "All concepts") else row.concepts.joinToString(" · "))
            if (row.countries.isNotEmpty()) put("countriesText", row.countries.joinToString(", "))
            if (row.platforms.isNotEmpty()) put("platformsText", row.platforms.joinToString(", "))
            row.url?.let { put("url", it) }
            type?.capabilities?.takeIf { it.isNotEmpty() }?.let { put("capabilitiesText", it.joinToString(" · ")) }
            put("updatedAt", row.changedAt ?: (clock() - row.agoMinutes * 60_000L))
            put("enabled", row.enabled)
            put("providerInstalled", installed)
            put("providerAlive", alive)
            put("canDelete", !installed)
            putJsonArray("_actions") {
                if (alive) add(buildJsonObject { put("method", "channels.toggle") })
                if (!installed) add(buildJsonObject { put("method", "channels.delete") })
            }
            if (card) {
                putJsonArray("_links") {
                    if (providerName != null && alive) {
                        add(
                            link(
                                if (status == "ready") words.t("Настройки", "Settings") else words.t("Настроить", "Set up"),
                                "settings.list", app = SETTINGS, params = buildJsonObject { put("q", providerName) },
                            ),
                        )
                    }
                    if (!installed) {
                        add(
                            link(
                                words.t("Установить провайдера", "Install provider"),
                                "system.catalog", app = SYSTEM, params = buildJsonObject { put("appId", providerName) },
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun typeRecord(type: DemoChannelType, state: DemoState, words: DemoWords, card: Boolean): JsonObject = buildJsonObject {
        val provider = type.provider?.let { moduleOf(state, it) }
        val installed = type.provider == null || provider != null
        val alive = installed && provider?.enable != false
        put("id", type.id)
        put("title", words.t(type.title.first, type.title.second))
        if (type.comingSoon) {
            put("availability", "coming_soon")
        } else {
            put(
                "stateText",
                when {
                    !installed -> words.t("Модуль не установлен", "Provider not installed")
                    !alive -> words.t("Провайдер не загрузился: перезапустите сервер, чтобы закончить установку", "Provider did not load: restart the server to finish installing it")
                    else -> words.t("Модуль установлен", "Provider installed")
                },
            )
        }
        put("category", type.category)
        type.capabilities.takeIf { it.isNotEmpty() }?.let { put("capabilitiesText", it.joinToString(" · ")) }
        type.countries.takeIf { it.isNotEmpty() }?.let { put("countriesText", it.joinToString(", ")) }
        type.provider?.let { put("providerText", it) }
        putJsonArray("_actions") {}
        if (card) {
            putJsonArray("_links") {
                when {
                    type.comingSoon -> if (type.id != "custom") {
                        add(link(words.t("Искать в маркетплейсе", "Search the marketplace"), "system.catalog", app = SYSTEM, params = buildJsonObject { put("tags", type.tag) }))
                    }
                    !installed -> add(
                        link(words.t("Установить из маркетплейса", "Install from marketplace"), "system.catalog", app = SYSTEM, params = buildJsonObject { put("appId", type.provider) }),
                    )
                    alive && type.provider != null -> add(
                        link(words.t("Настройки", "Settings"), "settings.list", app = SETTINGS, params = buildJsonObject { put("q", type.provider) }),
                    )
                }
            }
        }
    }

    suspend fun channels(method: String, params: JsonObject, current: DemoState, words: DemoWords): JsonElement {
        fun channel(id: String) = current.channels.firstOrNull { it.id == id }?.let { Channel(it, current, words) }
            ?: fail(MobileApiException.NOT_FOUND, "No such sales channel")

        return when (method) {
            "channels.list" -> {
                val q = params.search()
                val state = params.enumOf("state", listOf("all", "active", "off", "needs_setup", "error"), "all")
                val offset = offsetOf(params)
                val all = current.channels.map { Channel(it, current, words) }.filter { channel ->
                    q == null || listOf(words.t(channel.row.titleRu, channel.row.titleEn), channel.row.key, channel.row.type).any { it.lowercase().contains(q) }
                }
                fun matches(channel: Channel, name: String) = when (name) {
                    "active" -> channel.active
                    "off" -> !channel.row.enabled
                    "needs_setup" -> channel.status == "needs_setup"
                    "error" -> channel.status == "error"
                    else -> true
                }
                val counts = listOf("all", "active", "off", "needs_setup", "error").associateWith { name -> all.count { matches(it, name) } }
                val rows = all.filter { matches(it, state) }.map { it.record(card = false) }
                // The way to the catalog of types is the last row of the whole list; under a filter or a search it
                // would answer a question nobody asked, and would hide the empty state.
                val withTypes = if (q != null || state != "all") rows else rows + buildJsonObject {
                    put("id", "channel-types")
                    put("title", words.t("Типы каналов", "Channel types"))
                    put(
                        "subtitle",
                        if (rows.isNotEmpty()) words.t("Что можно подключить", "What can be connected")
                        else words.t("Каналы появляются, когда вы ставите их модуль-провайдер из маркетплейса", "Channels appear when you install their provider module from the marketplace"),
                    )
                    put("_open", buildJsonObject { put("method", "channels.types") })
                    putJsonArray("_actions") {}
                }
                page(withTypes, offset, counts)
            }
            "channels.get" -> buildJsonObject { put("item", channel(params.string("id")).record(card = true)) }
            "channels.toggle" -> {
                val target = channel(params.string("id"))
                val enabled = params.boolean("enabled")
                if (enabled && !target.alive) fail(MobileApiException.CONFLICT, words.t("Сначала установите модуль-провайдер", "Install the provider first"))
                if (enabled && target.status != "ready") {
                    fail(MobileApiException.CONFLICT, listOfNotNull(words.t("Сначала настройте канал", "Finish setup first"), target.message).joinToString(" — "))
                }
                val updated = target.row.copy(enabled = enabled, changedAt = clock())
                val next = current.copy(channels = current.channels.map { if (it.id == updated.id) updated else it })
                save(next)
                buildJsonObject { put("item", Channel(updated, next, words).record(card = true)) }
            }
            "channels.delete" -> {
                val target = channel(params.string("id"))
                // A provider's channel is only switched off while its module is installed (core's rule).
                if (target.installed) fail(MobileApiException.CONFLICT, words.t("Канал провайдера можно только выключить", "A provider's channel can only be switched off"))
                save(current.copy(channels = current.channels.filterNot { it.id == target.row.id }))
                buildJsonObject {
                    put("ok", true)
                    put("id", target.row.id)
                    put("key", target.row.key)
                }
            }
            "channels.types" -> {
                val kind = params.enumOf("kind", listOf("recommended", "all"), "recommended")
                val offset = offsetOf(params)
                val here = current.channels.map { it.type }.toSet()
                val recommended = DemoChannelTypes.all.filter { it.recommended && it.id !in here }
                val shown = if (kind == "all") DemoChannelTypes.all else recommended
                val rows = shown.map { typeRecord(it, current, words, card = false) } + buildJsonObject {
                    put("id", "marketplace")
                    put("title", words.t("Все каналы продаж в маркетплейсе", "All sales channels in marketplace"))
                    put("_open", buildJsonObject { put("app", SYSTEM); put("method", "system.catalog"); put("params", buildJsonObject { put("tags", "sales-channel") }) })
                    putJsonArray("_actions") {}
                }
                page(rows, offset, mapOf("recommended" to recommended.size, "all" to DemoChannelTypes.all.size))
            }
            "channels.type" -> {
                val type = DemoChannelTypes.of(params.string("id")) ?: fail(MobileApiException.NOT_FOUND, "No such channel type")
                buildJsonObject { put("item", typeRecord(type, current, words, card = true)) }
            }
            else -> fail(MobileApiException.FORBIDDEN, "Method is not available")
        }
    }

    // ---- «Настройки» ----

    /** The value this server actually reads: a setting never set works off its default. */
    private fun storedOf(state: DemoState, setting: DemoSetting): JsonElement? =
        setting.env ?: state.settingValues[setting.key]?.takeIf { it !is JsonNull }

    private fun effectiveOf(state: DemoState, setting: DemoSetting): JsonElement? = storedOf(state, setting) ?: setting.default

    private fun isChanged(state: DemoState, setting: DemoSetting): Boolean {
        if (setting.secret) return hasValue(state, setting)
        if (!hasValue(state, setting)) return false
        return setting.default == null || storedOf(state, setting) != setting.default
    }

    private fun valueText(value: JsonElement?, secret: Boolean, hasValue: Boolean, words: DemoWords): String {
        val empty = "—"
        if (secret) return if (hasValue) "••••••••" else empty
        return when (value) {
            null, JsonNull -> empty
            is JsonPrimitive -> when {
                value.content.isEmpty() -> empty
                value.booleanOrNull != null && !value.isString -> if (value.booleanOrNull == true) words.t("Да", "Yes") else words.t("Нет", "No")
                else -> value.content
            }
            is JsonArray -> {
                val scalars = value.all { it is JsonPrimitive }
                val written = if (scalars) value.joinToString(", ") { it.jsonPrimitive.content } else ""
                when {
                    value.isEmpty() -> empty
                    written.isNotEmpty() && written.length <= VALUE_TEXT_LIMIT -> written
                    else -> "${words.t("элементов", "items")}: ${value.size}"
                }
            }
            is JsonObject -> "${words.t("полей", "fields")}: ${value.size}"
        }
    }

    private fun stateText(state: DemoState, setting: DemoSetting, words: DemoWords): String = listOfNotNull(
        words.t("Только чтение", "Read only").takeIf { setting.readOnly },
        words.t("Значение по умолчанию", "The declared default").takeIf { !setting.secret && !hasValue(state, setting) && setting.default != null },
        words.t("Из окружения", "From the environment").takeIf { setting.env != null },
        words.t("Секрет", "Secret").takeIf { setting.secret },
        words.t("Нужен перезапуск", "Restart required").takeIf { setting.restart },
        words.t("Обязательная", "Required").takeIf { setting.required },
        setting.module,
    ).joinToString(" · ")

    private fun settingRecord(state: DemoState, setting: DemoSetting, words: DemoWords, card: Boolean): JsonObject = buildJsonObject {
        val has = hasValue(state, setting)
        put("id", setting.key)
        put("key", setting.key)
        put("name", words.t(setting.name.first, setting.name.second))
        put("type", setting.type)
        put("valueText", valueText(effectiveOf(state, setting), setting.secret, has, words))
        put("defaultText", if (setting.secret) "" else valueText(setting.default, secret = false, hasValue = false, words))
        put("state", stateText(state, setting, words))
        setting.module?.let { put("module", it) }
        setting.description?.let { put("description", words.t(it.first, it.second)) }
        if (card) {
            put("value", if (setting.secret) JsonNull else storedOf(state, setting) ?: JsonNull)
            put("defaultValue", if (setting.secret) JsonNull else setting.default ?: JsonNull)
            put("jsonSchema", setting.schema ?: JsonNull)
            setting.patternHint?.let { put("patternHint", words.t(it.first, it.second)) }
            put("secret", setting.secret)
            put("hasValue", has)
            put("readOnly", setting.readOnly)
            put("isRequired", setting.required)
            put("restartRequired", setting.restart)
            put("envOverride", if (setting.env != null) buildJsonObject { put("valid", true) } else JsonNull)
            put("boot", "demo-${state.boot}")
        }
        // A setting the application owns, or one the environment pins, has no action at all.
        putJsonArray("_actions") {
            if (!setting.readOnly && setting.env == null) add(buildJsonObject { put("method", "settings.update") })
        }
    }

    /** What is wrong with a value by the schema of its setting: the sentence the editor shows, or null. */
    private fun problemOf(setting: DemoSetting, value: JsonElement, words: DemoWords): String? {
        if (value is JsonNull) {
            return words.t("Обязательная настройка не может быть пустой", "A required setting cannot be empty").takeIf { setting.required && setting.default == null }
        }
        val schema = setting.schema
            ?: return when (setting.type) {
                "boolean" -> words.t("Нужно «да» или «нет»", "Must be yes or no").takeIf { (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null }
                "number" -> words.t("Нужно число", "Must be a number").takeIf { (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull == null }
                "string" -> words.t("Нужна строка", "Must be a string").takeIf { (value as? JsonPrimitive)?.isString != true }
                else -> null
            }
        return problemBySchema(schema, value, words)
    }

    private fun problemBySchema(schema: JsonObject, value: JsonElement, words: DemoWords): String? {
        val allowed = schema["enum"] as? JsonArray
        if (allowed != null && value !in allowed) return words.t("Такого значения нет в списке", "This value is not on the list")
        return when ((schema["type"] as? JsonPrimitive)?.contentOrNull) {
            "string" -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return words.t("Нужна строка", "Must be a string")
                val min = (schema["minLength"] as? JsonPrimitive)?.intOrNull
                val max = (schema["maxLength"] as? JsonPrimitive)?.intOrNull
                val pattern = (schema["pattern"] as? JsonPrimitive)?.contentOrNull
                when {
                    min != null && text.length < min -> words.t("Не короче $min символов", "At least $min characters")
                    max != null && text.length > max -> words.t("Не длиннее $max символов", "At most $max characters")
                    pattern != null && !Regex(pattern).containsMatchIn(text) -> words.t("Значение не подходит под формат", "The value does not match the format")
                    else -> null
                }
            }
            "integer", "number" -> {
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return words.t("Нужно число", "Must be a number")
                val min = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull
                val max = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull
                when {
                    schema["type"]?.jsonPrimitive?.content == "integer" && number % 1.0 != 0.0 -> words.t("Нужно целое число", "Must be a whole number")
                    min != null && number < min -> words.t("Не меньше ${min.toInt()}", "At least ${min.toInt()}")
                    max != null && number > max -> words.t("Не больше ${max.toInt()}", "At most ${max.toInt()}")
                    else -> null
                }
            }
            "array" -> {
                val items = value as? JsonArray ?: return words.t("Нужен список", "Must be a list")
                val each = schema["items"] as? JsonObject ?: return null
                items.firstNotNullOfOrNull { problemBySchema(each, it, words) }
            }
            "object" -> if (value is JsonObject) null else words.t("Нужен объект", "Must be an object")
            else -> null
        }
    }

    /** One write, with the refusals of core: `reason` to `message`. Null is success. */
    private fun refusalOf(setting: DemoSetting, value: JsonElement, words: DemoWords): Pair<String, String>? = when {
        setting.readOnly -> "read_only" to words.t("Этой настройкой управляет приложение", "The application owns this setting")
        setting.env != null -> "env" to words.t("Значение задано в окружении сервера", "The value is set in the server's environment")
        else -> problemOf(setting, value, words)?.let { "invalid" to it }
    }

    private fun DemoState.withValue(setting: DemoSetting, value: JsonElement) = copy(
        settingValues = if (value is JsonNull) settingValues - setting.key else settingValues + (setting.key to value),
    )

    suspend fun settings(method: String, params: JsonObject, current: DemoState, words: DemoWords): JsonElement {
        val visible = visibleSettings(current)
        fun setting(key: String) = visible.firstOrNull { it.key == key }

        return when (method) {
            "settings.list" -> {
                val q = params.search()
                val kind = params.enumOf("kind", listOf("all", "changed", "env", "restart"), "all")
                val offset = offsetOf(params)
                val found = visible.filter { row ->
                    q == null || listOf(row.key, words.t(row.name.first, row.name.second), row.module.orEmpty()).any { it.lowercase().contains(q) }
                }
                fun ofKind(row: DemoSetting, name: String) = when (name) {
                    "changed" -> isChanged(current, row)
                    "env" -> row.env != null
                    "restart" -> row.restart
                    else -> true
                }
                val counts = listOf("all", "changed", "env", "restart").associateWith { name -> found.count { ofKind(it, name) } }
                page(found.filter { ofKind(it, kind) }.map { settingRecord(current, it, words, card = false) }, offset, counts)
            }
            "settings.get" -> {
                val row = setting(params.string("id")) ?: fail(MobileApiException.NOT_FOUND, "No such setting")
                buildJsonObject { put("item", settingRecord(current, row, words, card = true)) }
            }
            "settings.update" -> {
                val row = setting(params.string("id")) ?: fail(MobileApiException.NOT_FOUND, "No such setting")
                val value = params["value"] ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: value")
                refusalOf(row, value, words)?.let { (reason, message) ->
                    fail(if (reason == "invalid") MobileApiException.INVALID else MobileApiException.FORBIDDEN, message)
                }
                val next = current.withValue(row, value)
                save(next)
                buildJsonObject { put("item", settingRecord(next, row, words, card = true)) }
            }
            "settings.dump" -> {
                // A secret is write-only, so it is left out of the file entirely.
                val rows = visible.filter { !it.secret }
                buildJsonObject {
                    put("exportedAt", "2026-11-05T06:00:00.000Z")
                    put("count", rows.size)
                    putJsonArray("settings") {
                        rows.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("key", row.key)
                                    put("name", row.name.second)
                                    put("type", row.type)
                                    put("value", storedOf(current, row) ?: JsonNull)
                                    put("defaultValue", row.default ?: JsonNull)
                                    put("module", row.module?.let(::JsonPrimitive) ?: JsonNull)
                                },
                            )
                        }
                    }
                }
            }
            "settings.transfer" -> {
                val entries = (params["settings"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: settings")
                if (params.booleanOrNull("preview") == true) {
                    buildJsonObject { put("diff", buildJsonArray { entries.forEach { add(diffRow(current, it, visible, words)) } }) }
                } else {
                    val keys = (params["keys"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    if (keys.isEmpty()) fail(MobileApiException.INVALID, "No keys selected for import")
                    var next = current
                    val results = buildJsonArray {
                        entries.filter { it.string("key") in keys }.forEach { entry ->
                            val key = entry.string("key")
                            val row = visible.firstOrNull { it.key == key }
                            val value = entry["value"] ?: JsonNull
                            val refusal = if (row == null) "invalid" to words.t("Такой настройки нет", "No such setting") else refusalOf(row, value, words)
                            add(
                                buildJsonObject {
                                    put("key", key)
                                    if (refusal == null) {
                                        put("status", "applied")
                                        next = next.withValue(row!!, value)
                                    } else {
                                        put("status", if (refusal.first == "invalid") "error" else "skipped")
                                        put("error", refusal.second)
                                    }
                                },
                            )
                        }
                    }
                    save(next)
                    buildJsonObject { put("results", results) }
                }
            }
            else -> fail(MobileApiException.FORBIDDEN, "Method is not available")
        }
    }

    /** What an import would change, by the rules of core's `diffImport`. */
    private fun diffRow(state: DemoState, entry: JsonObject, visible: List<DemoSetting>, words: DemoWords): JsonObject = buildJsonObject {
        val key = entry.string("key")
        val incoming = entry["value"] ?: JsonNull
        val row = visible.firstOrNull { it.key == key }
        put("key", key)
        put("importValue", incoming)
        if (row == null) {
            put("status", "not_found")
            return@buildJsonObject
        }
        val currentValue = effectiveOf(state, row)
        // A secret cannot be compared, since its value is never sent: it is always offered as a change.
        val readAs = incoming.takeIf { it !is JsonNull } ?: row.default
        val changed = row.secret || (currentValue ?: JsonNull) != (readAs ?: JsonNull)
        put("status", if (changed) "changed" else "unchanged")
        put("currentValue", if (row.secret) JsonNull else currentValue ?: JsonNull)
        put("readOnly", row.readOnly || row.env != null)
        put("envOverride", row.env != null)
        put("secret", row.secret)
        put("type", row.type)
        put("name", words.t(row.name.first, row.name.second))
    }

    // ---- «Система» ----

    private fun stateOf(module: DemoModule): String = when {
        module.migrationsProcessed < module.migrationsFiles -> "migrations"
        !module.enable -> "disabled"
        module.latestVersion != null -> "update"
        module.system -> "system"
        else -> "ok"
    }

    private fun stateWord(state: String, module: DemoModule, words: DemoWords): String = when (state) {
        "migrations" -> words.t("Миграции не прошли", "Migrations did not pass")
        "disabled" -> words.t("Выключен", "Switched off")
        "update" -> "${words.t("Обновление", "Update")} ${module.latestVersion}"
        "system" -> words.t("Системный", "System")
        else -> words.t("Работает", "Working")
    }

    private fun serverCard(state: DemoState, words: DemoWords): JsonObject = buildJsonObject {
        val restarting = state.restartOrderedAt != null
        val pending = state.modules.count { it.migrationsProcessed < it.migrationsFiles }
        val version = DemoBackend.VERSION
        val zone = (effectiveOf(state, DemoSettings.all.first { it.key == "TZ" }) as? JsonPrimitive)?.content ?: "Europe/Moscow"
        put("id", "server")
        put("title", serverName)
        put("subtitle", "$version · $zone")
        put("version", version)
        // As the server: when the process started, and how long that is in words — «3 дн 4 ч».
        val started = clock() - ((3 * 24 + 4) * 60 + 1) * 60_000L
        put("uptime", words.t("3 дн 4 ч", "3 d 4 h"))
        put("startedAt", started)
        put("timezone", zone)
        put("currency", "RUB")
        put("systemCount", state.modules.count { it.system })
        put("installedCount", state.modules.count { !it.system })
        put("disabledCount", state.modules.count { !it.enable })
        put("updatesCount", state.modules.count { it.latestVersion != null })
        put("migrationsPending", pending)
        put("restartScheduled", state.restartScheduled)
        put("restarting", restarting)
        put("wizardActive", false)
        put("licensed", true)
        when {
            restarting -> put("restartText", words.t("Сервер перезапускается", "The server is restarting"))
            state.restartScheduled -> put("restartText", words.restartScheduled)
        }
        if (pending > 0) put("migrationsText", "${words.t("Миграции не пройдены", "Migrations not processed")} · $pending")
        // P12, as the server: modules changed since the start run only after a restart.
        when {
            restarting -> put("notice", buildJsonObject { put("tone", "info"); put("title", words.t("Сервер перезапускается", "The server is restarting")) })
            state.changedSinceBoot.isNotEmpty() -> put("notice", buildJsonObject {
                put("tone", "warning")
                put("title", words.t("Нужен перезапуск сервера", "The server needs a restart"))
                put(
                    "text",
                    "${words.t("Изменены модули", "Modules changed")}: ${state.changedSinceBoot.joinToString(", ")}. " +
                        words.t("Сервер работает со старым кодом: новые страницы и функции этих модулей появятся после перезапуска.", "The server runs the old code: the new pages and functions of these modules appear after a restart.") + " " +
                        if (state.restartScheduled) words.restartScheduled else words.t("Перезапустите сейчас или запланируйте на полночь.", "Restart now or plan it for midnight."),
                )
                if (!state.restartScheduled) putJsonArray("actions") {
                    add(buildJsonObject {
                        put("title", words.t("Перезапустить в полночь", "Restart at midnight"))
                        put("method", "system.restart")
                        putJsonObject("input") { put("when", "midnight") }
                    })
                }
            })
        }
        // A restart that is planned is not planned again, and one that is going cannot be ordered twice.
        putJsonArray("_actions") {
            when {
                restarting -> {}
                state.restartScheduled -> add(
                    buildJsonObject {
                        put("method", "system.restart")
                        put("input", restartInput(words, listOf("now")))
                    },
                )
                else -> add(buildJsonObject { put("method", "system.restart") })
            }
        }
        putJsonArray("_links") {
            add(link("${words.t("Модули", "Modules")} · ${state.modules.size}", "system.modules"))
            add(link(words.t("Маркетплейс", "Marketplace"), "system.catalog"))
            add(link(words.t("Настройки", "Settings"), "settings.list", app = SETTINGS))
            add(link(words.t("Каналы продаж", "Sales channels"), "channels.list", app = CHANNELS))
        }
    }

    private fun moduleRecord(state: DemoState, module: DemoModule, words: DemoWords, card: Boolean): JsonObject = buildJsonObject {
        val moduleState = stateOf(module)
        val dependents = state.modules.filter { module.appId in it.dependencies }.map { it.appId }
        val removeBlock = when {
            module.system -> "system"
            module.forbidDelete -> "forbidden"
            dependents.isNotEmpty() -> "dependents"
            else -> null
        }
        put("id", module.appId)
        put("appId", module.appId)
        put("name", module.name)
        put("version", module.version)
        module.latestVersion?.let { put("latestVersion", it) }
        put("kind", if (module.system) "system" else "installed")
        put("enable", module.enable)
        put("loaded", module.enable)
        put("state", moduleState)
        put("stateText", stateWord(moduleState, module, words))
        put("description", words.t(module.descriptionRu, module.descriptionEn))
        if (card) {
            put("hookName", module.appId.replace("-", ""))
            put("directoryName", "modules/${module.appId}")
            if (module.author.isNotEmpty()) put("author", module.author)
            if (module.license.isNotEmpty()) put("license", module.license)
            if (module.keywords.isNotEmpty()) put("keywords", module.keywords)
            if (module.dependencies.isNotEmpty()) put("dependencies", module.dependencies.joinToString(", "))
            if (dependents.isNotEmpty()) put("dependents", dependents.joinToString(", "))
            put("repository", if (module.system) words.t("Поставка сервера", "Shipped with the server") else words.t("Маркетплейс", "Marketplace"))
            put("removeBlock", removeBlock?.let(::JsonPrimitive) ?: JsonNull)
            when {
                module.migrationsProcessed < module.migrationsFiles ->
                    put("migrationsText", "${words.t("Миграции не пройдены", "Migrations not processed")}: ${module.migrationsProcessed} / ${module.migrationsFiles}")
                module.migrationsFiles > 0 ->
                    put("migrationsText", "${words.t("Миграции пройдены", "Migrations processed")}: ${module.migrationsFiles}")
            }
            unfilled(state, module.appId).size.takeIf { it > 0 }?.let {
                put("settingsText", "${words.t("Не заполнены обязательные настройки", "Required settings are not filled in")} · $it")
            }
            when (removeBlock) {
                "system" -> put("removeText", words.t("Системный модуль удалить нельзя", "A system module cannot be removed"))
                "forbidden" -> put("removeText", words.t("Модуль запрещает своё удаление", "The module forbids its own removal"))
                "dependents" -> put("removeText", "${words.t("Удалить нельзя, от него зависят", "Cannot be removed, it is depended on by")}: ${dependents.joinToString(", ")}")
            }
        }
        putJsonArray("_actions") {
            if (moduleState == "update") add(buildJsonObject { put("method", "system.update") })
            if (!module.system && removeBlock == null) add(buildJsonObject { put("method", "system.remove") })
        }
    }

    private fun blockOf(state: DemoState, item: DemoMarketItem): String? =
        if (moduleOf(state, item.appId) != null) "installed" else item.block

    private fun catalogRecord(state: DemoState, item: DemoMarketItem, words: DemoWords): JsonObject = buildJsonObject {
        val block = blockOf(state, item)
        put("id", item.appId)
        put("appId", item.appId)
        put("name", item.name)
        put("teaser", words.t(item.teaser.first, item.teaser.second))
        put("description", words.t(item.description.first, item.description.second))
        put("author", item.author)
        put("tags", item.tags)
        put("latestVersion", item.latestVersion)
        put("block", block?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "stateText",
            when (block) {
                "installed" -> words.t("Установлен", "Installed")
                "license" -> words.t("Недоступен по лицензии", "Unavailable for this license")
                "download" -> words.t("Пока нельзя скачать", "Cannot be downloaded yet")
                else -> words.t("Доступен", "Available")
            },
        )
        putJsonArray("_actions") {
            if (block == null) add(buildJsonObject { put("method", "system.install") })
        }
    }

    /** The record a job's action answers with: the card as the job left it, or the fact that it is gone. */
    private fun recordOf(kind: String, appId: String, state: DemoState, words: DemoWords): JsonObject {
        if (kind == "install") {
            return DemoModules.marketOf(appId)?.let { catalogRecord(state, it, words) } ?: fail(MobileApiException.NOT_FOUND, words.t("Такого модуля в маркетплейсе нет", "The marketplace has no such module"))
        }
        moduleOf(state, appId)?.let { return moduleRecord(state, it, words, card = true) }
        return buildJsonObject {
            put("id", appId)
            put("appId", appId)
            put("name", appId)
            put("state", "removed")
            put("stateText", words.t("Удалён", "Removed"))
            putJsonArray("_actions") {}
        }
    }

    private fun startJob(kind: String, appId: String, state: DemoState, words: DemoWords): DemoJob {
        val now = clock()
        val going = jobs.values.firstOrNull { it.running(now) }
        if (going != null) {
            if (going.appId == appId && going.kind == kind) return going
            fail(MobileApiException.CONFLICT, "${words.t("Сейчас идёт другое задание, дождитесь его конца", "Another job is going on, wait for it to end")}: ${going.appId}")
        }
        val failsWith = if (kind == "install") {
            val item = DemoModules.marketOf(appId) ?: fail(MobileApiException.NOT_FOUND, words.t("Такого модуля в маркетплейсе нет", "The marketplace has no such module"))
            if (moduleOf(state, appId) != null) fail(MobileApiException.CONFLICT, words.t("Этот модуль уже установлен", "This module is already installed"))
            item.needsCore
        } else {
            moduleOf(state, appId) ?: fail(MobileApiException.NOT_FOUND, words.t("Такого модуля на сервере нет", "The server has no such module"))
            null
        }
        return DemoJob("job-${nextJob++}", kind, appId, now, failsWith).also { jobs[it.id] = it }
    }

    private fun jobRecord(job: DemoJob, state: DemoState, words: DemoWords): JsonObject = buildJsonObject {
        val now = clock()
        val running = job.running(now)
        fun stepWord(step: String) = when (step) {
            "license" -> words.t("Проверяем лицензию", "Checking the license")
            "download" -> words.t("Скачиваем из маркетплейса", "Downloading from the marketplace")
            "check" -> words.t("Проверяем совместимость", "Checking compatibility")
            "dependencies" -> words.t("Ставим зависимости", "Installing dependencies")
            "load" -> words.t("Загружаем модуль", "Loading the module")
            "remove" -> words.t("Удаляем файлы", "Deleting the files")
            else -> words.t("Начинаем", "Starting")
        }
        val kindWord = when (job.kind) {
            "install" -> words.t("Установка", "Installing")
            "update" -> words.t("Обновление", "Updating")
            else -> words.t("Удаление", "Removing")
        }
        val failed = !running && job.failsWith != null
        put("id", job.id)
        put("kind", job.kind)
        put("appId", job.appId)
        put("state", if (running) "running" else if (failed) "failed" else "done")
        put("title", "$kindWord: ${job.appId}")
        put("stepText", stepWord(job.step(now)))
        put("log", job.log(now).joinToString("\n") { stepWord(it) })
        if (failed) {
            put(
                "errorText",
                "${words.t("Модуль не подходит этому серверу.", "This module does not suit this server.")} " +
                    "${words.t("Нужен", "Needs")} core ≥ ${job.failsWith}, ${words.t("установлен", "installed")} ${moduleOf(state, "core")?.version}",
            )
        }
        if (!running && !failed) {
            put(
                "doneText",
                when (job.kind) {
                    "install" -> words.t("Модуль установлен", "The module is installed")
                    "update" -> words.t("Модуль обновлён", "The module is updated")
                    else -> words.t("Модуль удалён", "The module is removed")
                },
            )
            if (state.restartScheduled) {
                put("restartText", words.restartScheduled)
                put("restartConfirm", words.restartConfirm)
            }
        }
    }

    suspend fun system(method: String, params: JsonObject, current: DemoState, words: DemoWords): JsonElement = when (method) {
        "system.home" -> buildJsonObject { put("item", serverCard(current, words)) }
        "system.restart" -> {
            val whens = if (current.restartScheduled) listOf("now") else listOf("midnight", "now")
            if (current.restartOrderedAt != null) fail(MobileApiException.CONFLICT, words.t("Сервер перезапускается", "The server is restarting"))
            val next = when (params.enumOf("when", whens, "")) {
                "now" -> current.copy(restartOrderedAt = clock())
                "midnight" -> current.copy(restartScheduled = true)
                else -> fail(MobileApiException.INVALID, "Parameters do not match the method schema: when")
            }
            save(next)
            buildJsonObject { put("item", serverCard(next, words)) }
        }
        "system.modules" -> {
            val q = params.search()
            val kind = params.enumOf("kind", listOf("all", "installed", "system", "disabled"), "all")
            val found = current.modules.filter { module -> q == null || listOf(module.name, module.appId).any { it.lowercase().contains(q) } }
            fun ofKind(module: DemoModule, name: String) = when (name) {
                "installed" -> !module.system
                "system" -> module.system
                "disabled" -> !module.enable
                else -> true
            }
            val counts = listOf("all", "installed", "system", "disabled").associateWith { name -> found.count { ofKind(it, name) } }
            page(found.filter { ofKind(it, kind) }.map { moduleRecord(current, it, words, card = false) }, offsetOf(params), counts)
        }
        "system.module" -> {
            val module = moduleOf(current, params.string("id")) ?: fail(MobileApiException.NOT_FOUND, "No such module")
            buildJsonObject { put("item", moduleRecord(current, module, words, card = true)) }
        }
        "system.catalog" -> {
            val q = params.search()
            val appId = params.stringOrNull("appId", 100)
            val tags = params.stringOrNull("tags", 200)
            // The marketplace without what is installed.
            val found = DemoModules.market.filter { item ->
                moduleOf(current, item.appId) == null &&
                    (appId == null || item.appId == appId) &&
                    (tags == null || tags in item.tags) &&
                    (q == null || listOf(item.name, item.appId, words.t(item.teaser.first, item.teaser.second)).any { it.lowercase().contains(q) })
            }
            page(found.map { catalogRecord(current, it, words) }, offsetOf(params), emptyMap())
        }
        "system.catalogItem" -> {
            val item = DemoModules.marketOf(params.string("id")) ?: fail(MobileApiException.NOT_FOUND, words.t("Такого модуля в маркетплейсе нет", "The marketplace has no such module"))
            buildJsonObject { put("item", catalogRecord(current, item, words)) }
        }
        "system.install", "system.update", "system.remove" -> {
            val kind = method.removePrefix("system.")
            val appId = params.string("appId")
            if (params.stringOrNull("job", 100) != null) {
                buildJsonObject { put("item", recordOf(kind, appId, current, words)) }
            } else {
                val job = startJob(kind, appId, current, words)
                buildJsonObject {
                    put("item", JsonObject(recordOf(kind, appId, current, words) + ("jobId" to JsonPrimitive(job.id))))
                }
            }
        }
        "system.job" -> {
            val job = jobs[params.string("id")]
                ?: fail(MobileApiException.NOT_FOUND, words.t("Сервер не помнит это задание: скорее всего, он перезапустился. Проверьте список модулей.", "The server does not remember this job: it has most likely restarted. Check the list of modules."))
            buildJsonObject { put("item", jobRecord(job, current, words)) }
        }
        else -> fail(MobileApiException.FORBIDDEN, "Method is not available")
    }

    private val DemoWords.restartScheduled: String
        get() = t("Перезагрузка запланирована на 00:00 по времени сервера.", "Restart is scheduled for 00:00 server time.")

    private companion object {
        /** How long one step of a job takes on the demo: long enough to see it move. */
        const val STEP_MS = 800L

        /** How long the demo says «Сервер перезапускается». */
        const val RESTART_MS = 4_000L

        /** How much of a composite value is worth putting in a row of a list. */
        const val VALUE_TEXT_LIMIT = 40
    }
}
