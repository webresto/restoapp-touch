package cx.m42.restoapp.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Field
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.WindowClass
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * A form by the schema of a method (`control: {type: "form"}`): its fields, what has been typed into them,
 * and its two buttons — which are drawn where the page puts them (03-render.md): in the header wider than
 * a phone, in the `ActionBar` on a phone, in the footer of a dialog. Hence the state apart from the fields.
 *
 * As declarative as everything else here — the fields are the schema's, the words are the server's, and
 * what comes back is a plain map of values. A property this version cannot draw (a nested object, an
 * array) is left out rather than guessed at.
 */

/** One field of a form: what the schema says it is, and what the person has typed. */
public data class FormField(
    val name: String,
    val title: String,
    /** `string`, `integer`, `number`, `boolean`, or an `enum` when [values] is not empty. */
    val type: String,
    val required: Boolean,
    val values: List<String> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    /** `phone`, `money`, `datetime` — a hint, not a type. */
    val format: String? = null,
    /** The schema's `default`: what the form starts with — the current value when a record's form edits it. */
    val initial: String? = null,
)

/** What has been typed into a form of [fields], and what it adds up to. */
internal class FormState(val fields: List<FormField>, val tag: String) {
    val values = mutableStateMapOf<String, String>().apply {
        fields.forEach { field -> if (field.type != "boolean") field.initial?.let { put(field.name, it) } }
    }
    val flags = mutableStateMapOf<String, Boolean>().apply {
        fields.forEach { field -> if (field.type == "boolean" && field.initial == "true") put(field.name, true) }
    }

    /** Everything `required` is there: what the button waits for — the server is not asked half a form. */
    val filled: Boolean
        get() = fields.none { field -> field.required && field.type != "boolean" && values[field.name].isNullOrBlank() }

    /** Changed against what it started with: a form of a record starts filled in and is not "unsaved" until edited. */
    val dirty: Boolean
        get() = fields.any { field ->
            if (field.type == "boolean") (flags[field.name] == true) != (field.initial == "true")
            else values[field.name].orEmpty() != field.initial.orEmpty()
        }

    /** The values as the method takes them. */
    fun collect(): Map<String, JsonElement> = fields.mapNotNull { field ->
        when {
            field.type == "boolean" -> field.name to JsonPrimitive(flags[field.name] == true)
            else -> values[field.name]?.takeIf { it.isNotBlank() }?.let { typed ->
                field.name to when (field.type) {
                    "integer" -> JsonPrimitive(typed.trim().toLongOrNull() ?: 0L)
                    "number" -> JsonPrimitive(typed.trim().replace(',', '.').toDoubleOrNull() ?: 0.0)
                    else -> JsonPrimitive(typed.trim())
                }
            }
        }
    }.toMap()
}

/**
 * The fields of [form], each under its caption: a line to type in, a `Select` for a choice, a switch.
 * Short fields go two to a line wider than a phone. Tagged `<tag>:<name>`, a choice's options
 * `<tag>:<name>-<value>`.
 */
@Composable
internal fun FormFields(form: FormState, busy: Boolean, words: RenderWords) {
    val tag = form.tag
    val phone = LocalWindowClass.current == WindowClass.Compact

    @Composable
    fun one(field: FormField, modifier: Modifier) {
        val caption = if (field.required) field.title else "${field.title} · ${words.optional}"
        when {
            field.type == "boolean" -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                Text(field.title, modifier = Modifier.weight(1f))
                SwitchControl(
                    checked = form.flags[field.name] == true,
                    label = field.title,
                    enabled = !busy,
                    onChange = { form.flags[field.name] = it },
                    tag = "$tag:${field.name}",
                )
            }
            field.values.isNotEmpty() -> Field(label = caption, modifier = modifier) {
                TaggedSelect(
                    choices = field.values.map { PickChoice(it, field.titles[it] ?: it, null) },
                    selected = form.values[field.name],
                    title = field.title,
                    placeholder = words.choose,
                    onPick = { form.values[field.name] = it },
                    tag = "$tag:${field.name}",
                    itemTag = { "$tag:${field.name}-$it" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> Field(label = caption, modifier = modifier) {
                Input(
                    value = form.values[field.name].orEmpty(),
                    onValueChange = { form.values[field.name] = it },
                    label = field.title,
                    keyboardOptions = keyboardFor(field),
                    modifier = Modifier.testTag("$tag:${field.name}"),
                )
            }
        }
    }
    // A switch and a choice take the whole line.
    fun short(field: FormField) = field.type != "boolean" && field.values.isEmpty()
    val lines = if (phone) form.fields.map { listOf(it) } else form.fields.fold(mutableListOf<MutableList<FormField>>()) { acc, field ->
        val last = acc.lastOrNull()
        if (short(field) && last != null && last.size == 1 && short(last[0])) last += field else acc += mutableListOf(field)
        acc
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        lines.forEach { line ->
            if (line.size == 1) {
                one(line[0], Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    line.forEach { one(it, Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * «Отмена» and the form's own button, tagged `<tag>:cancel` and `<tag>:submit`: in a header or an
 * `ActionBar` side by side, the main one last; [fill] shares the width of a phone's bar between them.
 */
@Composable
internal fun FormButtons(form: FormState, submit: String, busy: Boolean, words: RenderWords, onSubmit: () -> Unit, onCancel: () -> Unit, fill: Boolean) {
    Row(
        if (fill) Modifier.fillMaxWidth() else Modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            text = words.cancel,
            onClick = onCancel,
            enabled = !busy,
            variant = ButtonVariant.Outline,
            modifier = (if (fill) Modifier.weight(1f) else Modifier).testTag("${form.tag}:cancel"),
        )
        Button(
            text = submit,
            onClick = onSubmit,
            enabled = !busy && form.filled,
            loading = busy,
            modifier = (if (fill) Modifier.weight(1f) else Modifier).testTag("${form.tag}:submit"),
        )
    }
}

/** The keyboard a field wants: digits for a number, the phone pad for a phone, mail for an e-mail. */
internal fun keyboardFor(field: FormField): KeyboardOptions {
    val type = when {
        field.type == "integer" -> KeyboardType.Number
        field.type == "number" || field.format == "money" || field.format == "credits" -> KeyboardType.Decimal
        field.format == "phone" -> KeyboardType.Phone
        field.format == "email" -> KeyboardType.Email
        else -> KeyboardType.Text
    }
    return KeyboardOptions(keyboardType = type)
}
