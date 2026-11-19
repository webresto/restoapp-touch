package cx.m42.restoapp.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import cx.m42.restoapp.render.HoldUnsaved
import cx.m42.restoapp.render.NativeActionScope
import cx.m42.restoapp.render.prettyJson
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Checkbox
import cx.m42.superizer.kit.EmptyState
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LineTabs
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Notice
import cx.m42.superizer.kit.Pill
import cx.m42.superizer.kit.Separator
import cx.m42.superizer.kit.SideSheet
import cx.m42.superizer.kit.Skeleton
import cx.m42.superizer.kit.StatusBadge
import cx.m42.superizer.kit.Terminal
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textStyles
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

/** One row of the dump as the export lists it: what it is called, its key, what it holds. */
private data class DumpRow(val key: String, val name: String, val value: JsonElement?, val changed: Boolean)

private fun dumpRowsOf(dump: JsonObject?): List<DumpRow> =
    (dump?.get("settings") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
        DumpRow(it.text("key"), it.textOrNull("name") ?: it.text("key"), it.element("value"), it.element("value") != it.element("defaultValue"))
    }

/** The dump with only the [keys] ticked: the same file, a shorter list, its count told again. */
private fun dumpOf(dump: JsonObject, keys: Set<String>): JsonObject {
    val rows = (dump["settings"] as? JsonArray).orEmpty().filter { (it as? JsonObject)?.text("key") in keys }
    return JsonObject(dump + mapOf("settings" to JsonArray(rows), "count" to JsonPrimitive(rows.size)))
}

