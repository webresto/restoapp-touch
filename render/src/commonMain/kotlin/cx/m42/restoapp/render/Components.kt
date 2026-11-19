package cx.m42.restoapp.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import cx.m42.restoapp.protocol.Tones
import androidx.compose.ui.semantics.text
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.StatusBadge
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.color
import cx.m42.superizer.kit.softColor
import cx.m42.superizer.kit.textStyles
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Switch
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.ui.platform.SystemBackHandler

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
                    style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold),
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
                .clip(RoundedCornerShape(14.dp))
                .background(Kit.colors.card)
                .border(1.dp, Kit.colors.border, RoundedCornerShape(14.dp)),
            content = content,
        )
        hint?.let {
            Text(it, variant = TextVariant.Muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/**
 * An order's state: the kit's `StatusBadge` in the tone. One text node — the dot and the pill are its
 * drawing — so the label's text is what a test or a screen reader finds on it.
 */
@Composable
public fun Badge(text: String, tone: String?, modifier: Modifier = Modifier) {
    // The kit's `StatusBadge` drawn as one text: its pill, its dot and its words, without its breathing.
    val kitTone = toneKit(tone)
    val dot = kitTone.color()
    Text(
        text = text,
        style = Kit.textStyles.caption.copy(fontWeight = FontWeight.Medium),
        color = kitTone.textColor(),
        maxLines = 1,
        modifier = modifier
            .clip(Kit.shapes.full)
            .background(kitTone.softColor())
            .drawBehind { drawCircle(dot, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(10.dp.toPx(), size.height / 2)) }
            .padding(start = 18.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
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
            .heightIn(min = 56.dp)
            .let { if (onClick != null) it.kitClickable(onClick = onClick) else it }
            .padding(start = 16.dp, end = if (onClick != null && chevron) 10.dp else 16.dp, top = 10.dp, bottom = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = Kit.textStyles.label,
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
            Icon(Lucide.ChevronRight, contentDescription = null, size = 16.dp, tint = Kit.colors.mutedForeground, modifier = Modifier.padding(start = 6.dp))
        }
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
    ChipsRow(values, titles, counts, selected, onSelect, tag, Modifier.padding(horizontal = inset, vertical = if (inset > 0.dp) 8.dp else 0.dp))
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
    NoticeBar(text, tone, action, onAction, tag, Modifier.padding(horizontal = PageGutter, vertical = 6.dp))
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
private fun BoxScope.BottomSheet(
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

