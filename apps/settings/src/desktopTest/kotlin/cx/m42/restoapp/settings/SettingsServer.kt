package cx.m42.restoapp.settings

import cx.m42.restoapp.protocol.Declaration
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.render.RestoServer
import cx.m42.restoapp.render.ServerStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * «Настройки» of a server that answers from memory: the declaration the hook builds, and three
 * settings of the kinds the editor has to tell apart — a string with a rule of its own, a secret, and
 * one with a default to go back to.
 *
 * Hand-written rather than taken from the demo server on purpose: the screen is being checked against
 * what a server may answer, the refusal of a write included, and a refusal is not something a demo
 * server has a reason to produce.
 */
internal class SettingsServer(
    /** What `settings.update` answers with instead of a record: the sentence of a server that refuses. */
    var refuseWith: String? = null,
) : RestoServer {

    val calls: MutableList<Pair<String, JsonObject>> = mutableListOf()

    private val declaration: Declaration = Json.decodeFromString(
        Declaration.serializer(),
        """
        {
          "protocolVersion": 1,
          "revision": "r1",
          "server": {"name": "Стенд"},
          "user": {"id": 1, "login": "admin", "isAdministrator": true},
          "apps": [{
            "id": "settings", "version": "1.0.0", "title": "Настройки", "home": "settings.list", "scope": "server",
            "models": {"setting": {"fields": [
              {"name": "type", "title": "Тип", "format": "enum", "readonly": true},
              {"name": "valueText", "title": "Значение", "format": "text", "readonly": true},
              {"name": "state", "title": "Состояние", "format": "text", "readonly": true}
            ]}},
            "methods": [
              {"name": "settings.list", "kind": "query", "title": "Настройки", "model": "setting",
               "input": {"type": "object", "properties": {"q": {"type": "string"}}},
               "ui": {"view": "list", "item": {"title": "key", "subtitle": "name", "meta": "state", "value": "valueText", "badge": "type"},
                      "search": "q", "open": "settings.get", "empty": "Настроек не нашлось"}},
              {"name": "settings.get", "kind": "query", "title": "Настройка", "model": "setting",
               "input": {"type": "object", "required": ["id"], "properties": {"id": {"type": "string"}}},
               "ui": {"view": "detail", "item": {"title": "key", "subtitle": "name", "badge": "type"}}},
              {"name": "settings.update", "kind": "mutation", "title": "Изменить значение",
               "input": {"type": "object", "required": ["id", "value"], "properties": {"id": {"type": "string"}, "value": {}}},
               "ui": {"view": "action", "on": "setting", "placement": ["detail"], "control": {"type": "native", "screen": "setting-editor"}}}
            ]
          }]
        }
        """.trimIndent(),
    )

    /** The three settings, as `settings.get` sends them: words for the renderer, the schema for the editor. */
    private val rows = mutableMapOf(
        "PROJECT_NAME" to buildJsonObject {
            put("id", "PROJECT_NAME"); put("key", "PROJECT_NAME"); put("name", "Project ID"); put("type", "string")
            put("valueText", "resto-stand"); put("state", "Обязательная"); put("value", "resto-stand")
            put("defaultValue", "restoapp"); put("isRequired", true); put("secret", false); put("hasValue", true)
            put("readOnly", false); put("restartRequired", false); put("boot", "boot-1")
            put("patternHint", "строчные латинские буквы, цифры и дефис")
            put("jsonSchema", Json.parseToJsonElement("""{"type": "string", "pattern": "^[a-z0-9-]+$", "minLength": 3}"""))
            put("_actions", buildJsonArray { add(buildJsonObject { put("method", "settings.update") }) })
        },
        "JWT_SECRET" to buildJsonObject {
            put("id", "JWT_SECRET"); put("key", "JWT_SECRET"); put("name", "JWT Secret"); put("type", "string")
            put("valueText", "••••••••"); put("state", "Секрет · Нужен перезапуск"); put("secret", true); put("hasValue", true)
            put("readOnly", false); put("isRequired", true); put("restartRequired", true); put("boot", "boot-1")
            put("jsonSchema", Json.parseToJsonElement("""{"type": "string", "minLength": 36}"""))
            put("_actions", buildJsonArray { add(buildJsonObject { put("method", "settings.update") }) })
        },
        "TZ" to buildJsonObject {
            put("id", "TZ"); put("key", "TZ"); put("name", "TZ"); put("type", "string")
            put("valueText", "Europe/Moscow"); put("state", "Из окружения"); put("value", "Europe/Moscow")
            put("envOverride", buildJsonObject { put("valid", true) }); put("secret", false); put("hasValue", true)
            put("readOnly", false); put("isRequired", false); put("restartRequired", false); put("boot", "boot-1")
            put("_actions", buildJsonArray { })
        },
    )

    override val app: StateFlow<DeclaredApp?> = MutableStateFlow(declaration.app("settings"))
    override val facts: StateFlow<ServerFacts?> = MutableStateFlow(declaration.server)
    override val places: StateFlow<List<Place>> = MutableStateFlow(emptyList())
    override val place: StateFlow<Place?> = MutableStateFlow(null)
    override val status: StateFlow<ServerStatus> = MutableStateFlow(ServerStatus.Online)
    override val notices: SharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 8)

    override fun selectPlace(id: String) = Unit

    override suspend fun call(method: String, params: JsonObject): JsonElement {
        calls += method to params
        return when (method) {
            "settings.list" -> buildJsonObject {
                put("items", buildJsonArray { rows.values.sortedBy { it["key"].toString() }.forEach { add(it) } })
                put("total", rows.size)
            }
            "settings.get" -> buildJsonObject { put("item", rows.getValue(params["id"].content())) }
            "settings.update" -> {
                refuseWith?.let { throw MobileApiException(MobileApiException.INVALID, it, status = 400) }
                val key = params["id"].content()
                val written = (params["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val kept = buildJsonObject {
                    rows.getValue(key).forEach { (name, value) -> put(name, value) }
                    put("value", written); put("valueText", written); put("hasValue", true)
                }
                rows[key] = kept
                buildJsonObject { put("item", kept) }
            }
            else -> error("the test server was asked for $method")
        }
    }

    private fun JsonElement?.content(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
}
