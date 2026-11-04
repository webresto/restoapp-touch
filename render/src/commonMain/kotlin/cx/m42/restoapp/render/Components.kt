package cx.m42.restoapp.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.restoapp.protocol.Tones
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.theme.Tokens
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.CheckIcon
import cx.m42.superizer.ui.components.Switch
import cx.m42.superizer.ui.platform.SystemBackHandler

/*
 * The renderer's building blocks (plan section 4, "Задел под JS-приложения"): each takes plain
 * values and callbacks — strings, numbers, tones by name — and no type of the protocol, so the
 * components of script apps (script-apps.plan.md) are these, not a second set.
 */

/** A tone of the contract as a colour of the theme; null — the ordinary text colour. */
internal fun Tokens.toneColor(tone: String?): Color? = when (tone) {
    Tones.DANGER -> danger
    Tones.WARNING -> warning
    Tones.SUCCESS -> success
    Tones.INFO -> accent
    else -> null
}

/** A small rounded label in the tone's colour: an order's state. */
@Composable
public fun Badge(text: String, tone: String?, modifier: Modifier = Modifier) {
    val tokens = AppTheme
    val color = tokens.toneColor(tone) ?: tokens.muted
    Text(
        text = text,
        style = tokens.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * One record in a list: title and subtitle on the left, a value (or the row's controls) on the
 * right. [onClick] null — the row does not open.
 */
@Composable
public fun ListRow(
    title: String,
    subtitle: String?,
    meta: String?,
    value: String?,
    valueTone: String?,
    badge: String?,
    badgeTone: String?,
    tag: String,
    onClick: (() -> Unit)?,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = tokens.body,
                    color = tokens.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    // Tagged like the badge of a card: the state of a record is what a list is read
                    // for, so a test has to be able to point at it.
                    Badge(badge, badgeTone, Modifier.testTag("$tag:badge"))
                }
            }
            val second = listOfNotNull(subtitle, meta).filter { it.isNotBlank() }.joinToString(" · ")
            if (second.isNotEmpty()) {
                Text(text = second, style = tokens.label, color = tokens.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            ValueText(value, valueTone, Modifier.padding(start = 8.dp).testTag("$tag:value"))
        }
        trailing()
    }
}

@Composable
internal fun ValueText(text: String, tone: String?, modifier: Modifier = Modifier) {
    val tokens = AppTheme
    Text(
        text = text,
        style = tokens.label.copy(fontWeight = FontWeight.Medium),
        color = tokens.toneColor(tone) ?: tokens.foreground,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier.widthIn(min = 48.dp),
    )
}

/** −, the value, + — the row form of a stepper. A null [onMinus] / [onPlus] greys that side out. */
@Composable
public fun StepperRow(
    display: String,
    tone: String?,
    onMinus: (() -> Unit)?,
    onPlus: (() -> Unit)?,
    enabled: Boolean,
    tag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        AppButton(
            text = "−",
            onClick = { onMinus?.invoke() },
            enabled = enabled && onMinus != null,
            variant = ButtonVariant.Outline,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("$tag:minus"),
        )
        ValueText(display, tone, Modifier.widthIn(min = 64.dp).padding(horizontal = 6.dp).testTag("$tag:value"))
        AppButton(
            text = "+",
            onClick = { onPlus?.invoke() },
            enabled = enabled && onPlus != null,
            variant = ButtonVariant.Outline,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("$tag:plus"),
        )
    }
}

/** A switch on its own, for a row that has a title already. */
@Composable
public fun SwitchControl(checked: Boolean, label: String, enabled: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(4.dp)
            .testTag(tag)
            .semantics { this.selected = checked },
    ) {
        Switch(checked = checked, label = label)
    }
}

