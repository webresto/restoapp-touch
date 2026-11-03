package cx.m42.restoapp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Protocol 1 of the RestoApp mobile API as the client reads it — `schema/declaration.schema.json`
 * of the app-mobile-api hook, which the contract tests check these against.
 *
 * Every closed set of the contract (view, control, format, tone, kind, scope) is a string here, not
 * an enum: a server one version ahead may send a value this client has never seen, and the rule of
 * the plan is "an unknown view, control or format is drawn as text, never a crash".
 */

/** The major this client speaks. Another major in a declaration or `X-Mobile-Protocol` → "update the app". */
public const val PROTOCOL_VERSION: Int = 1

/** Unknown keys are a server that grew a field; missing optional ones are its defaults. */
public val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = false
}

/** `GET /api/mobile/describe` → what this user may do on this server, in their language. */
@Serializable
public data class Declaration(
    val protocolVersion: Int,
    /** Changes whenever anything the declaration is built from changes; every answer carries it. */
    val revision: String,
    val server: ServerFacts,
    val user: DeclaredUser,
    /** Every point of every app by points, sorted by title. */
    val places: List<Place> = emptyList(),
    val apps: List<DeclaredApp> = emptyList(),
) {
    public fun app(id: String): DeclaredApp? = apps.firstOrNull { it.id == id }

    public fun place(id: String?): Place? = places.firstOrNull { it.id == id }
}

/** Facts the client formats by: money in [currency], dates in [timezone]. */
@Serializable
public data class ServerFacts(
    val name: String,
    val version: String? = null,
    val currency: String? = null,
    val timezone: String? = null,
    val locale: String? = null,
)

@Serializable
public data class DeclaredUser(
    /** A number or a string, whichever the server's users have. */
    val id: JsonPrimitive,
    val login: String,
    val fullName: String? = null,
    val isAdministrator: Boolean = false,
)

/** A point: a kitchen, a pickup point. Apps by points work on one of them at a time. */
@Serializable
public data class Place(
    val id: String,
    val title: String,
    val address: String? = null,
)

@Serializable
public data class DeclaredApp(
    /** `[a-z0-9-]`: becomes the `AppId` of the app in Superizer. */
    val id: String,
    val version: String,
    /** Already in the language the declaration was asked in. */
    val title: String,
    val icon: Icon? = null,
    val category: String? = null,
    /** The method whose screen opens first. */
    val home: String,
    /** [SCOPE_PLACE]: every call names one point of [places]. */
    val scope: String = SCOPE_SERVER,
    val places: List<String> = emptyList(),
    val models: Map<String, Model> = emptyMap(),
    val methods: List<DeclaredMethod> = emptyList(),
    /**
     * The client draws this app itself — the POS of `order-entry.plan.md`. The universal renderer
     * cannot: a receipt with a catalog behind a «+» is not a list with actions on a record. A client
     * that does not have this native app (or is older than `min`) shows the "needs a newer version"
     * tile, exactly as it does for [script].
     */
    val client: ClientRender? = null,
    /**
     * Reserved for apps that come as JS (script-apps.plan.md). A client that cannot run scripts shows
     * such an app as "needs a newer version", and neither crashes nor hides it.
     */
    val script: JsonElement? = null,
) {
    val byPlace: Boolean get() = scope == SCOPE_PLACE

    /** Which built-in screen this app asks for, if any. */
    public val native: String? get() = client?.native

    public fun method(name: String?): DeclaredMethod? = methods.firstOrNull { it.name == name }

    /** The fields of [model] as this user may see them; empty when the model is not declared. */
    public fun fields(model: String?): List<Field> = models[model]?.fields.orEmpty()

    public companion object {
        public const val SCOPE_SERVER: String = "server"
        public const val SCOPE_PLACE: String = "place"
    }
}

/**
 * «This one the client draws»: [native] names the built-in screen, [min] the oldest client that has
 * it. Both come from the server, and a client that knows neither shows a tile instead of a screen.
 */
@Serializable
public data class ClientRender(
    val native: String,
    val min: Int = 1,
)

/** An SVG path over a 24×24 box — `AppIcon.Path` in Superizer. */
@Serializable
public data class Icon(val path: String)

@Serializable
public data class Model(val fields: List<Field> = emptyList())

