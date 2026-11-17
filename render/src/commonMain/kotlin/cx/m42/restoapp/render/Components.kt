package cx.m42.restoapp.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronsUpDown
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.TriangleAlert
import cx.m42.restoapp.protocol.Tones
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Field
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.IconButton
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.ItemSeparator
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Switch
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.theme.Tokens
import cx.m42.superizer.ui.platform.SystemBackHandler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * The renderer's building blocks (plan section 4, "Задел под JS-приложения"): each takes plain
 * values and callbacks — strings, numbers, tones by name — and no type of the protocol, so the
 * components of script apps (script-apps.plan.md) are these, not a second set.
 *
 * Drawn with Superizer's ui-kit. The look is a phone's, not a web page's: grouped white blocks on the
 * grey page, a row is the unit of every list, the state of a record is a coloured label and a dot,
 * one main action per screen, and a field always says what it is above it — a placeholder that goes
 * away as soon as one types is not a caption.
 */

/** The page's gutter: what every block keeps from the screen's edges. */
public val PageGutter: androidx.compose.ui.unit.Dp = 16.dp

/** A tone of the contract as a colour of the theme; null — the ordinary text colour. */
internal fun Tokens.toneColor(tone: String?): Color? = when (tone) {
    Tones.DANGER -> danger
    Tones.WARNING -> warning
    Tones.SUCCESS -> success
    Tones.INFO -> accent
    else -> null
}

/** The same, from the kit's palette — what the components here draw with. */
@Composable
internal fun toneOf(tone: String?): Color? = when (tone) {
    Tones.DANGER -> Kit.colors.destructive
    Tones.WARNING -> Kit.colors.warning
    Tones.SUCCESS -> Kit.colors.success
    Tones.INFO -> Kit.colors.info
    else -> null
}

/**
 * A titled block of rows on the grey page: the unit of every card, every settings screen and every
 * summary. The title is small and quiet — the rows are what is read; [action] is a link at its end
 * («Все»).
 */
@Composable
public fun Group(
    title: String? = null,
    modifier: Modifier = Modifier,
    hint: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null || action != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    text = title.orEmpty(),
                    style = Kit.typography.small.copy(fontWeight = FontWeight.SemiBold),
                    color = Kit.colors.mutedForeground,
                    modifier = Modifier.weight(1f),
                )
                if (action != null && onAction != null) {
                    Text(
                        text = action,
                        style = Kit.typography.small,
                        color = Kit.colors.info,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onAction).padding(4.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Kit.shapes.lg)
                .background(Kit.colors.card)
                .border(1.dp, Kit.colors.border, Kit.shapes.lg),
            content = content,
        )
        hint?.let {
            Text(it, variant = TextVariant.Muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** The line between two rows of a [Group]. */
@Composable
public fun RowLine() {
    ItemSeparator()
}

/**
 * A small label in the tone's colour, with a dot in front: an order's state. One text node — the dot is
 * drawn behind it — so the label's text is what a test or a screen reader finds on it.
 */
@Composable
public fun Badge(text: String, tone: String?, modifier: Modifier = Modifier) {
    val color = toneOf(tone) ?: Kit.colors.mutedForeground
    Text(
        text = text,
        style = Kit.typography.small.copy(fontWeight = FontWeight.SemiBold, fontSize = Kit.typography.small.fontSize * 0.86f),
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .drawBehind { drawCircle(color, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(11.dp.toPx(), size.height / 2)) }
            .padding(start = 19.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    )
}

/**
 * One record in a list: title and subtitle on the left, a value (or the row's controls) on the
 * right. [onClick] null — the row does not open; with it, a chevron says it does.
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
    /** False for a row that is a choice, not a way in: it picks, it does not open anything. */
    chevron: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(start = 16.dp, end = if (onClick != null && chevron) 10.dp else 16.dp, top = 10.dp, bottom = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = Kit.typography.p.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    // Tagged like the badge of a card: the state of a record is what a list is read
                    // for, so a test has to be able to point at it.
                    Badge(badge, badgeTone, Modifier.padding(start = 8.dp).testTag("$tag:badge"))
                }
            }
            val second = listOfNotNull(subtitle, meta).filter { it.isNotBlank() }.joinToString(" · ")
            if (second.isNotEmpty()) {
                Text(second, variant = TextVariant.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            ValueText(value, valueTone, Modifier.padding(start = 12.dp).testTag("$tag:value"))
        }
        trailing()
        if (onClick != null && chevron) {
            Icon(Lucide.ChevronRight, contentDescription = null, size = 18.dp, tint = Kit.colors.mutedForeground, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
internal fun ValueText(text: String, tone: String?, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Kit.typography.p.copy(fontWeight = FontWeight.Medium),
        color = toneOf(tone) ?: Kit.colors.foreground,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier.widthIn(min = 40.dp),
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(Kit.shapes.full)
            .border(1.dp, Kit.colors.border, Kit.shapes.full),
    ) {
        IconButton(
            Lucide.Minus,
            contentDescription = "−",
            onClick = { onMinus?.invoke() },
            enabled = enabled && onMinus != null,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("$tag:minus"),
        )
        ValueText(display, tone, Modifier.widthIn(min = 44.dp).testTag("$tag:value"))
        IconButton(
            Lucide.Plus,
            contentDescription = "+",
            onClick = { onPlus?.invoke() },
            enabled = enabled && onPlus != null,
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
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, contentDescription = label)
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
    /** What the row keeps from the edges: the page's gutter on a screen, nothing inside a card. */
    inset: androidx.compose.ui.unit.Dp = PageGutter,
) {
    val c = Kit.colors
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = inset, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            val isSelected = value == selected
            Row(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) c.primary else c.card)
                    .border(1.dp, if (isSelected) c.primary else c.border, CircleShape)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
                    .testTag("$tag-$value"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = titles[value] ?: value,
                    variant = TextVariant.Small,
                    color = if (isSelected) c.onPrimary else c.foreground,
                )
                counts[value]?.let { count ->
                    Text(
                        text = count.toString(),
                        variant = TextVariant.Small,
                        color = if (isSelected) c.onPrimary.copy(alpha = 0.7f) else c.mutedForeground,
                    )
                }
            }
        }
    }
}

/** The search over a list: a field with the magnifier in it, across the top. */
@Composable
public fun SearchBox(value: String, placeholder: String, onChange: (String) -> Unit, tag: String) {
    Box(Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 8.dp)) {
        Input(
            value = value,
            onValueChange = onChange,
            placeholder = placeholder,
            leadingIcon = Lucide.Search,
            modifier = Modifier.testTag(tag),
        )
    }
}

