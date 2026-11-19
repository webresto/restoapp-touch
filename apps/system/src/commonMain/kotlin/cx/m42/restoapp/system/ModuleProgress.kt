package cx.m42.restoapp.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minimize2
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.X
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.id
import cx.m42.restoapp.protocol.jsonObjectOf
import cx.m42.restoapp.protocol.recordOf
import cx.m42.restoapp.render.ConfirmButton
import cx.m42.restoapp.render.NativeActionScope
import cx.m42.restoapp.render.RenderWords
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Notice
import cx.m42.superizer.kit.Page
import cx.m42.superizer.kit.PageHeader
import cx.m42.superizer.kit.PageWidth
import cx.m42.superizer.kit.Panel
import cx.m42.superizer.kit.Spinner
import cx.m42.superizer.kit.StatusBadge
import cx.m42.superizer.kit.Terminal
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.softColor
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How often the job is asked about (`system.plan.md`, slice C). */
private const val POLL_MS = 2000L

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

/** Where the screen stands: what it was opened to do, and what became of that. */
private sealed interface Phase {
    /** The method asks first: nothing has been sent yet. */
    data object Asking : Phase
    data object Starting : Phase
    data class Refused(val text: String) : Phase
    data class Watching(val jobId: String) : Phase
    /** The server does not remember the job any more. */
    data class Lost(val text: String) : Phase
}

/**
 * The screen of `system.install`, `system.update` and `system.remove`: asks if the method asks, starts the job,
 * and polls `system.job` until it is over. Every word on it but the names of its buttons is the server's.
 *
 * It can be closed while the job goes on: the job lives on the server, and pressing the button again comes back
 * to it. «Готово» sends the action once more with the id of the job, which starts nothing: the answer is the
 * record as the job left it, so the card under this screen is right the moment it closes.
 */
