package cx.m42.restoapp.script

import androidx.compose.runtime.Composable
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.render.RestoServer
import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.require
import kotlinx.serialization.Serializable

/** Nothing to configure: what the app shows is what its script draws. */
@Serializable
public data class ScriptAppConfig(val schemaVersion: Int = 1)

/** Where the code comes from: the host's loader, which downloaded it by the link of the declaration. */
public fun interface ScriptSource {
    public suspend fun bundle(app: DeclaredApp): String
}

/**
 * An app whose code the server keeps (js-module.plan.md, D12): the title, the icon and the rights are
 * the declaration's, as for any app, and the screens are the script's.
 *
 * The host gives it two things and nothing else: the server it may talk to (`RestoServer` — the token
 * and the point are added there) and the bundle it must run. The script sees neither.
 */
public class ScriptApp(
    declared: DeclaredApp,
    private val lang: String,
    private val source: ScriptSource,
) : SuperizerApp<ScriptAppConfig>() {

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

    override val configSpec: AppConfigSpec<ScriptAppConfig> = AppConfigSpec(ScriptAppConfig.serializer(), ScriptAppConfig())

    override fun launch(runtime: InstanceRuntime, config: ScriptAppConfig): AppInstance =
        ScriptAppInstance(runtime, runtime.require(RestoServer.Key), source, lang)

    public companion object {
        /** This build can run a script app when the script asks for a protocol it speaks. */
        public fun canRun(app: DeclaredApp): Boolean {
            val script = app.script ?: return false
            return AppId.parseOrNull(app.id) != null && script.min <= SCRIPT_UI_VERSION
        }
    }
}

/** The version of the component set this build draws; a script asking for more shows the update tile. */
public const val SCRIPT_UI_VERSION: Int = 1

/** What the instance draws; split off so the composables stay out of the state machine. */
@Composable
internal fun ScriptAppContent(instance: ScriptAppInstance) {
    ScriptAppScreen(instance)
}
