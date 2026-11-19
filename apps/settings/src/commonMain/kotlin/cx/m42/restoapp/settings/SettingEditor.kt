package cx.m42.restoapp.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.TriangleAlert
import cx.m42.restoapp.render.HoldUnsaved
import cx.m42.restoapp.render.NativeActionScope
import cx.m42.restoapp.render.SchemaEditor
import cx.m42.restoapp.render.SchemaNode
import cx.m42.restoapp.render.SchemaWords
import cx.m42.restoapp.render.numberText
import cx.m42.restoapp.render.plainOf
import cx.m42.restoapp.render.prettyJson
import cx.m42.restoapp.render.problemOf
import cx.m42.restoapp.render.schemaNodeOf
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.Dialog
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Notice
import cx.m42.superizer.kit.Pill
import cx.m42.superizer.kit.Separator
import cx.m42.superizer.kit.StatusBadge
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The editor of one setting's value — the screen `control: {type: "native", screen: "setting-editor"}`
 * asks for (`settings.plan.md`).
 *
 * It is here, and not in the declaration, for one reason: the form is built from a JSON Schema that
 * arrives *with the record*, and every setting of a server has one of its own. Everything else about
 * a setting stays declared — the row, the badges, the card, the search — and this screen only builds
 * the value of one action and sends it.
 *
 * What it refuses, it refuses for the reason the server would (`problemOf`, the same rules core's Ajv
 * runs): «Сохранить» stays shut while the value breaks one, and the rule is said in words under the
 * field rather than after a round trip. What the server refuses anyway is shown as the server said it
 * — it has the last word, and its sentence is already in the language of the request.
 */

/**
 * The keys whose new value only takes effect when the server comes back up, and the run of the server
 * they were written in.
 *
 * The web page keeps this in `sessionStorage` against the page's `bootId`; this keeps it in memory
 * against the `boot` of the record, which comes with every card. A different `boot` means the server
 * has restarted and the reminder is spent. It does not outlive a restart of the *app* — for that it
 * would have to reach the server's own storage, which a screen of an action has no door to yet.
 */
internal object RestartPending {
    private val keysByBoot = mutableMapOf<String, MutableSet<String>>()

    fun mark(boot: String, key: String) {
        if (boot.isBlank()) return
        keysByBoot.getOrPut(boot) { mutableSetOf() }.add(key)
        // Another run of the server is another world: what it was waiting for has been applied.
        keysByBoot.keys.filter { it != boot }.forEach(keysByBoot::remove)
    }

    fun waiting(boot: String, key: String): Boolean = keysByBoot[boot]?.contains(key) == true

    /** For the tests: a fresh client has nothing waiting. */
    fun clear() {
        keysByBoot.clear()
    }
}

internal fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.textOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true

internal fun JsonObject.element(name: String): JsonElement? = this[name]?.takeIf { it !is JsonNull }

/** A value on one line: a scalar as it is, anything composite as the JSON it is. */
internal fun shortJson(value: JsonElement?, limit: Int = 80): String {
    val text = when (value) {
        null, JsonNull -> "—"
        is JsonPrimitive -> plainOf(value) ?: "—"
        else -> prettyJson(value).lineSequence().joinToString(" ") { it.trim() }
    }
    return if (text.length <= limit) text else text.take(limit - 1) + "…"
}

/** What the field says about itself under it: the manifest's hint, the schema's words, or its bounds. */
private fun hintOf(node: SchemaNode, patternHint: String?, words: SchemaWords): String? = when {
    // The hint as the manifest wrote it: the sentence «Должно содержать только: …» belongs to the
    // refusal, and saying it twice over one field says nothing twice.
    patternHint != null -> patternHint
    node.note != null -> node.note
    node is SchemaNode.Num -> words.between(node.min?.let(::numberText), node.max?.let(::numberText))
    node is SchemaNode.Text -> node.minLength?.let(words::atLeastChars)
    else -> null
}

