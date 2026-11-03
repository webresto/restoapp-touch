package cx.m42.restoapp.pos

import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.render.RestoServer
import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.require
import kotlinx.serialization.Serializable

/** Nothing to configure: what the POS shows is the order the server counted. */
@Serializable
public data class PosConfig(val schemaVersion: Int = 1)

/**
 * The POS of a server (`order-entry.plan.md`): a receipt with a catalog behind a «+».
 *
 * A native app and the only one: the id, the title, the icon, the points and the methods still come
 * from the declaration, and the host only picks this class instead of the universal renderer because
 * the declaration said `client: {native: "pos"}`. A client without this class shows the server's app
 * as a «needs a newer version» tile, so a server may declare it to anybody.
 */
public class PosApp(private val declared: DeclaredApp, private val lang: String) : SuperizerApp<PosConfig>() {

    /** The newest `client.min` this build can draw; a higher one is for a newer RestoApp. */
    public companion object {
        public const val NATIVE: String = "pos"
        public const val VERSION: Int = 1

        /** Whether this build draws the app the declaration describes. */
        public fun canDraw(app: DeclaredApp): Boolean =
            app.native == NATIVE && (app.client?.min ?: 1) <= VERSION && AppId.parseOrNull(app.id) != null
    }

    override val manifest: AppManifest = AppManifest(
        id = AppId(declared.id),
        version = declared.version,
        metadata = AppMetadata(
            title = localized(lang to declared.title),
            icon = declared.icon?.let { AppIcon.Path(it.path) } ?: AppIcon.Letter(declared.title.firstOrNull()?.uppercaseChar() ?: '?'),
            category = declared.category,
        ),
        requires = setOf(RestoServer.Key),
    )

    override val configSpec: AppConfigSpec<PosConfig> = AppConfigSpec(PosConfig.serializer(), PosConfig())

    override fun launch(runtime: InstanceRuntime, config: PosConfig): AppInstance =
        PosInstance(runtime, runtime.require(RestoServer.Key))
}