@Composable
internal fun SettingTransfer(scope: NativeActionScope) {
    val words = remember(scope.lang) { SettingsWords(scope.lang) }
    val clipboard = LocalClipboardManager.current
    val coroutines = rememberCoroutineScope()

    var dump by remember { mutableStateOf<JsonObject?>(null) }
    var exported by remember { mutableStateOf<Set<String>?>(null) }
    var entries by remember { mutableStateOf<List<JsonObject>?>(null) }
    var diff by remember { mutableStateOf<List<DiffRow>?>(null) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var results by remember { mutableStateOf<List<ResultRow>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }

    // Export is a read, so the screen does it itself: `settings.dump` has no `ui` and is no action.
    LaunchedEffect(Unit) {
        runCatching { scope.call("settings.dump", JsonObject(emptyMap())) }
            .onSuccess { answer ->
                dump = answer as? JsonObject
                exported = dumpRowsOf(dump).map { it.key }.toSet()
            }
            .onFailure { problem = it.message }
    }

    // Nothing is written until the person says so; what would be lost is the preview and its ticks,
    // and those are not something to "save".
    HoldUnsaved(diff != null && results == null, scope.hold, save = null)

    val writable = diff.orEmpty().filter { it.status == "changed" && !it.locked }
    val rows = remember(dump) { dumpRowsOf(dump) }
    val picked = exported.orEmpty()
    val compact = LocalWindowClass.current == WindowClass.Compact

    fun paste() {
        message = null
        problem = null
        results = null
        val pasted = entriesOf(clipboard.getText()?.text.orEmpty())
        if (pasted == null) {
            problem = words.noJson
            return
        }
        entries = pasted
        working = true
        coroutines.launch {
            runCatching {
                scope.call("settings.transfer", buildJsonObject { put("settings", JsonArray(pasted)); put("preview", true) })
            }.onSuccess { answer ->
                diff = diffRowsOf(answer)
                chosen = emptySet()
            }.onFailure { problem = it.message }
            working = false
        }
    }

    fun apply() {
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
                // The report stays on the screen, as the web's does: what was applied, what was not, and why not.
                results = resultRowsOf(answer)
                diff = null
            }.onFailure { problem = it.message }
            working = false
        }
    }

    // Beside the list it was opened from: a panel from the right in a wide window, a sheet from the
    // bottom on a phone. The dim, Esc, back and the cross go through the question, as «Закрыть» does.
    SideSheet(
        onDismiss = scope.leave,
        title = words.transfer,
        closeLabel = words.close,
        description = words.transferHint,
        footer = {
            // The one action of the tab, and the way out; after an import, its report's own two.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                when {
                    tab == 0 -> Button(
                        text = words.copyCount(picked.size),
                        onClick = { copy(dump, picked, clipboard) { message = words.copied } },
                        enabled = dump != null && picked.isNotEmpty(),
                        leadingIcon = Lucide.Copy,
                        modifier = Modifier.weight(1f).testTag("settings:transfer-copy"),
                    )
                    results != null -> {
                        Button(words.close, scope.close, Modifier.weight(1f).testTag("settings:transfer-close"))
                        Button(words.pasteAnother, { results = null }, variant = ButtonVariant.Outline)
                    }
                    diff != null -> Box(Modifier.weight(1f)) {
                        ApplyButton(words, chosen.size, chosen.isNotEmpty() && !working && !scope.busy, working, ::apply, Modifier.fillMaxWidth())
                    }
                }
                if (tab == 0 || results == null) {
                    Button(words.close, scope.leave, Modifier.testTag("settings:transfer-cancel"), variant = ButtonVariant.Outline)
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag("settings:transfer"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LineTabs(listOf(words.export, words.import), tab, { tab = it }, Modifier.testTag("settings:transfer-tabs"))
            message?.let { Notice(Tone.Success, Lucide.Check, it, words.exportCount(picked.size), Modifier.testTag("settings:transfer-message")) }
            problem?.let { Notice(Tone.Danger, Lucide.CircleAlert, words.failed, it, Modifier.testTag("settings:transfer-problem")) }
            scope.failure?.let { Notice(Tone.Danger, Lucide.CircleAlert, words.failed, it, Modifier.testTag("settings:transfer-refusal")) }

            if (tab == 0) {
                val count = (dump?.get("count") as? JsonPrimitive)?.content?.toIntOrNull()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (count == null) "…" else words.selected(picked.size, rows.size),
                        variant = TextVariant.Muted,
                        modifier = Modifier.weight(1f).testTag("settings:transfer-count"),
                    )
                    if (rows.isNotEmpty()) {
                        Button(
                            if (picked.size == rows.size) words.onlyChanged else words.selectAll,
                            { exported = if (picked.size == rows.size) rows.filter { it.changed }.map { it.key }.toSet() else rows.map { it.key }.toSet() },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            modifier = Modifier.testTag("settings:transfer-pick"),
                        )
                    }
                }
                if (dump == null && problem == null) {
                    repeat(4) { Skeleton(Modifier.fillMaxWidth().height(44.dp)) }
                }
                if (rows.isNotEmpty()) {
                    Rows {
                        rows.forEachIndexed { i, row ->
                            if (i > 0) Separator()
                            val on = row.key in picked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .kitClickable(role = Role.Checkbox) { exported = if (on) picked - row.key else picked + row.key }
                                    .semantics { selected = on }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("settings:transfer-export-${row.key}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Checkbox(on, null)
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(row.key, style = Kit.textStyles.monoSmall, color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    shortJson(row.value, limit = 24),
                                    style = Kit.textStyles.caption,
                                    color = Kit.colors.mutedForeground,
                                    maxLines = 1,
                                    modifier = Modifier.widthIn(max = 140.dp),
                                )
                            }
                        }
                    }
                    dump?.let { whole ->
                        val text = remember(whole, picked) { prettyJson(dumpOf(whole, picked)) }
                        Terminal("settings.json", height = 240.dp, modifier = Modifier.testTag("settings:transfer-json")) {
                            Text(text, style = Kit.textStyles.monoSmall)
                        }
                    }
                }
            } else {
                when {
                    results != null -> {
                        Text(words.report, variant = TextVariant.Muted)
                        Rows {
                            results.orEmpty().forEachIndexed { i, row ->
                                if (i > 0) Separator()
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).testTag("settings:transfer-result-${row.key}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(row.key, style = Kit.textStyles.monoSmall.copy(fontWeight = FontWeight.Medium))
                                        row.error?.let { Text(it, variant = TextVariant.Muted) }
                                    }
                                    StatusBadge(
                                        when (row.status) {
                                            "applied" -> words.applied
                                            "skipped" -> words.skipped
                                            else -> words.failed
                                        },
                                        when (row.status) {
                                            "applied" -> Tone.Success
                                            "skipped" -> Tone.Warn
                                            else -> Tone.Danger
                                        },
                                    )
                                }
                            }
                        }
                    }
                    diff == null -> EmptyState(
                        Lucide.ClipboardPaste,
                        words.pasteTitle,
                        words.pasteHint,
                        action = {
                            Button(
                                words.paste,
                                ::paste,
                                enabled = !working && !scope.busy,
                                loading = working,
                                leadingIcon = Lucide.ClipboardPaste,
                                modifier = Modifier.testTag("settings:transfer-paste"),
                            )
                        },
                    )
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                words.selected(chosen.size, writable.size),
                                variant = TextVariant.Muted,
                                modifier = Modifier.weight(1f).testTag("settings:transfer-selected"),
                            )
                            Button(
                                words.selectChanged,
                                { chosen = writable.map { it.key }.toSet() },
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                                modifier = Modifier.testTag("settings:transfer-select-changed"),
                            )
                            Button(
                                words.clearAll,
                                { chosen = emptySet() },
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                                modifier = Modifier.testTag("settings:transfer-clear"),
                            )
                        }
                        Rows {
                            diff.orEmpty().forEachIndexed { i, row ->
                                if (i > 0) Separator()
                                DiffLine(
                                    row = row,
                                    words = words,
                                    picked = row.key in chosen,
                                    pickable = row.status == "changed" && !row.locked,
                                    onToggle = { chosen = if (row.key in chosen) chosen - row.key else chosen + row.key },
                                )
                            }
                        }
                        if (!compact) {
                            Button(words.paste, ::paste, variant = ButtonVariant.Outline, enabled = !working, leadingIcon = Lucide.ClipboardPaste)
                        }
                    }
                }
            }
        }
    }
}