/** A field of a model as the user may see it. A field that is not declared is not shown. */
@Serializable
public data class Field(
    val name: String,
    val title: String,
    val format: String = Formats.TEXT,
    val readonly: Boolean = false,
    /** The card groups fields by this; fields without one come first. */
    val section: String? = null,
    /** Values with their own words: -1 "Unlimited", 0 "Out of stock". */
    val specials: List<Special> = emptyList(),
    /** Titles of the values of an `enum` field. */
    @SerialName("x-titles") val titles: Map<String, String> = emptyMap(),
)

@Serializable
public data class Special(
    val value: JsonPrimitive,
    val title: String,
    val tone: String? = null,
)

@Serializable
public data class DeclaredMethod(
    val name: String,
    /** [KIND_QUERY] reads, [KIND_MUTATION] changes and is audited. */
    val kind: String,
    val title: String = "",
    /** JSON Schema of `params`, already narrowed for this user. */
    val input: JsonObject = JsonObject(emptyMap()),
    /** The model of the records a query returns; their `_actions` are the actions on it. */
    val model: String? = null,
    val ui: Ui? = null,
) {
    public companion object {
        public const val KIND_QUERY: String = "query"
        public const val KIND_MUTATION: String = "mutation"
    }
}

/** Screen hints, version 1 — exactly the keys of the contract. */
@Serializable
public data class Ui(
    /** [Views]: list, detail, action. */
    val view: String,
    val item: ItemUi? = null,
    val groupBy: String? = null,
    /** The input parameter the search box writes. */
    val search: String? = null,
    /** The `enum` input parameter the chips choose; titles from its `x-titles`, numbers from `counts`. */
    val chips: String? = null,
    /** The method a tap on a row opens. */
    val open: String? = null,
    val empty: String? = null,
    /** An action's model: shown on its records. `list` — on the list screen itself. */
    val on: String? = null,
    /** Where an action on a record is drawn: `row`, `detail`, `list`. */
    val placement: List<String> = emptyList(),
    val control: Control? = null,
    /** Asked before the action runs. */
    val confirm: String? = null,
    val specials: List<Special> = emptyList(),
    val bind: String? = null,
    val param: String? = null,
)

/** Which fields of a record a row shows. */
@Serializable
public data class ItemUi(
    val title: String? = null,
    val subtitle: String? = null,
    val meta: String? = null,
    val value: String? = null,
    val badge: String? = null,
    val image: String? = null,
)

/** How an action is drawn. [param] is the input it sets, [bind] the field of the record it shows. */
@Serializable
public data class Control(
    /** [Controls]: stepper, toggle, buttons, form. */
    val type: String,
    val param: String? = null,
    val bind: String? = null,
    val specials: List<Special> = emptyList(),
    /** Tone of each value of a `buttons` control. */
    val tones: Map<String, String> = emptyMap(),
)

public object Views {
    public const val LIST: String = "list"
    public const val DETAIL: String = "detail"
    public const val ACTION: String = "action"
}

public object Controls {
    public const val STEPPER: String = "stepper"
    public const val TOGGLE: String = "toggle"
    public const val BUTTONS: String = "buttons"
    public const val FORM: String = "form"
}

public object Formats {
    public const val TEXT: String = "text"
    public const val NUMBER: String = "number"
    public const val MONEY: String = "money"
    public const val DATETIME: String = "datetime"
    public const val RELATIVE: String = "relative"
    public const val PHONE: String = "phone"
    public const val BOOLEAN: String = "boolean"
    public const val ENUM: String = "enum"
    public const val IMAGE: String = "image"
    public const val REF: String = "ref"
}

public object Tones {
    public const val INFO: String = "info"
    public const val WARNING: String = "warning"
    public const val SUCCESS: String = "success"
    public const val DANGER: String = "danger"
}

/** What `server.about` tells before anyone signs in. */
@Serializable
public data class ServerAbout(val name: String, val version: String? = null)

@Serializable
public data class SignedInUser(
    val id: JsonPrimitive,
    val login: String,
    val fullName: String? = null,
)

/** `POST /api/mobile/auth/login` → a device token, shown once and kept by the host only. */
@Serializable
public data class LoginResult(
    val token: String,
    val user: SignedInUser,
    val protocolVersion: Int,
)

/** What the server's device list says about this one: "MacBook — desktop". */
@Serializable
public data class DeviceInfo(val name: String, val platform: String)
