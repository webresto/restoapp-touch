package cx.m42.restoapp.assistant

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
import cx.m42.superizer.host.net.defaultHttpClient
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.require
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Nothing to configure: the assistant, its model and its conversations are the panel's. */
@Serializable
public data class AssistantConfig(val schemaVersion: Int = 1)

/**
 * «Ассистент» (`.ai-notes/assistant.plan.md`): the assistant of the admin panel of the current context, in a chat
 * drawn here — Resto Cloud Assistant for a cloud account, the RestoApp assistant for a server. Which one is not this
 * app's choice: the context declares it (`client: {native: "assistant"}`), and its one method `assistant.session`
 * says where the panel's agent is. The mechanism is the same for both — adminizer's agent routes on the context's
 * address — so the model, the tools, the rights, the conversations and the billing are the panel's, shared with the
 * web.
 */
public class AssistantApp(
    private val declared: DeclaredApp,
    private val lang: String,
    /** The transport of a context; tests and the demo pass their own. */
    private val transports: AssistantTransports = AssistantTransports.Default,
) : SuperizerApp<AssistantConfig>() {

    public companion object {
        public const val NATIVE: String = "assistant"
        public const val VERSION: Int = 1
        public const val SESSION_METHOD: String = "assistant.session"

        public fun canDraw(app: DeclaredApp): Boolean =
            app.native == NATIVE && (app.client?.min ?: 1) <= VERSION && AppId.parseOrNull(app.id) != null &&
                app.method(SESSION_METHOD) != null
    }

    override val manifest: AppManifest = AppManifest(
        id = AppId(declared.id),
        version = declared.version,
        metadata = AppMetadata(
            title = localized(lang to declared.title),
            icon = declared.icon?.let { AppIcon.Path(it.path) } ?: AppIcon.Letter(declared.title.firstOrNull()?.uppercaseChar() ?: 'A'),
            category = declared.category,
        ),
        requires = setOf(RestoServer.Key),
    )

    override val configSpec: AppConfigSpec<AssistantConfig> = AppConfigSpec(AssistantConfig.serializer(), AssistantConfig())

    override fun launch(runtime: InstanceRuntime, config: AssistantConfig): AppInstance {
        val server = runtime.require(RestoServer.Key)
        val opened = transports.open(lang)
        return AssistantInstance(
            runtime = runtime,
            server = server,
            transportOf = opened.factory,
            fallbackTitle = declared.method(SESSION_METHOD)?.title?.takeIf { it.isNotBlank() } ?: declared.title,
            onDispose = opened.close,
        )
    }
}

/** How a launched assistant reaches its panel: a factory of the transport and what to release after. */
public fun interface AssistantTransports {
    public class Opened(public val factory: suspend (RestoServer) -> AssistantTransport, public val close: () -> Unit)

    public fun open(lang: String): Opened

    public companion object {
        /**
         * `assistant.session` decides: `adminizer-agent` — the panel's routes on the context's address over the
         * platform's HTTP client (one per open app, closed with it); `demo` — the canned assistant of the demo.
         */
        public val Default: AssistantTransports = AssistantTransports { lang ->
            var client: HttpClient? = null
            Opened(
                factory = { server ->
                    val first = AgentSession.of(server.call(AssistantApp.SESSION_METHOD, JsonObject(emptyMap())))
                    if (first.transport == AgentSession.DEMO) {
                        DemoAssistantTransport(first.title, lang)
                    } else {
                        val http = client ?: defaultHttpClient {
                            expectSuccess = false
                            install(HttpTimeout) { connectTimeoutMillis = 15_000 }
                        }.also { client = it }
                        var pending: AgentSession? = first
                        AdminizerAgentTransport(server.address, http, lang = lang, session = {
                            pending?.also { pending = null }
                                ?: AgentSession.of(server.call(AssistantApp.SESSION_METHOD, JsonObject(emptyMap())))
                        })
                    }
                },
                close = { client?.close() },
            )
        }
    }
}
