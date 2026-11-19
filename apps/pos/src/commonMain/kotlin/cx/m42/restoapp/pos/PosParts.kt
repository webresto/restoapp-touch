package cx.m42.restoapp.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CakeSlice
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CupSoda
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Fish
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pizza
import com.composables.icons.lucide.Salad
import com.composables.icons.lucide.ShoppingBag
import com.composables.icons.lucide.Soup
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Truck
import com.composables.icons.lucide.UtensilsCrossed
import com.composables.icons.lucide.X
import cx.m42.restoapp.protocol.Tones
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Dialog
import cx.m42.superizer.kit.IconButton
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Menu
import cx.m42.superizer.kit.MenuItem
import cx.m42.superizer.kit.Notice
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textStyles

/*
 * Small pieces the screens of the till share. The kit draws everything; what is here is the glue a
 * till needs on top of it — the server's tone words as kit tones, the icons of the ways an order is
 * handed over, and dialog buttons with test tags (the kit's `DialogActions` and `MenuItem` take none).
 */

/** The server's tone word as the kit's tone. */
internal fun toneOf(tone: String?): Tone = when (tone) {
    Tones.SUCCESS -> Tone.Success
    Tones.DANGER -> Tone.Danger
    Tones.WARNING -> Tone.Warn
    else -> Tone.Brand
}

internal fun iconOf(tone: Tone): ImageVector = when (tone) {
    Tone.Success -> Lucide.CircleCheck
    Tone.Danger -> Lucide.CircleAlert
    Tone.Warn -> Lucide.TriangleAlert
    else -> Lucide.Info
}

/** How the food is handed over, as an icon. */
internal fun serviceIcon(type: String): ImageVector = when (type) {
    PosOrder.DELIVERY -> Lucide.Truck
    PosOrder.PICKUP -> Lucide.ShoppingBag
    else -> Lucide.UtensilsCrossed
}

/** A group of the menu as an icon, guessed from its name; a menu the guess does not know gets cutlery. */
internal fun groupIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        "пицц" in n || "pizz" in n -> Lucide.Pizza
        "суп" in n || "soup" in n -> Lucide.Soup
        "салат" in n || "salad" in n -> Lucide.Salad
        "напит" in n || "drink" in n || "морс" in n || "кофе" in n || "coffee" in n -> Lucide.CupSoda
        "ролл" in n || "суши" in n || "roll" in n || "sushi" in n || "рыб" in n || "fish" in n -> Lucide.Fish
        "десерт" in n || "dessert" in n || "торт" in n || "cake" in n -> Lucide.CakeSlice
        else -> Lucide.UtensilsCrossed
    }
}

/**
 * The server's word on a step, as a kit notice: kept on screen until the next step, not a toast.
 * [onDismiss] puts a cross at its end.
 */
@Composable
internal fun PosNotice(words: PosWords, tone: String?, text: String, tag: String, onDismiss: (() -> Unit)? = null, title: String? = null) {
    val kitTone = toneOf(tone)
    val heading = title ?: when (kitTone) {
        Tone.Success -> words.checked
        Tone.Danger -> words.refused
        Tone.Warn -> words.attention
        else -> words.serverSays
    }
    Notice(
        tone = kitTone,
        icon = iconOf(kitTone),
        title = heading,
        text = text,
        modifier = Modifier.testTag(tag),
        action = onDismiss?.let { dismiss -> { IconButton(Lucide.X, words.hide, dismiss, Modifier.testTag("$tag:close"), size = ButtonSize.Sm) } },
    )
}

/**
 * A kit [Dialog] whose body carries [tag], so a test finds the question that is open, and whose
 * footer is [PosActions].
 */
