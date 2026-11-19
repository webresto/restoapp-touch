package cx.m42.restoapp.render

import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.requiredNames
import cx.m42.superizer.runtime.HostLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The mark beside an app in the menu (P4, `app.badge`): the number the declared method answers — «Заказы · 8».
 *
 * Asked when the app is connected (and when the method, the point or the window coming back to the front
 * changes what there is to ask), after each mutation of the app's own ([refresh]), and every [intervalMs] while
 * the host is in front. In the background nothing is asked and the last mark stays. 0, an answer that is not a
 * number, or no answer at all — no mark. A method the declaration does not have, or one that takes required
 * params, is no badge: the declaration is a hint, the menu asks nothing it cannot ask.
 */
internal class BadgePoller(
    private val scope: CoroutineScope,
    private val server: RestoServer,
    private val lifecycle: StateFlow<HostLifecycle>,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private val _mark = MutableStateFlow<String?>(null)
    val mark: StateFlow<String?> get() = _mark.asStateFlow()

    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun start(): BadgePoller {
        if (job != null) return this
        job = scope.launch {
            combine(server.app, server.place, lifecycle) { app, place, life -> Triple(badgeMethod(app?.badge?.let(app::method)), place?.id, life) }
                .distinctUntilChanged()
                .collectLatest { (method, _, life) ->
                    if (method == null) {
                        _mark.value = null
                        return@collectLatest
                    }
                    if (life != HostLifecycle.Foreground) return@collectLatest
                    // A kick left over from before is this very read.
                    kicks.tryReceive()
                    while (true) {
                        ask(method)
                        withTimeoutOrNull(intervalMs) { kicks.receive() }
                    }
                }
        }
        return this
    }

    /** One of this app's mutations went through: what the mark counts may have changed. */
    fun refresh() {
        kicks.trySend(Unit)
    }

    private suspend fun ask(method: String) {
        _mark.value = try {
            markOf(server.call(method, JsonObject(emptyMap())))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MobileApiException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    companion object {
        const val INTERVAL_MS: Long = 60_000

        /** The method of a badge as the client may ask it: a query without required params (`place` is the host's). */
        fun badgeMethod(method: DeclaredMethod?): String? = method
            ?.takeIf { it.kind == DeclaredMethod.KIND_QUERY }
            ?.takeIf { it.input.requiredNames().isEmpty() }
            ?.name

        /** The answer as a mark: a positive number, «99+» past 99; anything else is none. */
        fun markOf(answer: kotlinx.serialization.json.JsonElement): String? {
            val number = (answer as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
            val whole = number.toLong()
            return when {
                whole <= 0 -> null
                whole > 99 -> "99+"
                else -> whole.toString()
            }
        }
    }
}
