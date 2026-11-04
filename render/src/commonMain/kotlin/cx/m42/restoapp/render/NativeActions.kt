package cx.m42.restoapp.render

import androidx.compose.runtime.Composable
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.ServerFacts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Screens of the client's own that run one action — `control: {type: "native", screen: "promotion-editor"}`.
 *
 * The universal renderer draws a stepper, a toggle, buttons and a form; an editor of a promotion needs products
 * and groups chosen from a catalog, a gift and hours, and no declaration describes that (`marketing.plan.md`,
 * Q43). So one *action* of an otherwise declared app may ask for a screen the client has built in — the same
 * trick as `client: {native}` of the POS, but for an action rather than for a whole app.
 *
 * A screen that is not registered is an action not drawn: exactly what the renderer already does with a control
 * it does not know, so a server one version ahead loses a button and not a screen.
 */
public typealias NativeActionContent = @Composable (NativeActionScope) -> Unit

/** What a native screen of an action is given: the record it acts on, and the way back to the server. */
public class NativeActionScope(
    /** The record the action was opened on; `{}` when the action is the list's own. */
    public val record: JsonObject,
    public val method: DeclaredMethod,
    /** Currency and time zone, for the values the screen shows. */
    public val facts: ServerFacts?,
    public val lang: String,
    /** True while the action it submitted is in flight. */
    public val busy: Boolean,
    /** Any method of this app — the catalog the editor chooses products in. */
    public val call: suspend (method: String, params: JsonObject) -> JsonElement,
    /** Runs the action with these params; the screen closes once the server answers. */
    public val submit: (JsonObject) -> Unit,
    public val close: () -> Unit,
)

/** The screens this build has. The host registers them at startup, as it registers its native apps. */
public object NativeActions {
    private val screens = mutableMapOf<String, NativeActionContent>()

    public fun register(screen: String, content: NativeActionContent) {
        screens[screen] = content
    }

    public fun of(screen: String?): NativeActionContent? = screen?.let { screens[it] }

    /** For tests: nothing registered is the state of a client that does not know the screen. */
    public fun clear() {
        screens.clear()
    }
}