@Composable
internal fun ModuleProgress(scope: NativeActionScope, pollMs: Long = POLL_MS) {
    val words = remember(scope.lang) { SystemWords(scope.lang) }
    val appId = scope.record.text("appId") ?: scope.record.id().orEmpty()
    val coroutines = rememberCoroutineScope()
    val question = scope.method.ui?.confirm

    var phase by remember { mutableStateOf<Phase>(if (question != null) Phase.Asking else Phase.Starting) }
    var job by remember { mutableStateOf<JsonObject?>(null) }
    var offline by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf<String?>(null) }
    var restartRefused by remember { mutableStateOf<String?>(null) }

    val current = phase
    if (current is Phase.Starting) {
        LaunchedEffect(Unit) {
            try {
                val record = recordOf(scope.call(scope.method.name, jsonObjectOf("appId" to JsonPrimitive(appId))))
                val jobId = record?.text("jobId")
                phase = if (jobId != null) Phase.Watching(jobId) else Phase.Refused(words.noJob)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                phase = Phase.Refused(failure.message ?: failure.code)
            }
        }
    }
    if (current is Phase.Watching) {
        LaunchedEffect(current.jobId) {
            while (true) {
                try {
                    val answer = recordOf(scope.call("system.job", jsonObjectOf("id" to JsonPrimitive(current.jobId))))
                    offline = false
                    job = answer
                    if (answer?.text("state") != "running") break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: MobileApiException) {
                    // The connection comes back and the job goes on without us; what is not the connection is final.
                    if (failure.code == MobileApiException.NETWORK) {
                        offline = true
                    } else {
                        phase = Phase.Lost(failure.message ?: failure.code)
                        break
                    }
                }
                delay(pollMs)
            }
        }
    }

    val state = job?.text("state")
    val stepText = job?.text("stepText")
    // The steps as the job went through them: the server sends the one it is on, the screen keeps the trail.
    val trail = remember { mutableStateListOf<String>() }
    LaunchedEffect(stepText) { if (stepText != null && trail.lastOrNull() != stepText) trail += stepText }
    val running = current is Phase.Watching && state != "done" && state != "failed"

    Column(modifier = Modifier.fillMaxSize().testTag("module-progress")) {
        Page(PageWidth.Narrow) {
            PageHeader(
                title = job?.text("title") ?: scope.method.title,
                leading = {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(Kit.shapes.lg)
                            .border(1.dp, Kit.colors.input, Kit.shapes.lg)
                            .background(Kit.colors.background)
                            .kitClickable(Kit.shapes.lg, onClick = scope.close)
                            .semantics { contentDescription = if (running) words.collapse else words.close }
                            .testTag("module-progress:close"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (running) Lucide.Minimize2 else Lucide.X, contentDescription = null, size = 18.dp) }
                },
                badge = {
                    when {
                        current is Phase.Refused || current is Phase.Lost || state == "failed" -> StatusBadge(words.failed, Tone.Danger)
                        state == "done" -> StatusBadge(words.done, Tone.Success)
                        current is Phase.Watching || current is Phase.Starting -> StatusBadge(words.running, Tone.Running)
                    }
                },
                meta = { Text(appId, style = Kit.textStyles.monoSmall, color = Kit.colors.mutedForeground, maxLines = 1) },
            )

            when (current) {
                Phase.Asking -> Panel(modifier = Modifier.testTag("module-progress:question"), title = words.confirmTitle) {
                    Text(text = question.orEmpty())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            text = scope.method.title,
                            onClick = { phase = Phase.Starting },
                            variant = ButtonVariant.Destructive,
                            modifier = Modifier.testTag("module-progress:yes"),
                        )
                        Button(
                            text = words.cancel,
                            onClick = scope.close,
                            variant = ButtonVariant.Outline,
                            modifier = Modifier.testTag("module-progress:no"),
                        )
                    }
                }
                Phase.Starting -> Panel(title = words.steps) {
                    StepRow(words.starting, StepState.Running, Modifier.testTag("module-progress:step"))
                }
                is Phase.Refused -> Notice(Tone.Danger, Lucide.CircleAlert, words.refused, current.text, Modifier.testTag("module-progress:refused"))
                is Phase.Lost -> Notice(Tone.Warn, Lucide.TriangleAlert, words.lost, current.text, Modifier.testTag("module-progress:lost"))
                is Phase.Watching -> {
                    if (offline) Notice(Tone.Warn, Lucide.WifiOff, words.offlineTitle, words.offline, Modifier.testTag("module-progress:offline"))
                    when (state) {
                        "done" -> Notice(Tone.Success, Lucide.CircleCheck, job?.text("doneText").orEmpty(), appId, Modifier.testTag("module-progress:done"))
                        "failed" -> Notice(Tone.Danger, Lucide.CircleAlert, words.failed, job?.text("errorText").orEmpty(), Modifier.testTag("module-progress:error"))
                    }
                    Panel(title = words.steps, description = if (job == null) words.waiting else null) {
                        val steps = trail.ifEmpty { listOf(stepText ?: words.starting) }
                        steps.forEachIndexed { i, step ->
                            val last = i == steps.lastIndex
                            StepRow(
                                step,
                                when {
                                    !last || state == "done" -> StepState.Done
                                    state == "failed" -> StepState.Failed
                                    else -> StepState.Running
                                },
                                if (last && running) Modifier.testTag("module-progress:step") else Modifier,
                            )
                        }
                    }
                    if (state == "done") {
                        restarting?.let { Notice(Tone.Running, Lucide.RotateCw, it, words.restartingHint, Modifier.testTag("module-progress:restarting")) }
                        restartRefused?.let { Notice(Tone.Danger, Lucide.CircleAlert, words.refused, it, Modifier.testTag("module-progress:restart-refused")) }
                        job?.text("restartText")?.takeIf { restarting == null }?.let { asked ->
                            Panel(title = words.restartTitle, icon = Lucide.RotateCw) {
                                Text(asked, variant = TextVariant.Muted, modifier = Modifier.testTag("module-progress:restart"))
                                ConfirmButton(
                                    text = words.restartNow,
                                    tone = "danger",
                                    confirm = job?.text("restartConfirm"),
                                    enabled = true,
                                    words = remember(scope.lang) { RenderWords(scope.lang) },
                                    onClick = {
                                        coroutines.launch {
                                            try {
                                                val card = recordOf(scope.call("system.restart", jsonObjectOf("when" to JsonPrimitive("now"))))
                                                restartRefused = null
                                                restarting = card?.text("restartText") ?: words.restarting
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: MobileApiException) {
                                                restartRefused = failure.message ?: failure.code
                                            }
                                        }
                                    },
                                    tag = "module-progress:restart-now",
                                    variant = ButtonVariant.Outline,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    job?.text("log")?.let { log ->
                        Terminal(words.log + " — " + appId, live = running, liveLabel = if (running) words.running else null) {
                            Text(log, style = Kit.textStyles.monoSmall, modifier = Modifier.testTag("module-progress:log"))
                        }
                    }
                    if (state == "done") {
                        Button(
                            text = if (scope.busy) words.loading else words.finish,
                            onClick = {
                                scope.submit(jsonObjectOf("appId" to JsonPrimitive(appId), "job" to JsonPrimitive(current.jobId)))
                            },
                            enabled = !scope.busy,
                            loading = scope.busy,
                            size = ButtonSize.Lg,
                            modifier = Modifier.fillMaxWidth().testTag("module-progress:finish"),
                        )
                    }
                }
            }
        }
    }
}

private enum class StepState { Done, Running, Failed }

/** One step with its mark: a tick when passed, a spinner while on it, a cross where it broke — as the cloud's start of a service. */
@Composable
private fun StepRow(text: String, state: StepState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val tone = when (state) {
            StepState.Done -> Tone.Success
            StepState.Running -> Tone.Running
            StepState.Failed -> Tone.Danger
        }
        Box(Modifier.size(24.dp).clip(CircleShape).background(tone.softColor()), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.Done -> Icon(Lucide.Check, null, size = 13.dp, tint = tone.textColor())
                StepState.Running -> Spinner(size = 12.dp)
                StepState.Failed -> Icon(Lucide.X, null, size = 13.dp, tint = tone.textColor())
            }
        }
        Text(text, style = Kit.textStyles.label, color = if (state == StepState.Running) Kit.colors.foreground else Kit.colors.foreground)
    }
}