private fun copy(dump: JsonObject?, keys: Set<String>, clipboard: androidx.compose.ui.platform.ClipboardManager, done: () -> Unit) {
    dump ?: return
    clipboard.setText(AnnotatedString(prettyJson(dumpOf(dump, keys))))
    done()
}

@Composable
private fun ApplyButton(words: SettingsWords, count: Int, enabled: Boolean, working: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        text = words.apply(count),
        onClick = onClick,
        enabled = enabled,
        loading = working,
        modifier = modifier.testTag("settings:transfer-apply"),
    )
}

/** Rows in one bordered block, hairlines between them. */
@Composable
private fun Rows(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Kit.colors.card).border(1.dp, Kit.colors.border, RoundedCornerShape(12.dp)),
        content = content,
    )
}

/** One key of the difference: a tick when it may be written, and what it is now → what the file would make it. */
@Composable
private fun DiffLine(row: DiffRow, words: SettingsWords, picked: Boolean, pickable: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (pickable) Modifier.kitClickable(role = Role.Checkbox, onClick = onToggle) else Modifier)
            .semantics { selected = picked }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("settings:transfer-row-${row.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(picked, null, enabled = pickable)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.key, Modifier.weight(1f, fill = false), style = Kit.textStyles.monoSmall.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    row.locked -> Pill(words.locked, Tone.Warn, icon = Lucide.Lock)
                    row.status == "changed" -> Unit
                    row.status == "unchanged" -> Pill(words.unchanged, Tone.Muted)
                    else -> Pill(words.notFound, Tone.Danger)
                }
            }
            if (row.status != "unchanged" && row.status != "not_found") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (row.secret) words.secretShown else shortJson(row.current, limit = 40), style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
                    Text("→", style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
                    Text(
                        shortJson(row.incoming, limit = 40),
                        style = Kit.textStyles.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = if (row.locked) Kit.colors.mutedForeground else Kit.palette.onBrandSoft,
                    )
                }
            } else if (row.status == "not_found") {
                Text(shortJson(row.incoming, limit = 40), style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
            }
        }
    }
}
