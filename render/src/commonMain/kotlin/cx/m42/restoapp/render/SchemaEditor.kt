package cx.m42.restoapp.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.theme.AppTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Text as KitText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Check

/**
 * The form of one value, by the schema the value came with ([SchemaNode]).
 *
 * Every field here edits a [JsonElement] and hands a [JsonElement] back, so the whole form is one
 * value and one callback however deep it goes: an object of arrays of objects is the same three
 * functions calling each other. The rules of what may be typed are [problemOf]'s, the screen's
 * «Сохранить» asks it, and the server asks Ajv — in that order and with the last word at the end.
 *
 * A choice of up to [SchemaNode.CHIPS_LIMIT] values is drawn as chips; a bigger one — `TZ` has 459
 * and the countries 238 — opens a list with a search over it, because a list of 459 that cannot be
 * searched is a list that cannot be used. (The web opens a dropdown there; a sheet over the screen
 * would hide the form a nested field belongs to, so the list expands in place.)
 */
@Composable
public fun SchemaEditor(
    node: SchemaNode,
    value: JsonElement?,
    label: String?,
    note: String?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    Labelled(label, note ?: node.note, tag) {
        when (node) {
            is SchemaNode.Text -> TextEditor(node, value, enabled, words, onChange, tag)
            is SchemaNode.Num -> NumberEditor(node, value, enabled, words, onChange, tag)
            is SchemaNode.Flag -> FlagEditor(value, enabled, words, onChange, tag)
            is SchemaNode.Choice -> ChoiceEditor(node, value, enabled, words, onChange, tag)
            is SchemaNode.Multi -> MultiEditor(node, value, enabled, words, onChange, tag)
            is SchemaNode.Items -> ItemsEditor(node, value, enabled, words, onChange, tag)
            is SchemaNode.Fields -> FieldsEditor(node, value, enabled, words, onChange, tag)
            is SchemaNode.Keyed -> KeyedEditor(node, value, enabled, words, onChange, tag)
            is SchemaNode.Raw -> RawEditor(node, value, enabled, words, onChange, tag)
        }
    }
}

/** A value of this shape with nothing in it: what «+ Добавить» puts in a new item. */
public fun blankOf(node: SchemaNode): JsonElement = when (node) {
    is SchemaNode.Text -> JsonPrimitive("")
    is SchemaNode.Num -> if (node.nullable) JsonNull else JsonPrimitive(0)
    is SchemaNode.Flag -> JsonPrimitive(false)
    is SchemaNode.Choice -> node.values.firstOrNull() ?: JsonNull
    is SchemaNode.Multi -> JsonArray(emptyList())
    is SchemaNode.Items -> JsonArray(emptyList())
    // Only what the schema demands: an optional property an operator has not filled in should not
    // appear in the value just because a form was opened on it.
    is SchemaNode.Fields -> JsonObject(
        node.properties.filter { it.name in node.required }.associate { it.name to blankOf(it.node) },
    )
    is SchemaNode.Keyed -> JsonObject(emptyMap())
    is SchemaNode.Raw -> JsonObject(emptyMap())
}

private fun keyOf(value: JsonElement): String = if (value is JsonNull) "null" else plainOf(value) ?: "null"

@Composable
private fun Labelled(label: String?, note: String?, tag: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!label.isNullOrBlank()) {
            KitText(text = label, variant = TextVariant.Small, modifier = Modifier.testTag("$tag:label"))
        }
        content()
        if (!note.isNullOrBlank()) {
            KitText(text = note, variant = TextVariant.Muted, modifier = Modifier.testTag("$tag:note"))
        }
    }
}