/** The values of a choice as chips, each with its count when there is one. */
@Composable
public fun Chips(
    values: List<String>,
    titles: Map<String, String>,
    counts: Map<String, Int>,
    selected: String?,
    onSelect: (String) -> Unit,
    tag: String,
) {
    val tokens = AppTheme
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            val isSelected = value == selected
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) tokens.primary else tokens.surface)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("$tag-$value"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = titles[value] ?: value,
                    style = tokens.label.copy(fontWeight = FontWeight.Medium),
                    color = if (isSelected) tokens.primaryForeground else tokens.foreground,
                )
                counts[value]?.let { count ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = count.toString(),
                        style = tokens.label,
                        color = if (isSelected) tokens.primaryForeground.copy(alpha = 0.7f) else tokens.muted,
                    )
                }
            }
        }
    }
}

@Composable
public fun SearchBox(value: String, placeholder: String, onChange: (String) -> Unit, tag: String) {
    // AppTextField puts the modifier on the field inside its frame, so the margin goes on a box.
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        AppTextField(value = value, onValueChange = onChange, placeholder = placeholder, modifier = Modifier.testTag(tag))
    }
}

/**
 * A line across the top of the screen: the connection, a notice, an error, with one action. [tone]
 * colours it; null is neutral.
 */
@Composable
public fun Notice(text: String, tone: String?, action: String?, onAction: (() -> Unit)?, tag: String) {
    val tokens = AppTheme
    val color = tokens.toneColor(tone) ?: tokens.muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = tokens.label, color = tokens.foreground, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            AppButton(
                text = action,
                onClick = onAction,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$tag:action"),
            )
        }
    }
}

/**
 * A button that may ask first: with [confirm] set, a tap shows the question and two buttons, and
 * only the second tap acts. A dialog would need a window layer the library does not have.
 */
@Composable
public fun ConfirmButton(
    text: String,
    tone: String?,
    confirm: String?,
    enabled: Boolean,
    words: RenderWords,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    var asking by remember(text, confirm) { mutableStateOf(false) }
    val variant = when (tone) {
        Tones.DANGER -> ButtonVariant.Destructive
        Tones.SUCCESS, Tones.INFO -> ButtonVariant.Default
        else -> ButtonVariant.Outline
    }
    if (!asking) {
        AppButton(
            text = text,
            onClick = { if (confirm != null) asking = true else onClick() },
            enabled = enabled,
            variant = variant,
            size = ButtonSize.Default,
            modifier = modifier.testTag(tag),
        )
        return
    }
    val tokens = AppTheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.background)
            .padding(12.dp)
            .testTag("$tag:confirm"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = confirm.orEmpty(), style = tokens.body, color = tokens.foreground)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                text = text,
                onClick = {
                    asking = false
                    onClick()
                },
                enabled = enabled,
                variant = variant,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$tag:yes"),
            )
            AppButton(
                text = words.cancel,
                onClick = { asking = false },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$tag:no"),
            )
        }
    }
}

/** "Ленина, 5 ▾" at the top of an app by points: where you are, and a tap to go elsewhere. */
@Composable
public fun PlaceLine(title: String, address: String?, onClick: () -> Unit, tag: String) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = tokens.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!address.isNullOrBlank() && address != title) {
                Text(text = address, style = tokens.footnote, color = tokens.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(text = "▾", style = tokens.body, color = tokens.muted)
    }
}

/** One choice in a sheet: id, title, a line under it. */
public data class SheetChoice(val id: String, val title: String, val subtitle: String?)

/**
 * A list from the bottom over a scrim, the tick at the current one — the server switcher's shape,
 * for the points. Back, Escape-less desktop aside, and a tap on the scrim close it.
 */
@Composable
public fun BoxScope.ChoiceSheet(
    visible: Boolean,
    title: String,
    choices: List<SheetChoice>,
    selected: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    tag: String,
) {
    val tokens = AppTheme
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.foreground.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        SystemBackHandler(enabled = visible, onBack = onDismiss)
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(tokens.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(bottom = 12.dp)
                .testTag(tag),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(tokens.border),
            )
            Text(text = title, style = tokens.header, color = tokens.foreground, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { choice ->
                    val isSelected = choice.id == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(tokens.radius))
                            .clickable(role = Role.Button) { onPick(choice.id) }
                            .semantics {
                                this.selected = isSelected
                                contentDescription = choice.title
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                            .testTag("$tag:item-${choice.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = choice.title,
                                style = if (isSelected) tokens.body.copy(fontWeight = FontWeight.SemiBold) else tokens.body,
                                color = tokens.foreground,
                            )
                            choice.subtitle?.takeIf { it.isNotBlank() && it != choice.title }?.let {
                                Text(text = it, style = tokens.label, color = tokens.muted)
                            }
                        }
                        if (isSelected) CheckIcon(tokens.foreground)
                    }
                }
            }
        }
    }
}