/**
 * A line across the top of the screen: the connection, a notice, an error, with one action. [tone]
 * colours it and picks its icon; null is neutral.
 */
@Composable
public fun Notice(text: String, tone: String?, action: String?, onAction: (() -> Unit)?, tag: String) {
    val color = toneOf(tone) ?: Kit.colors.mutedForeground
    val icon = when (tone) {
        Tones.DANGER -> Lucide.CircleAlert
        Tones.WARNING -> Lucide.TriangleAlert
        Tones.SUCCESS -> Lucide.CircleCheck
        else -> Lucide.Info
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter, vertical = 6.dp)
            .clip(Kit.shapes.lg)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), Kit.shapes.lg)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, size = 18.dp, tint = color)
        Text(text = text, variant = TextVariant.Small, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        if (action != null && onAction != null) {
            Button(
                text = action,
                onClick = onAction,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$tag:action"),
            )
        }
    }
}

/** A tone as a button's variant: danger is red, success and info are the main action, the rest outlined. */
public fun buttonVariantOf(tone: String?): ButtonVariant = when (tone) {
    Tones.DANGER -> ButtonVariant.Destructive
    Tones.SUCCESS, Tones.INFO -> ButtonVariant.Default
    else -> ButtonVariant.Outline
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
    variant: ButtonVariant = buttonVariantOf(tone),
) {
    var asking by remember(text, confirm) { mutableStateOf(false) }
    if (!asking) {
        Button(
            onClick = { if (confirm != null) asking = true else onClick() },
            enabled = enabled,
            variant = variant,
            size = ButtonSize.Lg,
            modifier = modifier.testTag(tag),
        ) {
            // A quiet button of a dangerous value still says it is one: red words, no red block.
            Text(
                text = text,
                style = Kit.typography.p.copy(fontWeight = FontWeight.Medium),
                color = if (tone == Tones.DANGER && variant != ButtonVariant.Destructive) Kit.colors.destructive else androidx.compose.ui.graphics.Color.Unspecified,
                maxLines = 1,
            )
        }
        return
    }
    val danger = toneOf(Tones.DANGER) ?: Kit.colors.destructive
    Column(
        modifier = modifier
            .clip(Kit.shapes.lg)
            .background(danger.copy(alpha = 0.06f))
            .border(1.dp, danger.copy(alpha = 0.3f), Kit.shapes.lg)
            .padding(12.dp)
            .testTag("$tag:confirm"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = confirm.orEmpty())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                text = text,
                onClick = {
                    asking = false
                    onClick()
                },
                enabled = enabled,
                variant = if (tone == Tones.DANGER) ButtonVariant.Destructive else ButtonVariant.Default,
                modifier = Modifier.weight(1f).testTag("$tag:yes"),
            )
            Button(
                text = words.cancel,
                onClick = { asking = false },
                variant = ButtonVariant.Outline,
                modifier = Modifier.weight(1f).testTag("$tag:no"),
            )
        }
    }
}