@Composable
private fun TextEditor(
    node: SchemaNode.Text,
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    if (node.secret) {
        // The stored value never arrives (it is a secret), so the field starts empty and what is
        // typed replaces it. Empty means «leave it as it was» — the screen then has nothing to save.
        var typed by remember(tag) { mutableStateOf("") }
        SecretField(
            value = typed,
            onChange = {
                typed = it
                onChange(if (it.isEmpty()) null else JsonPrimitive(it))
            },
            placeholder = "••••••••",
            enabled = enabled,
            tag = tag,
        )
        return
    }
    val text = plainOf(value).orEmpty()
    Input(
        value = text,
        onValueChange = { typed ->
            val cut = node.maxLength?.let { typed.take(it + 1) } ?: typed
            onChange(if (cut.isEmpty() && node.nullable) JsonNull else JsonPrimitive(cut))
        },
        placeholder = if (node.nullable) words.notSet else "",
        // A value of a setting is as often a line as a paragraph (a description, a list of origins):
        // a short one keeps one line, a long one grows, which is the web's textarea.
        singleLine = (node.maxLength ?: Int.MAX_VALUE) <= SHORT_TEXT,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

private const val SHORT_TEXT = 120

@Composable
private fun NumberEditor(
    node: SchemaNode.Num,
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    var typed by remember(tag) { mutableStateOf(plainOf(value).orEmpty()) }
    // A value the screen changed from outside — «К умолчанию» — must reach the field; one the field
    // itself produced must not come back rounded, or a half-typed «12.» would lose its dot.
    LaunchedEffect(value) {
        val shown = plainOf(value).orEmpty()
        if (shown.toDoubleOrNull() != typed.toDoubleOrNull()) typed = shown
    }
    Input(
        value = typed,
        onValueChange = { text ->
            typed = text.filter { it.isDigit() || it == '-' || it == '.' || it == ',' }.replace(',', '.')
            val number = typed.toDoubleOrNull()
            onChange(
                when {
                    typed.isEmpty() -> JsonNull
                    number == null -> JsonPrimitive(typed) // a string where a number belongs: `problemOf` says so
                    node.integer -> JsonPrimitive(number.toLong())
                    else -> JsonPrimitive(number)
                },
            )
        },
        placeholder = if (node.nullable) words.notSet else "",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Two buttons, «Да» and «Нет» — the two radio buttons of the web page. */
@Composable
private fun FlagEditor(
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    val on = (value as? JsonPrimitive)?.booleanOrNull
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(true, false).forEach { flag ->
            Button(
                text = if (flag) words.yes else words.no,
                onClick = { onChange(JsonPrimitive(flag)) },
                enabled = enabled,
                variant = if (on == flag) ButtonVariant.Default else ButtonVariant.Outline,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$tag-$flag"),
            )
        }
    }
}

@Composable
private fun ChoiceEditor(
    node: SchemaNode.Choice,
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    if (!node.big) {
        TagChips(
            values = node.values,
            titles = node.titles,
            chosen = setOfNotNull(value?.let(::keyOf)),
            enabled = enabled,
            words = words,
            onToggle = { picked -> onChange(picked) },
            tag = tag,
        )
        return
    }
    SearchableList(
        values = node.values,
        titles = node.titles,
        chosen = setOfNotNull(value?.let(::keyOf)),
        current = value?.let { titleOf(it, node.titles, words) },
        enabled = enabled,
        words = words,
        onPick = { picked -> onChange(picked) },
        tag = tag,
    )
}

@Composable
private fun MultiEditor(
    node: SchemaNode.Multi,
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    val chosen = (value as? JsonArray).orEmpty()
    val keys = chosen.map(::keyOf).toSet()
    val toggle = { picked: JsonElement ->
        val next = if (keyOf(picked) in keys) chosen.filter { keyOf(it) != keyOf(picked) } else chosen + picked
        onChange(JsonArray(next))
    }
    if (!node.big) {
        TagChips(node.values, node.titles, keys, enabled, words, toggle, tag)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (chosen.isNotEmpty()) {
            TagChips(chosen, node.titles, keys, enabled, words, toggle, "$tag-chosen")
        }
        SearchableList(
            values = node.values,
            titles = node.titles,
            chosen = keys,
            current = null,
            enabled = enabled,
            words = words,
            onPick = toggle,
            keepOpen = true,
            tag = tag,
        )
    }
}

/** Chips that toggle: one of them for a choice, any number of them for a multiple one. */
@Composable
private fun TagChips(
    values: List<JsonElement>,
    titles: Map<String, String>,
    chosen: Set<String>,
    enabled: Boolean,
    words: SchemaWords,
    onToggle: (JsonElement) -> Unit,
    tag: String,
) {
    val tokens = AppTheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.chunked(CHIPS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    val key = keyOf(item)
                    val isOn = key in chosen
                    Text(
                        text = titleOf(item, titles, words),
                        style = tokens.label.copy(fontWeight = FontWeight.Medium),
                        color = if (isOn) tokens.primaryForeground else tokens.foreground,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isOn) tokens.primary else Kit.colors.card)
                            .border(1.dp, if (isOn) tokens.primary else Kit.colors.border, RoundedCornerShape(50))
                            .clickable(enabled = enabled, role = Role.Tab) { onToggle(item) }
                            .semantics { selected = isOn }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .testTag("$tag-$key"),
                    )
                }
            }
        }
    }
}

private const val CHIPS_PER_ROW = 3

/**
 * A list of values with a search over it: what 459 time zones need and chips cannot give.
 *
 * Closed, it shows what is chosen and a button; open, a field to type in and the matches under it.
 * [keepOpen] is for a multiple choice, where picking one value is rarely the last thing done.
 */
