package cx.m42.restoapp.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.render.Badge
import cx.m42.restoapp.render.NativeActionScope
import cx.m42.restoapp.render.Notice
import cx.m42.restoapp.render.prettyJson
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.CheckIcon
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Carrying the settings of one server to another: the screen
 * `control: {type: "native", screen: "setting-transfer"}` asks for, on the list itself
 * (`settings.plan.md`, slice D).
 *
 * The same two halves the web page has. Export is a read — `settings.dump`, which the screen calls
 * itself, because an action can only be a mutation — and it is text, so it goes to the clipboard;
 * secrets are not in it, the server leaves them out. Import is the preview of the difference: every
 * key with what it is now and what the file would make it, a tick on the ones that may be written,
 * and a report of what was applied and what was not, with the reason.
 *
 * The one difference from the web is where the file comes from and goes to: the clipboard rather than
 * the disk. A phone may have no disk a person can point at, and «Поделиться» or a file picker is
 * platform work this build has not got yet.
 */

private data class DiffRow(
    val key: String,
    val status: String,
    val current: JsonElement?,
    val incoming: JsonElement?,
    val locked: Boolean,
    val secret: Boolean,
)

private data class ResultRow(val key: String, val status: String, val error: String?)

private fun diffRowsOf(answer: JsonElement?): List<DiffRow> =
    ((answer as? JsonObject)?.get("diff") as? JsonArray).orEmpty().mapNotNull { row ->
        (row as? JsonObject)?.let {
            DiffRow(
                key = it.text("key"),
                status = it.text("status"),
                current = it.element("currentValue"),
                incoming = it["importValue"],
                locked = it.flag("readOnly") || it.flag("envOverride"),
                secret = it.flag("secret"),
            )
        }
    }

