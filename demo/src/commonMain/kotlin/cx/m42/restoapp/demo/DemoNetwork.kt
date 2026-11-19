package cx.m42.restoapp.demo

import cx.m42.restoapp.protocol.CloudServer
import cx.m42.restoapp.protocol.CloudServerAccess
import cx.m42.restoapp.protocol.Declaration
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.protocol.LoginResult
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.protocol.ServerAbout
import cx.m42.superizer.runtime.NetworkRequest
import cx.m42.superizer.runtime.NetworkResponse
import cx.m42.superizer.runtime.NetworkService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * In-process servers behind addresses, as the host's network sees them: `<base>/api/mobile/…` answered by the
 * [MobileApi] at that base — a [DemoCloud] with its `cloud/servers`, a [DemoBackend] as one of its servers. So the
 * host talks HTTP exactly as to a real cloud (`HttpMobileApi`), and the test needs no cloud panel.
 */
public class DemoNetwork(private val servers: Map<String, MobileApi>) : NetworkService {
    override val online: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun request(request: NetworkRequest): NetworkResponse {
        val (base, api) = servers.entries.firstOrNull { request.url.startsWith(it.key.trimEnd('/') + "/api/mobile/") }?.toPair()
            ?: return NetworkResponse(404, "not found")
        val path = request.url.removePrefix(base.trimEnd('/') + "/api/mobile/")
        val route = path.substringBefore('?')
        val lang = path.substringAfter("lang=", "ru").substringBefore('&')
        val token = request.headers["Authorization"]?.removePrefix("Bearer ").orEmpty()
        val body = request.body?.let { runCatching { ProtocolJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        return try {
            val result: JsonElement = when {
                route == "call/server.about" -> encode(ServerAbout.serializer(), api.about())
                route == "auth/challenge" -> JsonObject(mapOf("task" to JsonNull))
                route == "auth/login" -> encode(
                    LoginResult.serializer(),
                    api.login(text(body, "login"), text(body, "password"), null, DeviceInfo("demo", "desktop")),
                )
                route == "auth/logout" -> JsonObject(mapOf("ok" to JsonPrimitive(true)))
                route == "describe" -> return describe(api, token, lang)
                route == "cloud/servers" && api is DemoCloud ->
                    JsonObject(mapOf("items" to encode(ListSerializer(CloudServer.serializer()), api.servers())))
                route.startsWith("cloud/servers/") && route.endsWith("/connect") && api is DemoCloud ->
                    encode(CloudServerAccess.serializer(), api.access(route.removePrefix("cloud/servers/").removeSuffix("/connect")))
                route.startsWith("call/") -> api.call(
                    token,
                    route.removePrefix("call/"),
                    body["params"] as? JsonObject ?: JsonObject(emptyMap()),
                    (body["place"] as? JsonPrimitive)?.contentOrNull,
                    lang,
                ).value
                else -> return NetworkResponse(404, "not found")
            }
            NetworkResponse(200, ProtocolJson.encodeToString(JsonElement.serializer(), JsonObject(mapOf("result" to result))))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MobileApiException) {
            val status = when (failure.code) {
                MobileApiException.UNAUTHORIZED, MobileApiException.INVALID_CREDENTIALS -> 401
                MobileApiException.FORBIDDEN, MobileApiException.PLACE_FORBIDDEN -> 403
                MobileApiException.NOT_FOUND -> 404
                else -> 400
            }
            NetworkResponse(status, """{"error":{"code":"${failure.code}","message":"${failure.message.orEmpty().replace("\"", "'")}","retryable":false}}""")
        }
    }

    private suspend fun describe(api: MobileApi, token: String, lang: String): NetworkResponse {
        val answer = api.describe(token, lang)
        val text = ProtocolJson.encodeToString(JsonElement.serializer(), JsonObject(mapOf("result" to encode(Declaration.serializer(), answer.value))))
        return NetworkResponse(200, text, mapOf("X-Mobile-Revision" to listOf(answer.value.revision)))
    }

    private fun text(body: JsonObject, key: String) = (body[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): JsonElement =
        ProtocolJson.encodeToJsonElement(serializer, value)
}
