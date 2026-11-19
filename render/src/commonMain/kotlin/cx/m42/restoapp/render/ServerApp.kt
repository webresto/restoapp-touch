package cx.m42.restoapp.render

import androidx.compose.runtime.Composable
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.Open
import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppSetupContext
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.require
import kotlinx.serialization.Serializable

/**
 * What an app shows is the server's declaration, so there is nothing to configure — apart from [target], the
 * screen a link of another app leads to: «Установить провайдера» opens «Систему» on its catalog, on that module.
 */
@Serializable
public data class ServerAppConfig(val schemaVersion: Int = 1, val target: Open? = null)

/**
 * One app a server declares (plan section 4): its id, title, icon and category come from the
 * declaration, its screens from the `ui` of its methods. The same class draws «Блюда», «Заказы» and
 * whatever a server registers next — the client has no screen of its own for any of them.
 *
 * [declared] is what the menu needs at registration; the screen follows the live declaration through
 * [RestoServer.app], so a method granted or taken away shows on the next answer, without a rebuild.
 */
public class ServerApp(declared: DeclaredApp, lang: String) : SuperizerApp<ServerAppConfig>() {

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

    override val configSpec: AppConfigSpec<ServerAppConfig> = AppConfigSpec(ServerAppConfig.serializer(), ServerAppConfig())

    /** The mark in the menu (P4), while the app is enabled; null until [setup], and for an app without `badge`. */
    private var badge: BadgePoller? = null

    /**
     * The mark beside the app in the menu, when the declaration names a method for it (`app.badge`). It is
     * kept on the app's own runtime, so it counts while no screen of the app is open.
     */
    override fun setup(ctx: AppSetupContext) {
        val server = ctx.runtime.service(RestoServer.Key) ?: return
        val poller = BadgePoller(ctx.runtime.scope, server, ctx.runtime.lifecycle).start()
        badge = poller
        ctx.badge(poller.mark)
    }

    override fun launch(runtime: InstanceRuntime, config: ServerAppConfig): AppInstance =
        ServerAppInstance(runtime, runtime.require(RestoServer.Key), config.target, mutated = { badge?.refresh() })

    public companion object {
        /** Ids the menu can take: the declaration's ids are kebab-case by the contract, but a server may be wrong. */
        public fun canShow(app: DeclaredApp): Boolean = AppId.parseOrNull(app.id) != null
    }
}

/** What the instance draws; split from it so the composables stay free of the state machine. */
@Composable
internal fun ServerAppContent(instance: ServerAppInstance) {
    ServerAppScreen(instance)
}