private fun resultRowsOf(answer: JsonElement?): List<ResultRow> =
    ((answer as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { row ->
        (row as? JsonObject)?.let { ResultRow(it.text("key"), it.text("status"), it.textOrNull("error")) }
    }

/** The `{key, value}` entries of a dump, whichever shape was pasted: the whole file or just its list. */
private fun entriesOf(text: String): List<JsonObject>? {
    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
    val list = when (parsed) {
        is JsonArray -> parsed
        is JsonObject -> parsed["settings"] as? JsonArray ?: return null
        else -> return null
    }
    val entries = list.mapNotNull { row ->
        (row as? JsonObject)?.takeIf { it.text("key").isNotBlank() }?.let { entry ->
            buildJsonObject {
                put("key", entry.text("key"))
                put("value", entry["value"] ?: JsonNull)
            }
        }
    }
    return entries.takeIf { it.isNotEmpty() }
}

@Composable
internal fun SettingTransfer(scope: NativeActionScope) {
    val words = remember(scope.lang) { SettingsWords(scope.lang) }
    val tokens = AppTheme
    val clipboard = LocalClipboardManager.current
    val coroutines = rememberCoroutineScope()

    var dump by remember { mutableStateOf<JsonObject?>(null) }
    var entries by remember { mutableStateOf<List<JsonObject>?>(null) }
    var diff by remember { mutableStateOf<List<DiffRow>?>(null) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var results by remember { mutableStateOf<List<ResultRow>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    // Export is a read, so the screen does it itself: `settings.dump` has no `ui` and is no action.
    LaunchedEffect(Unit) {
        runCatching { scope.call("settings.dump", JsonObject(emptyMap())) }
            .onSuccess { dump = it as? JsonObject }
            .onFailure { problem = it.message }
    }

    val writable = diff.orEmpty().filter { it.status == "changed" && !it.locked }

    Column(modifier = Modifier.fillMaxSize().testTag("settings:transfer")) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                text = words.cancel,
                onClick = scope.close,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("settings:transfer-cancel"),
            )
            Text(
                text = words.transfer,
                style = tokens.subtitle,
                color = tokens.foreground,
                modifier = Modifier.padding(horizontal = 8.dp).weight(1f),
            )
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            message?.let { Notice(it, Tones.SUCCESS, null, null, "settings:transfer-message") }
            problem?.let { Notice(it, Tones.DANGER, null, null, "settings:transfer-problem") }
            scope.failure?.let { Notice(it, Tones.DANGER, null, null, "settings:transfer-refusal") }

            Section(title = words.export) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val count = (dump?.get("count") as? JsonPrimitive)?.content?.toIntOrNull()
                    Text(
                        text = if (count == null) "…" else words.exportCount(count),
                        style = tokens.label,
                        color = tokens.muted,
                        modifier = Modifier.testTag("settings:transfer-count"),
                    )
                    AppButton(
                        text = words.copy,
                        onClick = {
                            dump?.let {
                                clipboard.setText(AnnotatedString(prettyJson(it)))
                                message = words.copied
                            }
                        },
                        enabled = dump != null,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag("settings:transfer-copy"),
                    )
                }
            }

            Section(title = words.import) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        text = words.paste,
                        onClick = {
                            message = null
                            problem = null
                            results = null
                            val pasted = entriesOf(clipboard.getText()?.text.orEmpty())
                            if (pasted == null) {
                                problem = words.noJson
                                return@AppButton
                            }
                            entries = pasted
                            working = true
                            coroutines.launch {
                                runCatching {
                                    scope.call(
                                        "settings.transfer",
                                        buildJsonObject {
                                            put("settings", JsonArray(pasted))
                                            put("preview", true)
                                        },
                                    )
                                }.onSuccess { answer ->
                                    diff = diffRowsOf(answer)
                                    chosen = emptySet()
                                }.onFailure { problem = it.message }
                                working = false
                            }
                        },
                        enabled = !working && !scope.busy,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag("settings:transfer-paste"),
                    )

                    diff?.let { rows ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(
                                text = words.selectChanged,
                                onClick = { chosen = writable.map { it.key }.toSet() },
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                                modifier = Modifier.testTag("settings:transfer-select-changed"),
                            )
                            AppButton(
                                text = words.clearAll,
                                onClick = { chosen = emptySet() },
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                                modifier = Modifier.testTag("settings:transfer-clear"),
                            )
                        }
                        Text(
                            text = words.selected(chosen.size, writable.size),
                            style = tokens.footnote,
                            color = tokens.muted,
                            modifier = Modifier.testTag("settings:transfer-selected"),
                        )
                        rows.forEachIndexed { index, row ->
                            if (index > 0) RowDivider()
                            DiffLine(
                                row = row,
                                words = words,
                                picked = row.key in chosen,
                                pickable = row.status == "changed" && !row.locked,
                                onToggle = { chosen = if (row.key in chosen) chosen - row.key else chosen + row.key },
                            )
                        }
                        AppButton(
                            text = words.apply(chosen.size),
                            onClick = {
                                working = true
                                coroutines.launch {
                                    runCatching {
                                        scope.call(
                                            "settings.transfer",
                                            buildJsonObject {
                                                put("settings", JsonArray(entries.orEmpty()))
                                                put("keys", buildJsonArray { chosen.forEach { add(it) } })
                                            },
                                        )
                                    }.onSuccess { answer ->
                                        // The report stays on the screen, as the web's does: what was
                                        // applied, what was not, and why not.
                                        results = resultRowsOf(answer)
                                        diff = null
                                    }.onFailure { problem = it.message }
                                    working = false
                                }
                            },
                            enabled = chosen.isNotEmpty() && !working && !scope.busy,
                            size = ButtonSize.Sm,
                            modifier = Modifier.testTag("settings:transfer-apply"),
                        )
                    }

                    results?.let { report ->
                        report.forEach { row ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("settings:transfer-result-${row.key}")) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = row.key, style = tokens.label.copy(fontWeight = FontWeight.Medium), color = tokens.foreground)
                                    row.error?.let { Text(text = it, style = tokens.footnote, color = tokens.muted) }
                                }
                                Badge(
                                    text = when (row.status) {
                                        "applied" -> words.applied
                                        "skipped" -> words.skipped
                                        else -> words.failed
                                    },
                                    tone = if (row.status == "applied") Tones.SUCCESS else Tones.WARNING,
                                )
                            }
                        }
                        AppButton(
                            text = words.close,
                            onClick = scope.close,
                            size = ButtonSize.Sm,
                            modifier = Modifier.testTag("settings:transfer-close"),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One key of the difference: what it is now, what the file would make it, and a tick when it may be. */
@Composable
private fun DiffLine(row: DiffRow, words: SettingsWords, picked: Boolean, pickable: Boolean, onToggle: () -> Unit) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (pickable) it.clickable(role = Role.Checkbox, onClick = onToggle) else it }
            .semantics { selected = picked }
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .testTag("settings:transfer-row-${row.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = row.key, style = tokens.label.copy(fontWeight = FontWeight.Medium), color = tokens.foreground)
                Badge(
                    text = when {
                        row.locked -> words.locked
                        row.status == "changed" -> words.changed
                        row.status == "unchanged" -> words.unchanged
                        else -> words.notFound
                    },
                    tone = when {
                        row.locked -> Tones.WARNING
                        row.status == "changed" -> Tones.INFO
                        row.status == "unchanged" -> null
                        else -> Tones.DANGER
                    },
                )
            }
            if (row.status != "unchanged") {
                Text(
                    text = "${words.current}: ${if (row.secret) "••••••••" else shortJson(row.current, limit = 40)}",
                    style = tokens.footnote,
                    color = tokens.muted,
                )
                Text(
                    text = "${words.incoming}: ${shortJson(row.incoming, limit = 40)}",
                    style = tokens.footnote,
                    color = tokens.muted,
                )
            }
        }
        if (pickable && picked) CheckIcon(tokens.foreground)
    }
}
