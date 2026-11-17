package cx.m42.restoapp.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Lucide
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.ui.platform.hostDrawsBack

/*
 * Walking a catalog of the menu: the way back, where we are, a search, and the groups to step into.
 *
 * Two screens of the client walk one and the same menu — the «+» of the POS (`order-entry.plan.md`) and the
 * editor of a promotion, which chooses the products and the groups a discount is for (`marketing.plan.md`, Q43).
 * What they share is here, so they cannot drift apart: the shell and the tile of a group. What they do *with* a
 * product is their own — the POS adds it to a receipt, the editor ticks it.
 */

/**
 * The shell of a catalog screen: where we are, something of the screen's own, and a search. «‹ [back]»
 * is drawn only where the host has no arrow of its own — the screens claim the system back, so the
 * shell's arrow already takes this step.
 */
@Composable
public fun CatalogShell(
    back: String,
    onBack: () -> Unit,
    title: String,
    query: String,
    searchHint: String,
    onQuery: (String) -> Unit,
    tag: String,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().testTag(tag)) {
        if (!hostDrawsBack) {
            Button(
                onClick = onBack,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp).testTag("$tag-back"),
            ) {
                Icon(Lucide.ChevronLeft, contentDescription = null, size = 16.dp)
                Text(back, variant = TextVariant.Small, maxLines = 1)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = PageGutter + 4.dp, end = PageGutter, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                variant = TextVariant.H3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        SearchBox(query, searchHint, onQuery, "$tag-search")
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp), content = content)
    }
}

/** A group of the menu: a row with a folder that steps into it. */
@Composable
public fun GroupTile(name: String, onClick: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter, vertical = 4.dp)
            .clip(Kit.shapes.lg)
            .background(Kit.colors.card)
            .border(1.dp, Kit.colors.border, Kit.shapes.lg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeadingIcon(Lucide.Folder, Kit.colors.warning)
        Text(text = name, style = Kit.typography.p.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
        Icon(Lucide.ChevronRight, contentDescription = null, size = 18.dp, tint = Kit.colors.mutedForeground)
    }
}
