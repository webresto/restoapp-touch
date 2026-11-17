package cx.m42.restoapp.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.id
import cx.m42.restoapp.protocol.jsonObjectOf
import cx.m42.restoapp.protocol.recordOf
import cx.m42.restoapp.render.ConfirmButton
import cx.m42.restoapp.render.NativeActionScope
import cx.m42.restoapp.render.Notice
import cx.m42.restoapp.render.RenderWords
import cx.m42.superizer.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant

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
    val tokens = AppTheme
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
    Column(modifier = Modifier.fillMaxSize().testTag("module-progress")) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Button(
                text = if (current is Phase.Watching && state != "done" && state != "failed") words.collapse else words.close,
                onClick = scope.close,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("module-progress:close"),
            )
            Text(
                text = job?.text("title") ?: scope.method.title,
                style = tokens.subtitle,
                color = tokens.foreground,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).weight(1f).testTag("module-progress:title"),
            )
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (current) {
                Phase.Asking -> Column(
                    modifier = Modifier.padding(16.dp).testTag("module-progress:question"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = question.orEmpty(), style = tokens.body, color = tokens.foreground)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            text = scope.method.title,
                            onClick = { phase = Phase.Starting },
                            variant = ButtonVariant.Destructive,
                            size = ButtonSize.Sm,
                            modifier = Modifier.testTag("module-progress:yes"),
                        )
                        Button(
                            text = words.cancel,
                            onClick = scope.close,
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            modifier = Modifier.testTag("module-progress:no"),
                        )
                    }
                }
                Phase.Starting -> Line(words.starting, "module-progress:step")
                is Phase.Refused -> Notice(current.text, "danger", null, null, "module-progress:refused")
                is Phase.Lost -> Notice(current.text, "warning", null, null, "module-progress:lost")
                is Phase.Watching -> {
                    if (offline) Notice(words.offline, "warning", null, null, "module-progress:offline")
                    when (state) {
                        "done" -> {
                            Notice(job?.text("doneText").orEmpty(), "success", null, null, "module-progress:done")
                            restarting?.let { Notice(it, "warning", null, null, "module-progress:restarting") }
                            restartRefused?.let { Notice(it, "danger", null, null, "module-progress:restart-refused") }
                            job?.text("restartText")?.takeIf { restarting == null }?.let { asked ->
                                Line(asked, "module-progress:restart")
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
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            Button(
                                text = if (scope.busy) words.loading else words.finish,
                                onClick = {
                                    scope.submit(jsonObjectOf("appId" to JsonPrimitive(appId), "job" to JsonPrimitive(current.jobId)))
                                },
                                enabled = !scope.busy,
                                size = ButtonSize.Lg,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("module-progress:finish"),
                            )
                        }
                        "failed" -> Notice(job?.text("errorText").orEmpty(), "danger", null, null, "module-progress:error")
                        else -> Line(job?.text("stepText") ?: words.starting, "module-progress:step")
                    }
                    job?.text("log")?.let { log ->
                        Text(
                            text = log,
                            style = tokens.footnote,
                            color = tokens.muted,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("module-progress:log"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(text: String, tag: String) {
    Text(
        text = text,
        style = AppTheme.body,
        color = AppTheme.foreground,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
    )
}
