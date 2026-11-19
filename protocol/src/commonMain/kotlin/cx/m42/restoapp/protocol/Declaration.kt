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
    /** What this server is built of and what was installed into it; empty from a server that does not say. */
    val modules: List<ServerModule> = emptyList(),
) {
    public fun app(id: String): DeclaredApp? = apps.firstOrNull { it.id == id }

    public fun place(id: String?): Place? = places.firstOrNull { it.id == id }

    /** P10: the money left on this account — of the server, or of the person; null for a RestoApp. */
    public val balance: AccountBalance? get() = server.balance ?: user.balance
}

/** Facts the client formats by: money in [currency], dates in [timezone]. */
@Serializable
public data class ServerFacts(
    val name: String,
    val version: String? = null,
    val currency: String? = null,
    val timezone: String? = null,
    val locale: String? = null,
    /** P10: the money left on a Resto Cloud account — the cloud's `server` (or `user`) says it; a RestoApp never does. */
    @Serializable(with = LenientBalance::class) val balance: AccountBalance? = null,
    /**
     * P11 (Resto Cloud): every amount of this server is credits ([currency] is then [CREDITS]); this is the display
     * currency of the account and the system rate — «≈ 12,35 $» next to credits, [CreditRate.note] behind an (i).
     */
    @Serializable(with = LenientCreditRate::class) val credits: CreditRate? = null,
) {
    public companion object {
        /** `server.currency` of a server whose amounts are credits (the cloud): an older client says «999 CR». */
        public const val CREDITS: String = "CR"
    }

    /** Facts of a Resto Cloud account: a cloud that does not say its currency (before P11) counts in credits all the same. */
    public fun ofCloud(): ServerFacts = if (currency == null) copy(currency = CREDITS) else this
}

/**
 * P11: the display currency of a cloud account and the system rate the administrator set — display only: the cloud
 * charges, stores and sells credits. [perCredit] — how much of [currency] one credit is; [note] — what the (i) says.
 */
@Serializable
public data class CreditRate(
    val currency: String,
    val sign: String,
    val perCredit: Double,
    val decimals: Int = 2,
    val signBefore: Boolean = false,
    val title: String? = null,
    val rateUpdatedAt: String? = null,
    val rateText: String? = null,
    val note: String? = null,
)

/**
 * P10: what is left on the account, in the account's money ([ServerFacts.currency]), how many days it lasts at
 * today's spend, and how alarming that is — [Tones.WARNING] under a week, [Tones.DANGER] under three days or at
 * nothing; null when there is nothing to say. A wrong shape is no balance, never a lost declaration.
 */
@Serializable
public data class AccountBalance(
    val amount: Double,
    @Serializable(with = LenientInt::class) val daysLeft: Int? = null,
    @Serializable(with = LenientString::class) val tone: String? = null,
) {
    /** [tone] when it is one of the two this client warns with. */
    public val warning: String? get() = tone?.takeIf { it == Tones.WARNING || it == Tones.DANGER }
}

@Serializable
public data class DeclaredUser(
    /** A number or a string, whichever the server's users have. */
    val id: JsonPrimitive,
    val login: String,
    val fullName: String? = null,
    val isAdministrator: Boolean = false,
    /** P10, where the cloud puts it on the person rather than the server. */
    @Serializable(with = LenientBalance::class) val balance: AccountBalance? = null,
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
     * The app comes as JS from the server (script-apps.plan.md, D12): where to download the bundle and
     * which bundle it must be. A client that cannot run scripts shows such an app as "needs a newer
     * version", and neither crashes nor hides it.
     */
    val script: ScriptRef? = null,
    /**
     * P4: the method whose number is the mark beside the app in the menu — «Заказы · 8». A query with no
     * required params (an app by points gets only its point); its answer is a number, and 0 is no mark.
     */
    @Serializable(with = LenientString::class) val badge: String? = null,
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

/**
 * Where the code of a script app is and what it must be.
 *
 * [sha256] is in [url] on purpose: a new build is a new link, so what was downloaded under this name
 * is good forever, and a body that hashes to something else is refused rather than run.
 */
@Serializable
public data class ScriptRef(
    val url: String,
    val sha256: String,
    val size: Int,
    /** The oldest client that may run it; a newer requirement than this client shows the update tile. */
    val min: Int = 1,
)

/**
 * A module of the server: [KIND_SYSTEM] — what the installation is built of, [KIND_INSTALLED] — what
 * was installed into it from the marketplace. A module that brought an app of its own names it in
 * [app], and if that app comes as JS, [script] is the same link the app itself carries.
 */
@Serializable
public data class ServerModule(
    val id: String,
    val version: String? = null,
    val kind: String = KIND_INSTALLED,
    val title: String? = null,
    val app: String? = null,
    val script: ScriptRef? = null,
) {
    public companion object {
        public const val KIND_SYSTEM: String = "system"
        public const val KIND_INSTALLED: String = "installed"
    }
}

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
    /**
     * P2: the [Tones] of the values of an `enum` field — wherever the value shows as a badge, a dot or a column
     * of a board. A value of another tone than the closed set is drawn as one without a tone.
     */
    @SerialName("x-tones") @Serializable(with = LenientStringMap::class) val tones: Map<String, String> = emptyMap(),
) {
    /** The tone of [value], when the server gave it one this client knows. */
    public fun toneOf(value: String?): String? = value?.let { tones[it] }?.takeIf { it in Tones.ALL }
}

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
    /** P1: the columns of the table of a `list` on a wide window, in this order; without them — those of [item]. */
    @Serializable(with = LenientColumns::class) val columns: List<Column>? = null,
    /** P3: the main action of a record (one; of several, the first). The record's other actions are secondary. */
    @Serializable(with = LenientBoolean::class) val primary: Boolean? = null,
    /** P5: the name of the section of the app this query is, already in the language asked; two or more make sections. */
    @Serializable(with = LenientString::class) val section: String? = null,
    /** P6: a `list` drawn as a board on a wide window, a column per value of an `enum` field. */
    @Serializable(with = LenientBoard::class) val board: Board? = null,
    /** P7: blocks of numbers and charts over the card of a `detail`. A block of a type this client does not know is skipped. */
    @Serializable(with = LenientSummary::class) val summary: List<SummaryBlock>? = null,
)