@Composable
private fun SearchableList(
    values: List<JsonElement>,
    titles: Map<String, String>,
    chosen: Set<String>,
    current: String?,
    enabled: Boolean,
    words: SchemaWords,
    onPick: (JsonElement) -> Unit,
    tag: String,
    keepOpen: Boolean = false,
) {
    val tokens = AppTheme
    var open by remember(tag) { mutableStateOf(false) }
    var query by remember(tag) { mutableStateOf("") }

    if (!open) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = current ?: words.notSet,
                style = tokens.body,
                color = tokens.foreground,
                modifier = Modifier.weight(1f).testTag("$tag:current"),
            )
            Button(
                text = words.choose,
                onClick = { open = true },
                enabled = enabled,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$tag:open"),
            )
        }
        return
    }

    val matching = values.filter { item ->
        query.isBlank() || titleOf(item, titles, words).contains(query.trim(), ignoreCase = true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Input(
            value = query,
            onValueChange = { query = it },
            placeholder = words.choose,
            modifier = Modifier.fillMaxWidth().testTag("$tag:search"),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(tokens.radius))
                .background(tokens.surface)
                .verticalScroll(rememberScrollState())
                .testTag("$tag:list"),
        ) {
            if (matching.isEmpty()) {
                Text(text = words.notSet, style = tokens.label, color = tokens.muted, modifier = Modifier.padding(12.dp))
            }
            matching.forEach { item ->
                val key = keyOf(item)
                val isOn = key in chosen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled, role = Role.Button) {
                            onPick(item)
                            if (!keepOpen) open = false
                        }
                        .semantics {
                            selected = isOn
                            contentDescription = titleOf(item, titles, words)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("$tag-$key"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = titleOf(item, titles, words),
                        style = if (isOn) tokens.body.copy(fontWeight = FontWeight.SemiBold) else tokens.body,
                        color = tokens.foreground,
                        modifier = Modifier.weight(1f),
                    )
                    if (isOn) Icon(Lucide.Check, contentDescription = null, tint = tokens.foreground)
                }
            }
        }
        Button(
            text = words.cancelChoosing,
            onClick = { open = false },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("$tag:close"),
        )
    }
}

@Composable
private fun ItemsEditor(
    node: SchemaNode.Items,
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    val items = (value as? JsonArray).orEmpty()
    val replace = { index: Int, child: JsonElement? ->
        onChange(JsonArray(items.mapIndexed { at, old -> if (at == index) child ?: JsonNull else old }))
    }
    val group = node.item is SchemaNode.Fields || node.item is SchemaNode.Items || node.item is SchemaNode.Keyed
    Column(verticalArrangement = Arrangement.spacedBy(if (group) 10.dp else 6.dp)) {
        items.forEachIndexed { index, item ->
            if (group) {
                // An item that is a form of its own gets a card and a «Удалить» of its own: that is
                // how `WORK_TIME` reads — one card per rule of the hours.
                Card(tag = "$tag-$index") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${words.element} ${index + 1}",
                            style = AppTheme.footnote.copy(fontWeight = FontWeight.SemiBold),
                            color = AppTheme.muted,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            text = words.remove,
                            onClick = { onChange(JsonArray(items.filterIndexed { at, _ -> at != index })) },
                            enabled = enabled,
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            modifier = Modifier.testTag("$tag-$index:remove"),
                        )
                    }
                    SchemaEditor(
                        node = node.item,
                        value = item,
                        label = null,
                        note = null,
                        enabled = enabled,
                        words = words,
                        onChange = { child -> replace(index, child) },
                        tag = "$tag-$index",
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        SchemaEditor(
                            node = node.item,
                            value = item,
                            label = null,
                            note = null,
                            enabled = enabled,
                            words = words,
                            onChange = { child -> replace(index, child) },
                            tag = "$tag-$index",
                        )
                    }
                    Button(
                        text = "−",
                        onClick = { onChange(JsonArray(items.filterIndexed { at, _ -> at != index })) },
                        enabled = enabled,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag("$tag-$index:remove"),
                    )
                }
            }
        }
        Button(
            text = "+ ${words.add}",
            onClick = { onChange(JsonArray(items + blankOf(node.item))) },
            enabled = enabled,
            variant = ButtonVariant.Outline,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("$tag:add"),
        )
    }
}