/** A block of label–value lines, the card's unit: a section of fields. */
@Composable
public fun FieldLine(label: String, value: String, tone: String?, tag: String) {
    val tokens = AppTheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp).testTag(tag),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = label, style = tokens.label, color = tokens.muted, modifier = Modifier.weight(0.42f).padding(end = 12.dp))
        Text(
            text = value,
            style = tokens.body,
            color = tokens.toneColor(tone) ?: tokens.foreground,
            modifier = Modifier.weight(0.58f).testTag("$tag:value"),
        )
    }
}

/** The middle of the screen when there is nothing else to show: a line, and maybe a button. */
@Composable
public fun EmptyState(text: String, hint: String?, action: String?, onAction: (() -> Unit)?, tag: String) {
    val tokens = AppTheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = text, style = tokens.body.copy(fontWeight = FontWeight.Medium), color = tokens.foreground, textAlign = TextAlign.Center)
        hint?.let { Text(text = it, style = tokens.label, color = tokens.muted, textAlign = TextAlign.Center) }
        if (action != null && onAction != null) {
            AppButton(text = action, onClick = onAction, variant = ButtonVariant.Outline, size = ButtonSize.Default, modifier = Modifier.testTag("$tag:action"))
        }
    }
}

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
)

/**
 * A form by the schema of a method (`control: {type: "form"}`): one line per property, the button of
 * the method under them.
 *
 * As declarative as everything else here — the fields are the schema's, the words are the server's,
 * and what comes back is a plain map of values, so the same component serves the script apps later.
 * A property this version cannot draw (a nested object, an array) is left out rather than guessed at.
 */
@Composable
public fun FormCard(
    title: String,
    fields: List<FormField>,
    submit: String,
    busy: Boolean,
    words: RenderWords,
    onSubmit: (Map<String, JsonElement>) -> Unit,
    onCancel: (() -> Unit)?,
    tag: String,
) {
    val tokens = AppTheme
    val values = remember(tag, fields) { mutableStateMapOf<String, String>() }
    val flags = remember(tag, fields) { mutableStateMapOf<String, Boolean>() }
    val filled = fields.none { field ->
        field.required && when {
            field.type == "boolean" -> false
            else -> values[field.name].isNullOrBlank()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.surface)
            .padding(12.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = title, style = tokens.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.foreground)
        fields.forEach { field ->
            when {
                field.type == "boolean" -> SwitchControl(
                    checked = flags[field.name] == true,
                    label = field.title,
                    enabled = !busy,
                    onChange = { flags[field.name] = it },
                    tag = "$tag:${field.name}",
                )
                field.values.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = field.title, style = tokens.footnote, color = tokens.muted)
                    Chips(
                        values = field.values,
                        titles = field.titles,
                        counts = emptyMap(),
                        selected = values[field.name],
                        onSelect = { values[field.name] = it },
                        tag = "$tag:${field.name}",
                    )
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = field.title, style = tokens.footnote, color = tokens.muted)
                    AppTextField(
                        value = values[field.name].orEmpty(),
                        onValueChange = { values[field.name] = it },
                        placeholder = field.title,
                        modifier = Modifier.fillMaxWidth().testTag("$tag:${field.name}"),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                text = submit,
                onClick = {
                    onSubmit(
                        fields.mapNotNull { field ->
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
                        }.toMap(),
                    )
                },
                enabled = !busy && filled,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$tag:submit"),
            )
            onCancel?.let {
                AppButton(
                    text = words.cancel,
                    onClick = it,
                    enabled = !busy,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("$tag:cancel"),
                )
            }
        }
    }
}