@Composable
internal fun PosDialog(
    tag: String,
    title: String,
    onDismiss: () -> Unit,
    words: PosWords,
    description: String? = null,
    icon: ImageVector? = null,
    tone: Tone = Tone.Brand,
    width: androidx.compose.ui.unit.Dp = 460.dp,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismiss = onDismiss,
        title = title,
        description = description,
        icon = icon,
        tone = tone,
        width = width,
        closeLabel = words.close,
        footer = footer,
    ) {
        Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/**
 * The buttons of a dialog, as the kit's `DialogActions` lays them out — stacked with the answer on top
 * on a phone, side by side at the right elsewhere — with a tag on each. [extra] is a third, quieter
 * button: «Найти клиента», «Снять».
 */
@Composable
internal fun PosActions(
    confirm: String?,
    onConfirm: () -> Unit,
    confirmTag: String,
    cancel: String,
    onCancel: () -> Unit,
    cancelTag: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    extra: Triple<String, () -> Unit, String>? = null,
    extraEnabled: Boolean = true,
) {
    val variant = if (destructive) ButtonVariant.Destructive else ButtonVariant.Default
    if (LocalWindowClass.current == WindowClass.Compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (confirm != null) Button(confirm, onConfirm, Modifier.fillMaxWidth().testTag(confirmTag), variant = variant, size = ButtonSize.Lg, enabled = enabled)
            extra?.let { (text, click, tag) -> Button(text, click, Modifier.fillMaxWidth().testTag(tag), variant = ButtonVariant.Outline, size = ButtonSize.Lg, enabled = extraEnabled) }
            Button(cancel, onCancel, Modifier.fillMaxWidth().testTag(cancelTag), variant = ButtonVariant.Ghost, size = ButtonSize.Lg)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            extra?.let { (text, click, tag) -> Button(text, click, Modifier.testTag(tag), variant = ButtonVariant.Ghost, enabled = extraEnabled) }
            Box(Modifier.weight(1f))
            Button(cancel, onCancel, Modifier.testTag(cancelTag), variant = ButtonVariant.Outline)
            if (confirm != null) Button(confirm, onConfirm, Modifier.testTag(confirmTag), variant = variant, enabled = enabled)
        }
    }
}

/** A question before what cannot be undone: the kit's confirm dialog, with tags on its parts. */
@Composable
internal fun PosConfirm(
    tag: String,
    title: String,
    text: String,
    confirm: String,
    words: PosWords,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    Dialog(
        onDismiss = onDismiss,
        title = title,
        description = text,
        icon = icon,
        tone = if (destructive) Tone.Danger else Tone.Brand,
        footer = {
            Column(Modifier.testTag("$tag:confirm")) {
                PosActions(confirm, onConfirm, "$tag:yes", words.cancel, onDismiss, "$tag:no", destructive = destructive)
            }
        },
    )
}

/** The `⋯` of the receipt: «Бросить заказ», asked about first. */
@Composable
internal fun ReceiptMore(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    var open by remember { mutableStateOf(false) }
    var drop by remember { mutableStateOf(false) }
    Box {
        IconButton(Lucide.Ellipsis, words.moreActions, { open = true }, Modifier.testTag("pos:more"), size = ButtonSize.Sm, enabled = !state.busy)
        Menu(open, { open = false }) {
            Box(Modifier.testTag("pos:drop")) {
                MenuItem(words.dropOrder, { open = false; drop = true }, icon = Lucide.Trash2, destructive = true)
            }
        }
    }
    if (drop) {
        PosConfirm(
            tag = "pos:drop",
            title = words.dropTitle(order.number),
            text = words.dropText(order.dishesCount),
            confirm = words.dropYes,
            words = words,
            onConfirm = { drop = false; instance.cancel() },
            onDismiss = { drop = false },
            destructive = true,
            icon = Lucide.Trash2,
        )
    }
}

/** «×2» on a product the receipt already has. */
@Composable
internal fun InReceipt(count: Int, modifier: Modifier = Modifier) {
    Box(
        // Its own node, not merged into the clickable row it sits in: a test and a screen reader find it.
        modifier.semantics(mergeDescendants = true) {}.height(22.dp).clip(CircleShape).background(Kit.palette.brand).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("×$count", style = Kit.textStyles.caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = Kit.palette.onBrand)
    }
}