@Composable
private fun FieldsEditor(
    node: SchemaNode.Fields,
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    val fields = (value as? JsonObject) ?: JsonObject(emptyMap())
    val put = { name: String, child: JsonElement? ->
        onChange(JsonObject(if (child == null) fields - name else fields + (name to child)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        node.properties.forEach { property ->
            // The name of the property is the label: no schema of a setting of this server carries a
            // `title` inside it, and the web's own JSON editor shows these very keys.
            SchemaEditor(
                node = property.node,
                value = fields[property.name],
                label = property.name + if (property.name in node.required) " *" else "",
                note = null,
                enabled = enabled,
                words = words,
                onChange = { child -> put(property.name, child) },
                tag = "$tag.${property.name}",
            )
        }
        if (node.loose) {
            // `additionalProperties: true`: whatever the schema does not describe is still part of the
            // operator's value, so it is shown as JSON rather than quietly dropped on the next save.
            val described = node.properties.map { it.name }.toSet()
            val tail = JsonObject(fields.filterKeys { it !in described })
            RawEditorLabelled(
                label = words.rawTail,
                value = tail,
                enabled = enabled,
                words = words,
                onChange = { parsed ->
                    val kept = (parsed as? JsonObject) ?: JsonObject(emptyMap())
                    onChange(JsonObject(fields.filterKeys { it in described } + kept))
                },
                tag = "$tag:tail",
            )
        }
    }
}

@Composable
private fun KeyedEditor(
    node: SchemaNode.Keyed,
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    val fields = (value as? JsonObject) ?: JsonObject(emptyMap())
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.entries.forEachIndexed { index, (key, child) ->
            Card(tag = "$tag-$index") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        Input(
                            value = key,
                            onValueChange = { typed ->
                                // The key is part of the value: renaming it keeps the order of the rest.
                                onChange(JsonObject(fields.entries.associate { (old, kept) -> (if (old == key) typed else old) to kept }))
                            },
                            placeholder = words.key,
                            modifier = Modifier.fillMaxWidth().testTag("$tag-$index:key"),
                        )
                    }
                    Button(
                        text = words.remove,
                        onClick = { onChange(JsonObject(fields - key)) },
                        enabled = enabled,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag("$tag-$index:remove"),
                    )
                }
                SchemaEditor(
                    node = node.value,
                    value = child,
                    label = null,
                    note = null,
                    enabled = enabled,
                    words = words,
                    onChange = { updated -> onChange(JsonObject(fields + (key to (updated ?: JsonNull)))) },
                    tag = "$tag.$key",
                )
            }
        }
        Button(
            text = "+ ${words.addKey}",
            onClick = {
                val name = generateSequence(1) { it + 1 }.first { "${words.key} $it" !in fields.keys }
                onChange(JsonObject(fields + ("${words.key} $name" to blankOf(node.value))))
            },
            enabled = enabled,
            variant = ButtonVariant.Outline,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("$tag:add"),
        )
    }
}

@Composable
private fun RawEditor(
    node: SchemaNode.Raw,
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    if (!node.json) {
        TextEditor(SchemaNode.Text(), value, enabled, words, onChange, tag)
        return
    }
    RawEditorLabelled(label = null, value = value, enabled = enabled, words = words, onChange = onChange, tag = tag)
}

/**
 * The value as JSON text: the honest fallback for a schema this form does not draw, and the tail of
 * a loose object.
 *
 * Text that does not parse is handed on as a string, which is the one thing a JSON value of a
 * setting cannot be — so [problemOf] says «this is not JSON» and «Сохранить» stays shut, without
 * this field needing a channel of its own to report it.
 */
@Composable
private fun RawEditorLabelled(
    label: String?,
    value: JsonElement?,
    enabled: Boolean,
    words: SchemaWords,
    onChange: (JsonElement?) -> Unit,
    tag: String,
) {
    var text by remember(tag) { mutableStateOf(prettyJson(value)) }
    LaunchedEffect(value) {
        // A value changed from outside — «К умолчанию» — replaces the text, but what this field
        // itself parsed into that value must not be reformatted under the cursor.
        if (parseJsonOrString(text) != value) text = prettyJson(value)
    }
    Labelled(label, null, tag) {
        Input(
            value = text,
            onValueChange = {
                text = it
                onChange(parseJsonOrString(it))
            },
            placeholder = "{ }",
            singleLine = false,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            minLines = 3,
        )
    }
}

@Composable
private fun Card(tag: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Kit.shapes.lg)
            .background(Kit.colors.card)
            .border(1.dp, Kit.colors.border, Kit.shapes.lg)
            .padding(12.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

/** A field that shows dots instead of what is typed: a secret is written, never read. */
@Composable
public fun SecretField(value: String, onChange: (String) -> Unit, placeholder: String, enabled: Boolean, tag: String) {
    Input(
        value = value,
        onValueChange = onChange,
        placeholder = placeholder,
        enabled = enabled,
        password = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.testTag(tag),
    )
}
