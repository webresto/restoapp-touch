package cx.m42.restoapp.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One RestoApp server, as protocol 1 lets a client talk to it: `HttpMobileApi` for a real one by its
 * address, `DemoBackend` for the demo in process. Stateless — the token is the caller's, so the host
 * keeps it and no app ever sees it.
 *
 * Every failure is a [MobileApiException]: an error of the protocol by its code, or
 * [MobileApiException.NETWORK] when no answer came at all.
 */
public interface MobileApi {
    /** Name and version, without a token: what the client shows before the login form. */
    public suspend fun about(): ServerAbout

    /** A PoW task for the login, or null when the server's CAPTCHA is off. */
    public suspend fun challenge(): ByteArray?

    public suspend fun login(login: String, password: String, captchaSolution: String?, device: DeviceInfo): LoginResult

    /** Revokes the device of [token]. */
    public suspend fun logout(token: String)

    public suspend fun describe(token: String, lang: String): Answer<Declaration>

    /** [place] is required exactly for the methods of apps by points, and refused for the rest. */
    public suspend fun call(token: String, method: String, params: JsonObject, place: String?, lang: String): Answer<JsonElement>
}

/** A result and what the headers said about the declaration it was computed against. */
public data class Answer<out T>(
    val value: T,
    /** `X-Mobile-Revision`: another one than the client's → re-read the declaration. */
    val revision: String?,
    /** `X-Mobile-Protocol`: another major than [PROTOCOL_VERSION] → "update the app". */
    val protocol: Int?,
)

/** An error answer of the protocol, by [code] — never by its message, which is for logs. */
public class MobileApiException(
    public val code: String,
    message: String,
    /** HTTP status; null when nothing was answered. */
    public val status: Int? = null,
    public val retryable: Boolean = false,
    /** `Retry-After` of `rate_limited`, in seconds. */
    public val retryAfterSeconds: Int? = null,
    public val details: JsonElement? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    public companion object {
        public const val UNAUTHORIZED: String = "unauthorized"
        public const val INVALID_CREDENTIALS: String = "invalid_credentials"
        public const val FORBIDDEN: String = "forbidden"
        public const val PLACE_FORBIDDEN: String = "place_forbidden"
        public const val HTTPS_REQUIRED: String = "https_required"
        public const val INVALID: String = "invalid"
        public const val CAPTCHA_INVALID: String = "captcha_invalid"
        public const val NOT_FOUND: String = "not_found"
        public const val CONFLICT: String = "conflict"
        public const val TOO_LARGE: String = "too_large"
        public const val RATE_LIMITED: String = "rate_limited"
        public const val INTERNAL: String = "internal"
        public const val UNAVAILABLE: String = "unavailable"

        /** Client side: no answer — DNS, TLS, a timeout, no network. */
        public const val NETWORK: String = "network"

        /** Client side: an answer that is not this API — not JSON, not an envelope, another server at the address. */
        public const val PROTOCOL: String = "protocol"

        /** Client side: the server speaks another major of the protocol — "update the app". */
        public const val UPDATE_REQUIRED: String = "update_required"
    }
}

/** An action available on one record: the method, and its input narrowed for this record. */
@Serializable
public data class RecordAction(val method: String, val input: JsonObject? = null)

/** `{items, nextCursor, counts, total}` — the envelope of every list. */
public data class ListPage(
    val items: List<JsonObject>,
    val nextCursor: String?,
    val counts: Map<String, Int>,
    val total: Int?,
) {
    public companion object {
        public fun of(result: JsonElement): ListPage {
            val body = result as? JsonObject ?: throw notAnEnvelope("list")
            val items = (body["items"] as? JsonArray ?: throw notAnEnvelope("list")).map { it as? JsonObject ?: throw notAnEnvelope("list") }
            return ListPage(
                items = items,
                nextCursor = (body["nextCursor"] as? JsonPrimitive)?.contentOrNull,
                counts = (body["counts"] as? JsonObject)?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.intOrNull?.let { key to it } }?.toMap().orEmpty(),
                total = (body["total"] as? JsonPrimitive)?.intOrNull,
            )
        }
    }
}

/** `{item}` — a record, and the answer of an action that changed one; null for `{ok: true}`. */
public fun recordOf(result: JsonElement): JsonObject? =
    ((result as? JsonObject)?.get("item") as? JsonObject)

/** The record's `_actions`. A record without one is read-only. */
public fun JsonObject.actions(): List<RecordAction> =
    (this["_actions"] as? JsonArray).orEmpty().mapNotNull { element ->
        runCatching { ProtocolJson.decodeFromJsonElement(RecordAction.serializer(), element) }.getOrNull()
    }

/** The record's id as a string: ids are strings or numbers depending on the server's models. */
public fun JsonObject.id(): String? = (this["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun notAnEnvelope(what: String) =
    MobileApiException(MobileApiException.PROTOCOL, "The answer is not a $what envelope")

/** `input.properties.<name>` of a method's schema. */
public fun JsonObject.property(name: String): JsonObject? =
    (this["properties"] as? JsonObject)?.get(name) as? JsonObject

/** The `enum` of a schema, as strings. */
public fun JsonObject.enumValues(): List<String> =
    (this["enum"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** The `x-titles` of a schema. */
public fun JsonObject.titles(): Map<String, String> =
    (this["x-titles"] as? JsonObject)?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }?.toMap().orEmpty()

/** `default` of a schema, as a string. */
public fun JsonObject.defaultString(): String? = (this["default"] as? JsonPrimitive)?.contentOrNull

/** The names in `properties`, in the schema's own order — the order a form draws them in. */
public fun JsonObject.propertyNames(): List<String> = (this["properties"] as? JsonObject)?.keys?.toList().orEmpty()

/** The `required` of a schema. */
public fun JsonObject.requiredNames(): Set<String> =
    (this["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()

/** `type` of a schema: `string`, `integer`, `number`, `boolean`. */
public fun JsonObject.schemaType(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

/** `title` of a schema — what a form calls the field; the server sends it translated. */
public fun JsonObject.schemaTitle(): String? = (this["title"] as? JsonPrimitive)?.contentOrNull

/** `x-format` of a schema: `phone`, `money`, `datetime` — a hint for the keyboard, not a type. */
public fun JsonObject.schemaFormat(): String? = (this["x-format"] as? JsonPrimitive)?.contentOrNull

/** A JSON object as a plain map, for building `params`. */
public fun jsonObjectOf(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

internal fun JsonElement.objectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