/** "Ленина, 5 ⇅" at the top of an app by points: where you are, and a tap to go elsewhere. */
@Composable
public fun PlaceLine(title: String, address: String?, onClick: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PageGutter, end = PageGutter, top = 10.dp, bottom = 2.dp)
            .clip(Kit.shapes.lg)
            .background(Kit.colors.card)
            .border(1.dp, Kit.colors.border, Kit.shapes.lg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LeadingIcon(Lucide.MapPin, Kit.colors.info)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = Kit.typography.p.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!address.isNullOrBlank() && address != title) {
                Text(text = address, variant = TextVariant.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Lucide.ChevronsUpDown, contentDescription = null, size = 18.dp, tint = Kit.colors.mutedForeground)
    }
}

/** An icon on a tinted rounded square: what leads a row that stands for a place, a section, an app. */
@Composable
public fun LeadingIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, size = 18.dp, tint = tint)
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
    BottomSheet(visible = visible, title = title, onDismiss = onDismiss, tag = tag) {
        choices.forEach { choice ->
            val isSelected = choice.id == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(Kit.shapes.lg)
                    .clickable(role = Role.Button) { onPick(choice.id) }
                    .background(if (isSelected) Kit.colors.accent else Color.Transparent)
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
                        style = if (isSelected) Kit.typography.p.copy(fontWeight = FontWeight.SemiBold) else Kit.typography.p,
                    )
                    choice.subtitle?.takeIf { it.isNotBlank() && it != choice.title }?.let {
                        Text(text = it, variant = TextVariant.Muted)
                    }
                }
                if (isSelected) Icon(Lucide.Check, contentDescription = null, size = 18.dp)
            }
        }
    }
}

/**
 * A sheet from the bottom over a scrim: a handle, a [title], and [content] that scrolls when it is
 * long. Back and a tap on the scrim close it.
 */
@Composable
public fun BoxScope.BottomSheet(
    visible: Boolean,
    title: String?,
    onDismiss: () -> Unit,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Kit.colors.scrim)
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
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Kit.colors.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(bottom = 16.dp)
                .testTag(tag),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(Kit.colors.border),
            )
            if (title != null) {
                Text(text = title, variant = TextVariant.H3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

/** One label–value line of a card: the label quiet on the left, the value on the right. */
@Composable
public fun FieldLine(label: String, value: String, tone: String?, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 11.dp).testTag(tag),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = label, color = Kit.colors.mutedForeground, modifier = Modifier.weight(0.45f))
        Text(
            text = value,
            color = toneOf(tone) ?: Kit.colors.foreground,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.55f).testTag("$tag:value"),
        )
    }
}

/** A row that leads somewhere: its words, a line about it, and where it goes — one more screen, or out of the app. */
@Composable
public fun LinkLine(
    title: String,
    subtitle: String?,
    external: Boolean,
    onClick: () -> Unit,
    tag: String,
    /** What a screen reader hears for the arrow of a line that leaves the app. */
    externalHint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, variant = TextVariant.Muted) }
        }
        Icon(
            if (external) Lucide.ArrowUpRight else Lucide.ChevronRight,
            contentDescription = if (external) externalHint else null,
            size = 18.dp,
            tint = Kit.colors.mutedForeground,
            modifier = Modifier.testTag("$tag:value"),
        )
    }
}