/** P1: one column of a list's table: a field of the model, [Align] and [Width] of the closed sets. */
@Serializable
public data class Column(
    val field: String,
    val align: String? = null,
    val width: String? = null,
) {
    public object Align {
        public const val START: String = "start"
        public const val END: String = "end"
    }

    public object Width {
        public const val NARROW: String = "narrow"
        public const val WIDE: String = "wide"
    }
}

/** P6: the board of a list — columns are the values of the enum field [field], in [order]. */
@Serializable
public data class Board(
    val field: String,
    val order: List<String> = emptyList(),
)

/**
 * P7: one block of a summary. [type] is one of [SummaryBlock.METRIC], [SERIES], [LIST], [SHARE]; the arrays
 * the last three read are fields of the record that the model does not declare — an older client shows none.
 */
@Serializable
public data class SummaryBlock(
    val type: String,
    val field: String,
    val title: String? = null,
    val format: String? = null,
    /** For a metric: the field with the change in percent; its sign is its tone. */
    val delta: String? = null,
    /** For a list: the field with the lines under it — «Блюда», «Промокод», «Итого». */
    val totals: String? = null,
    /** For a metric (Resto Cloud): the field of the record holding the tone of the number — `balanceTone` of «Баланс». */
    @Serializable(with = LenientString::class) val tone: String? = null,
) {
    public companion object {
        public const val METRIC: String = "metric"
        public const val SERIES: String = "series"
        public const val LIST: String = "list"
        public const val SHARE: String = "share"
        public val TYPES: Set<String> = setOf(METRIC, SERIES, LIST, SHARE)
    }
}

/** Which fields of a record a row shows. */
@Serializable
public data class ItemUi(
    val title: String? = null,
    val subtitle: String? = null,
    val meta: String? = null,
    val value: String? = null,
    val badge: String? = null,
    val image: String? = null,
    /** A third line of a card: where the order goes — the address, «Самовывоз», the table. */
    val detail: String? = null,
    /** A note at the card's foot, before its value: «6 поз.». */
    val note: String? = null,
    /**
     * P8: the field of the record with an array of numbers — the load of a service over the last hours; the
     * client draws it as a sparkline in the card and the table. The array is not declared in the model, so an
     * older client never shows it.
     */
    @Serializable(with = LenientString::class) val series: String? = null,
)

/** How an action is drawn. [param] is the input it sets, [bind] the field of the record it shows. */
@Serializable
public data class Control(
    /** [Controls]: stepper, toggle, buttons, form, native. */
    val type: String,
    /**
     * For [Controls.NATIVE]: which built-in screen of the client runs the action — the editor of a promotion
     * (`marketing.plan.md`, Q43). A client that does not have it draws no action at all, which is what it already
     * does for a control it does not know.
     */
    val screen: String? = null,
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

    /** A screen of the client's own, named by [Control.screen]: what no declaration can describe. */
    public const val NATIVE: String = "native"
}

public object Formats {
    public const val TEXT: String = "text"
    public const val NUMBER: String = "number"
    public const val MONEY: String = "money"

    /** P11 (Resto Cloud): a number of credits — «1 234,5 кр», with «≈ 12,35 $» where the server gave a rate. */
    public const val CREDITS: String = "credits"

    /** A number of percent: 10 is "10 %". */
    public const val PERCENT: String = "percent"
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

    /** Grey: done, cancelled, switched off. */
    public const val MUTED: String = "muted"

    public val ALL: Set<String> = setOf(INFO, WARNING, SUCCESS, DANGER, MUTED)
}

/**
 * What `server.about` tells before anyone signs in. [registration]: the server lets a person sign up from the app
 * (`POST /api/mobile/auth/register`) — Resto Cloud in test mode; a RestoApp never says so.
 */
@Serializable
public data class ServerAbout(
    val name: String,
    val version: String? = null,
    val registration: Boolean = false,
    /** A Resto Cloud account, not a RestoApp: its servers come from `/cloud/servers` and sit under it in the switcher. */
    val cloud: Boolean = false,
)

/** A server (a RestoApp service) of a cloud account, as `GET /api/mobile/cloud/servers` lists it. */
@Serializable
public data class CloudServer(
    val id: String,
    val title: String,
    /** starting, online, stopping, stopped, error, deleting. */
    val status: String,
    val url: String? = null,
    val adminUrl: String? = null,
)

/** How to sign in to one server of the account: its address and the RestoApp administrator the cloud made for it. */
@Serializable
public data class CloudServerAccess(val url: String, val login: String, val password: String, val status: String? = null)

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
