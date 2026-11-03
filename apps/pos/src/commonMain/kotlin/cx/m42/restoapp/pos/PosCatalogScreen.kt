package cx.m42.restoapp.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.restoapp.render.EmptyState
import cx.m42.restoapp.render.SearchBox
import cx.m42.restoapp.render.money
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant

/**
 * The catalog behind the «+»: the groups of the menu, then the products of one, each with the price
 * the server quotes for *this* order and a «+» that adds it. Whatever the point has stopped is not
 * here at all — the server filters the menu, as it does for the site.
 */
@Composable
internal fun CatalogScreen(context: PosContext) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val catalog = state.catalog ?: return
    val tokens = AppTheme

    Column(modifier = Modifier.fillMaxSize().testTag("pos:catalog")) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                text = words.toReceipt,
                onClick = instance::catalogBack,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("pos:catalog-back"),
            )
            // Where we are: the groups from the top down to the open one.
            val here = catalog.catalog.path.lastOrNull()?.name ?: words.catalog
            Text(text = here, style = tokens.header, color = tokens.foreground, modifier = Modifier.padding(start = 8.dp).weight(1f))
            state.order?.let { order ->
                Text(
                    text = money(order.total, context.currency),
                    style = tokens.body.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.foreground,
                    modifier = Modifier.padding(end = 12.dp).testTag("pos:catalog-total"),
                )
            }
        }
        SearchBox(catalog.query, words.searchProduct, instance::catalogSearch, "pos:catalog-search")

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when {
                catalog.loading && catalog.catalog.items.isEmpty() && catalog.catalog.groups.isEmpty() ->
                    Text(words.loading, style = tokens.label, color = tokens.muted, modifier = Modifier.padding(20.dp).testTag("pos:catalog-loading"))
                catalog.catalog.groups.isEmpty() && catalog.catalog.items.isEmpty() ->
                    EmptyState(words.nothingFound, null, null, null, "pos:catalog-empty")
            }
            catalog.catalog.groups.forEach { group ->
                GroupTile(group) { instance.openCatalog(group.id) }
            }
            catalog.catalog.items.forEach { product ->
                ProductTile(context, product)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupTile(group: PosGroup, onClick: () -> Unit) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag("pos:group-${group.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = group.name, style = tokens.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.foreground, modifier = Modifier.weight(1f))
        Text(text = "›", style = tokens.body, color = tokens.muted)
    }
}

/** A product: the price (struck through when a promotion takes it), the stock, and the «+». */
@Composable
private fun ProductTile(context: PosContext, product: PosProduct) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val tokens = AppTheme
    val stopped = product.balance == 0.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("pos:product-${product.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = product.name, style = tokens.body, color = tokens.foreground)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val sale = product.salePrice
                if (sale != null) {
                    Text(text = money(sale, context.currency), style = tokens.footnote.copy(fontWeight = FontWeight.SemiBold), color = tokens.foreground)
                    Text(
                        text = money(product.price, context.currency),
                        style = tokens.footnote.copy(textDecoration = TextDecoration.LineThrough),
                        color = tokens.muted,
                    )
                } else {
                    Text(text = money(product.price, context.currency), style = tokens.footnote, color = tokens.muted)
                }
                // -1 is unlimited and says nothing; a number is what the kitchen has left.
                if (product.balance > 0) Text(text = "${words.left} ${product.balance.toInt()}", style = tokens.footnote, color = tokens.muted)
                if (product.hasOptions) Text(text = words.withOptions, style = tokens.footnote, color = tokens.muted)
            }
        }
        if (product.inOrder > 0) {
            Text(
                text = "× ${product.inOrder}",
                style = tokens.body,
                color = tokens.muted,
                modifier = Modifier.padding(end = 8.dp).testTag("pos:product-${product.id}:in-order"),
            )
        }
        AppButton(
            text = if (stopped) words.stopped else "+",
            onClick = { instance.add(product.id) },
            enabled = !state.busy && !stopped,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag("pos:product-${product.id}:add"),
        )
    }
}