@Composable
internal fun SettingEditor(scope: NativeActionScope) {
    val words = remember(scope.lang) { SettingsWords(scope.lang) }
    val schemaWords = remember(scope.lang) { SchemaWords(scope.lang) }
    val record = scope.record

    val key = record.text("key")
    val secret = record.flag("secret")
    val patternHint = record.textOrNull("patternHint")
    val node = remember(record) { schemaNodeOf(record["jsonSchema"], record.text("type"), patternHint, secret) }
    val stored = record.element("value")
    val default = record.element("defaultValue")

    var edited by remember(record) { mutableStateOf(stored) }
    var touched by remember(record) { mutableStateOf(false) }

    val problem = problemOf(node, edited, schemaWords)
    // A secret with an empty field means «leave the stored value alone»: there is nothing to save.
    val nothingTyped = secret && edited == null
    val dirty = touched && edited != stored
    val canSave = !scope.busy && problem == null && !nothingTyped
    val compact = LocalWindowClass.current == WindowClass.Compact

    fun save(saved: () -> Unit) {
        scope.submitThen(buildJsonObject { put("id", key); put("value", edited ?: JsonNull) }) {
            // Only once the server has taken it: a value that only applies at boot leaves a
            // reminder on the screen underneath, and the key waits for this run to end.
            if (record.flag("restartRequired")) {
                RestartPending.mark(record.text("boot"), key)
                scope.notice(words.restartNeeded)
            }
            saved()
        }
    }
    // What is wrong with the value is on the screen already, under the field it is about.
    HoldUnsaved(dirty, scope.hold) { saved -> if (canSave) save(saved) }

    // Over the card it was opened from: a centred dialog in a wide window, a sheet from the bottom on a
    // phone — the kit decides. The dim, Esc and back go the way «Отмена» does, through the question.
    Dialog(
        onDismiss = scope.leave,
        title = record.textOrNull("name") ?: key,
        width = 520.dp,
        closeLabel = words.close,
        footer = {
            val saveButton = @Composable { modifier: Modifier ->
                Button(
                    text = if (scope.busy) words.saving else words.save,
                    onClick = { save {} },
                    enabled = canSave,
                    loading = scope.busy,
                    size = if (compact) ButtonSize.Lg else ButtonSize.Default,
                    modifier = modifier.testTag("settings:editor-save"),
                )
            }
            if (compact) {
                // The answer on top, where the thumb is — as the kit's `DialogActions` stacks them.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    saveButton(Modifier.fillMaxWidth())
                    Button(
                        words.cancel,
                        scope.leave,
                        Modifier.fillMaxWidth().testTag("settings:editor-cancel"),
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Lg,
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    Button(words.cancel, scope.leave, Modifier.testTag("settings:editor-cancel"), variant = ButtonVariant.Outline)
                    saveButton(Modifier)
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag("settings:editor"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    key,
                    Modifier.weight(1f, fill = false),
                    style = Kit.textStyles.monoSmall,
                    color = Kit.colors.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    dirty -> StatusBadge(words.unsaved, Tone.Warn, Modifier.testTag("settings:editor-unsaved"))
                    RestartPending.waiting(record.text("boot"), key) -> Pill(words.waitsRestart, Tone.Warn)
                    secret -> Pill(words.secret, Tone.Neutral, icon = Lucide.Lock)
                }
            }
            record.textOrNull("description")?.let { description ->
                Text(description, variant = TextVariant.Muted, modifier = Modifier.testTag("settings:editor-description"))
            }

            // What cannot be changed here, and why — the red plate of the web page, word for word.
            record.element("envOverride")?.let { env ->
                Notice(Tone.Danger, Lucide.Lock, words.pinned, words.envPinned, Modifier.testTag("settings:editor-env"))
                if ((env as? JsonObject)?.flag("valid") == false) {
                    Notice(Tone.Danger, Lucide.TriangleAlert, words.pinned, words.envInvalid, Modifier.testTag("settings:editor-env-invalid"))
                }
            }
            if (record.flag("readOnly")) {
                Notice(Tone.Warn, Lucide.Lock, words.appOwned, words.readOnly, Modifier.testTag("settings:editor-readonly"))
            }
            if (RestartPending.waiting(record.text("boot"), key)) {
                Notice(Tone.Warn, Lucide.RotateCw, words.restartTitle, words.restartPending, Modifier.testTag("settings:editor-restart"))
            }
            // What the server said when it refused anyway: its words, and the last word.
            scope.failure?.let {
                Notice(Tone.Danger, Lucide.CircleAlert, words.serverRefused, it, Modifier.testTag("settings:editor-refusal"))
            }

            Separator()
            Column(
                modifier = if (secret) Modifier.testTag("settings:editor-secret") else Modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(words.value, style = Kit.textStyles.label)
                    if (secret) {
                        Text(if (record.flag("hasValue")) words.secretStored else words.notSetYet, variant = TextVariant.Muted)
                    }
                }
                SchemaEditor(
                    node = node,
                    value = edited,
                    label = null,
                    note = hintOf(node, patternHint, schemaWords),
                    enabled = !scope.busy,
                    words = schemaWords,
                    onChange = {
                        edited = it
                        touched = true
                    },
                    tag = "settings:field",
                )

                // The reason it cannot be saved yet, as the web says it under its own editor.
                problem?.takeIf { touched }?.let {
                    Row(
                        Modifier.testTag("settings:editor-problem"),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Lucide.CircleAlert, null, Modifier.padding(top = 2.dp), size = 15.dp, tint = Tone.Danger.textColor())
                        Text(it, variant = TextVariant.Small, color = Tone.Danger.textColor())
                    }
                }

                if (default != null && !secret) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = words.defaultIs(shortJson(default)),
                            variant = TextVariant.Muted,
                            modifier = Modifier.weight(1f).testTag("settings:editor-default-value"),
                        )
                        if (edited != default) {
                            Button(
                                text = words.toDefault,
                                onClick = {
                                    // Locally, as the web does it: the write still goes through «Сохранить».
                                    edited = default
                                    touched = true
                                },
                                enabled = !scope.busy,
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                                leadingIcon = Lucide.RotateCcw,
                                modifier = Modifier.testTag("settings:editor-default"),
                            )
                        }
                    }
                }
                if (record.flag("isRequired")) {
                    Text(words.required, variant = TextVariant.Muted, modifier = Modifier.testTag("settings:editor-required"))
                }
                if (record.flag("restartRequired")) {
                    Row(
                        Modifier.testTag("settings:editor-restart-hint"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Lucide.RotateCw, null, size = 14.dp, tint = Tone.Warn.textColor())
                        Text(words.restartNeeded, style = Kit.textStyles.caption, color = Tone.Warn.textColor())
                    }
                }
                if (nothingTyped) {
                    Text(words.secretEmpty, variant = TextVariant.Muted, modifier = Modifier.testTag("settings:editor-secret-hint"))
                }
            }
        }
    }
}
