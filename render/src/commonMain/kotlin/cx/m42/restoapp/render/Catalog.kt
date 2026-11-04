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
import com.composeunstyled.Text
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant

/*
 * Walking a catalog of the menu: the way back, where we are, a search, and the groups to step into.
 *
 * Two screens of the client walk one and the same menu — the «+» of the POS (`order-entry.plan.md`) and the
 * editor of a promotion, which chooses the products and the groups a discount is for (`marketing.plan.md`, Q43).
 * What they share is here, so they cannot drift apart: the shell and the tile of a group. What they do *with* a
 * product is their own — the POS adds it to a receipt, the editor ticks it.
 */

/** The shell of a catalog screen: «← назад», where we are, something of the screen's own, and a search. */
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
    val tokens = AppTheme
    Column(modifier = Modifier.fillMaxSize().testTag(tag)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                text = back,
                onClick = onBack,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$tag-back"),
            )
            Text(
                text = title,
                style = tokens.header,
                color = tokens.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            trailing()
        }
        SearchBox(query, searchHint, onQuery, "$tag-search")
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = content)
    }
}

/** A group of the menu: a tile that steps into it. */
@Composable
public fun GroupTile(name: String, onClick: () -> Unit, tag: String) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = name, style = tokens.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.foreground, modifier = Modifier.weight(1f))
        Text(text = "›", style = tokens.body, color = tokens.muted)
    }
}
