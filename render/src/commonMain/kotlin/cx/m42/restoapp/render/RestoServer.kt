package cx.m42.restoapp.render

import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.superizer.runtime.ServiceKey
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What one app sees of its server (plan section 4, "Сеть — только у хоста").
 *
 * Bound by the host to one server and one app: [call] goes only to this app's methods as the
 * declaration has them — the host refuses any other — and the token is added by the host, so no
 * app ever holds one. For an app by points the host adds the current point too.
 */
public interface RestoServer {

    /** This app as the server declares it now; null once the server no longer declares it. */
    public val app: StateFlow<DeclaredApp?>

    /**
     * The address this server was connected by. A link that leads outside the apps (`open: {url}`) is
     * usually a path of this very server — the settings page of a provider module — and that is what it
     * is resolved against. Empty when there is no address to speak of (the demo server in process).
     */
    public val address: String get() = ""

    /** Currency and time zone the values are formatted in. */
    public val facts: StateFlow<ServerFacts?>

    /** The points of this app, in the server's order. Empty for an app without points. */
    public val places: StateFlow<List<Place>>

    /**
     * The point calls go to. One per server, shared by its apps by points: picked in «Заказы», it
     * is where «Блюда» open — unless this app does not have it, then the app's first point.
     */
    public val place: StateFlow<Place?>

    /** The person picked a point: it becomes the server's point for every app that has it. */
    public fun selectPlace(id: String)

    public val status: StateFlow<ServerStatus>

    /** One-off words from the host: "Rights changed", "Access to the point «…» was removed". */
    public val notices: SharedFlow<String>

    /**
     * Calls one method of this app. Throws `MobileApiException` by the protocol's code; the host has
     * already acted on it — re-read the declaration after `forbidden`, moved to another point after
     * `place_forbidden`, asked to sign in after `unauthorized`.
     */
    public suspend fun call(method: String, params: JsonObject): JsonElement

    public companion object {
        public val Key: ServiceKey<RestoServer> = ServiceKey("restoapp-server")
    }
}

/** The connection to the server, as an app needs to know it. */
public enum class ServerStatus {
    Online,

    /** No answer: what is on screen is what was there before. */
    Offline,

    /** The token is gone — revoked, expired, the password changed. The host's banner signs in again. */
    SignedOut,

    /** The server speaks another major of the protocol. */
    UpdateRequired,
}