/** The middle of the screen when there is nothing else to show: an icon, a line, and maybe a button. */
@Composable
public fun EmptyState(text: String, hint: String?, action: String?, onAction: (() -> Unit)?, tag: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Kit.colors.muted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Inbox, contentDescription = null, size = 26.dp, tint = Kit.colors.mutedForeground)
        }
        Text(text = text, style = Kit.typography.p.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        hint?.let { Text(text = it, variant = TextVariant.Muted, textAlign = TextAlign.Center) }
        if (action != null && onAction != null) {
            Button(text = action, onClick = onAction, variant = ButtonVariant.Outline, modifier = Modifier.padding(top = 8.dp).testTag("$tag:action"))
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
    /** The schema's `default`: what the form starts with — the current value when a record's form edits it. */
    val initial: String? = null,
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
    onSubmit: (Map<String, JsonElement>, saved: () -> Unit) -> Unit,
    onCancel: (() -> Unit)?,
    tag: String,
    hold: (UnsavedWork?) -> Unit = {},
) {
    val values = remember(tag, fields) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { field -> if (field.type != "boolean") field.initial?.let { put(field.name, it) } }
        }
    }
    val flags = remember(tag, fields) {
        mutableStateMapOf<String, Boolean>().apply {
            fields.forEach { field -> if (field.type == "boolean" && field.initial == "true") put(field.name, true) }
        }
    }
    val filled = fields.none { field ->
        field.required && when {
            field.type == "boolean" -> false
            else -> values[field.name].isNullOrBlank()
        }
    }
    fun send(saved: () -> Unit) = onSubmit(
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
        saved,
    )
    // Changed against what it started with: a form of a record starts filled in and is not "unsaved" until edited.
    val dirty = fields.any { field ->
        if (field.type == "boolean") (flags[field.name] == true) != (field.initial == "true")
        else values[field.name].orEmpty() != field.initial.orEmpty()
    }
    HoldUnsaved(dirty, hold) { saved -> if (filled && !busy) send(saved) }
    // Filled in, the form is a step of its own: back closes it, not the screen under it.
    SystemBackHandler(enabled = dirty && onCancel != null) { onCancel?.invoke() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter, vertical = 6.dp)
            .clip(Kit.shapes.lg)
            .background(Kit.colors.card)
            .border(1.dp, Kit.colors.border, Kit.shapes.lg)
            .padding(16.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = title, variant = TextVariant.H4)
        fields.forEach { field ->
            val caption = if (field.required) field.title else "${field.title} · ${words.optional}"
            when {
                field.type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(field.title, modifier = Modifier.weight(1f))
                    SwitchControl(
                        checked = flags[field.name] == true,
                        label = field.title,
                        enabled = !busy,
                        onChange = { flags[field.name] = it },
                        tag = "$tag:${field.name}",
                    )
                }
                field.values.isNotEmpty() -> Field(label = caption) {
                    Chips(
                        values = field.values,
                        titles = field.titles,
                        counts = emptyMap(),
                        selected = values[field.name],
                        onSelect = { values[field.name] = it },
                        tag = "$tag:${field.name}",
                        inset = 0.dp,
                    )
                }
                else -> Field(label = caption) {
                    Input(
                        value = values[field.name].orEmpty(),
                        onValueChange = { values[field.name] = it },
                        label = field.title,
                        keyboardOptions = keyboardFor(field),
                        modifier = Modifier.testTag("$tag:${field.name}"),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onCancel?.let {
                Button(
                    text = words.cancel,
                    onClick = it,
                    enabled = !busy,
                    variant = ButtonVariant.Outline,
                    modifier = Modifier.weight(1f).testTag("$tag:cancel"),
                )
            }
            Button(
                text = submit,
                onClick = { send {} },
                enabled = !busy && filled,
                loading = busy,
                modifier = Modifier.weight(1f).testTag("$tag:submit"),
            )
        }
    }
}

/** The keyboard a field wants: digits for a number, the phone pad for a phone, mail for an e-mail. */
internal fun keyboardFor(field: FormField): androidx.compose.foundation.text.KeyboardOptions {
    val type = when {
        field.type == "integer" -> androidx.compose.ui.text.input.KeyboardType.Number
        field.type == "number" || field.format == "money" -> androidx.compose.ui.text.input.KeyboardType.Decimal
        field.format == "phone" -> androidx.compose.ui.text.input.KeyboardType.Phone
        field.format == "email" -> androidx.compose.ui.text.input.KeyboardType.Email
        else -> androidx.compose.ui.text.input.KeyboardType.Text
    }
    return androidx.compose.foundation.text.KeyboardOptions(keyboardType = type)
}
