package cx.m42.restoapp.protocol

import cx.m42.superizer.runtime.NetworkException
import cx.m42.superizer.runtime.NetworkRequest
import cx.m42.superizer.runtime.NetworkResponse
import cx.m42.superizer.runtime.NetworkService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A real server by its address: `<base>/api/mobile/…` over the host's [NetworkService].
 *
 * The host's network injects nothing (Superizer D32), so the bearer token is set here, per request,
 * and only ever sent to [base]. Every answer is JSON — `{result}` or `{error}`; anything else at the
 * address (a web page, a proxy's error) is [MobileApiException.PROTOCOL].
 */
public class HttpMobileApi(
    /** `https://admin.cafe.ru` — what [normalizeAddress] makes of what the person typed. */
    public val base: String,
    private val network: NetworkService,
) : MobileApi {

    private val api = "$base/api/mobile"

    override suspend fun about(): ServerAbout =
        decode(post("$api/call/server.about", jsonObjectOf("params" to JsonObject(emptyMap())), token = null).value, ServerAbout.serializer())

    override suspend fun challenge(): ByteArray? {
        val result = send(NetworkRequest("GET", "$api/auth/challenge", HEADERS)).value as? JsonObject
            ?: throw MobileApiException(MobileApiException.PROTOCOL, "No task in the answer")
        val task = result["task"] ?: JsonNull
        if (task is JsonNull) return null
        val bytes = (task as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: -1 }
        if (bytes == null || bytes.any { it !in 0..255 }) throw MobileApiException(MobileApiException.PROTOCOL, "The task is not an array of bytes")
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }

    override suspend fun login(login: String, password: String, captchaSolution: String?, device: DeviceInfo): LoginResult {
        val body = jsonObjectOf(
            "login" to JsonPrimitive(login),
            "password" to JsonPrimitive(password),
            "captchaSolution" to captchaSolution?.let(::JsonPrimitive),
            "device" to jsonObjectOf("name" to JsonPrimitive(device.name), "platform" to JsonPrimitive(device.platform)),
        )
        val result = decode(post("$api/auth/login", body, token = null).value, LoginResult.serializer())
        if (result.protocolVersion != PROTOCOL_VERSION) throw updateRequired(result.protocolVersion)
        return result
    }

    override suspend fun logout(token: String) {
        post("$api/auth/logout", JsonObject(emptyMap()), token)
    }

    override suspend fun describe(token: String, lang: String): Answer<Declaration> {
        val answer = send(NetworkRequest("GET", "$api/describe?lang=$lang", HEADERS + bearer(token)))
        val declaration = decode(answer.value, Declaration.serializer())
        if (declaration.protocolVersion != PROTOCOL_VERSION) throw updateRequired(declaration.protocolVersion)
        return Answer(declaration, answer.revision ?: declaration.revision, answer.protocol)
    }

    override suspend fun call(token: String, method: String, params: JsonObject, place: String?, lang: String): Answer<JsonElement> =
        post("$api/call/$method?lang=$lang", jsonObjectOf("params" to params, "place" to place?.let(::JsonPrimitive)), token)

    /**
     * The bundle of a script app, from the link the declaration gave and nowhere else: a URL that does
     * not belong to this server is refused before any request is made. The answer is JS, not JSON, so
     * this is the one path that does not go through [send].
     */
    override suspend fun script(token: String, ref: ScriptRef): String {
        val url = if (ref.url.startsWith("/")) "$base${ref.url}" else ref.url
        if (!url.startsWith("$api/")) throw MobileApiException(MobileApiException.PROTOCOL, "The script link is not of this server: ${ref.url}")

        val response = try {
            network.request(NetworkRequest("GET", url, mapOf("Accept" to "application/javascript") + bearer(token)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NetworkException) {
            throw MobileApiException(MobileApiException.NETWORK, failure.message ?: "No connection", cause = failure)
        }
        if (!response.isSuccess) {
            val code = (runCatching { ProtocolJson.parseToJsonElement(response.body) }.getOrNull() as? JsonObject)?.get("error") as? JsonObject
            throw code?.let { errorOf(it, response) }
                ?: MobileApiException(MobileApiException.PROTOCOL, "HTTP ${response.status} for a script", status = response.status)
        }

        val body = response.body
        val bytes = body.encodeToByteArray()
        if (bytes.size != ref.size || sha256Hex(bytes) != ref.sha256) {
            throw MobileApiException(MobileApiException.PROTOCOL, "The script is not the one the declaration named (${ref.url})")
        }
        return body
    }

    private suspend fun post(url: String, body: JsonObject, token: String?): Answer<JsonElement> =
        send(
            NetworkRequest(
                method = "POST",
                url = url,
                headers = HEADERS + (token?.let(::bearer) ?: emptyMap()),
                body = ProtocolJson.encodeToString(JsonObject.serializer(), body),
                contentType = "application/json",
            ),
        )

    private suspend fun send(request: NetworkRequest): Answer<JsonElement> {
        val response: NetworkResponse = try {
            network.request(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NetworkException) {
            throw MobileApiException(MobileApiException.NETWORK, failure.message ?: "No connection", cause = failure)
        }
        val protocol = response.header("X-Mobile-Protocol")?.toIntOrNull()
        if (protocol != null && protocol != PROTOCOL_VERSION) throw updateRequired(protocol)

        val body = runCatching { ProtocolJson.parseToJsonElement(response.body) }.getOrNull() as? JsonObject
            ?: throw MobileApiException(
                MobileApiException.PROTOCOL,
                "Not a RestoApp mobile API answer (HTTP ${response.status})",
                status = response.status,
            )
        (body["error"] as? JsonObject)?.let { error -> throw errorOf(error, response) }
        val result = body["result"] ?: throw MobileApiException(MobileApiException.PROTOCOL, "No result in the answer", status = response.status)
        if (!response.isSuccess) throw MobileApiException(MobileApiException.PROTOCOL, "HTTP ${response.status} with a result", status = response.status)
        return Answer(result, response.header("X-Mobile-Revision"), protocol)
    }

    private fun errorOf(error: JsonObject, response: NetworkResponse): MobileApiException {
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull ?: MobileApiException.PROTOCOL
        return MobileApiException(
            code = code,
            message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: code,
            status = response.status,
            retryable = (error["retryable"] as? JsonPrimitive)?.booleanOrNull ?: false,
            retryAfterSeconds = response.header("Retry-After")?.trim()?.toIntOrNull(),
            details = error["details"],
        )
    }

    private fun <T> decode(element: JsonElement, serializer: KSerializer<T>): T =
        try {
            ProtocolJson.decodeFromJsonElement(serializer, element)
        } catch (failure: IllegalArgumentException) {
            throw MobileApiException(MobileApiException.PROTOCOL, "Unexpected answer: ${failure.message}", cause = failure)
        }

    private companion object {
        val HEADERS = mapOf("Accept" to "application/json")

        fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

        fun updateRequired(version: Int) =
            MobileApiException(MobileApiException.UPDATE_REQUIRED, "The server speaks protocol $version, this app $PROTOCOL_VERSION")
    }
}

/** Header names are case-insensitive; Ktor keeps them as the server wrote them. */
internal fun NetworkResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

/**
 * What a typed address means: `admin.cafe.ru` → `https://admin.cafe.ru`; a trailing slash or
 * `/api/mobile` dropped; plain `http://` kept as typed — a server on a laptop has no TLS, and the
 * server itself refuses http where it must not be used. Null when there is no host at all.
 */
public fun normalizeAddress(typed: String): String? {
    var text = typed.trim()
    if (text.isEmpty()) return null
    if (!text.contains("://")) text = "https://$text"
    val scheme = text.substringBefore("://").lowercase()
    if (scheme != "https" && scheme != "http") return null
    var rest = text.substringAfter("://").trimEnd('/')
    rest = rest.removeSuffix("/api/mobile").trimEnd('/')
    val host = rest.substringBefore('/').substringBefore('?')
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    return "$scheme://${rest.substringBefore('?').substringBefore('#')}"
}
