package cx.m42.restoapp.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Receipt
import com.composables.icons.lucide.Search
import cx.m42.superizer.kit.ActionBar
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.Chip
import cx.m42.superizer.kit.EmptyState
import cx.m42.superizer.kit.Grid
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.Input
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.Page
import cx.m42.superizer.kit.PageHeader
import cx.m42.superizer.kit.Panel
import cx.m42.superizer.kit.Separator
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles

/*
 * The catalog: a search across the whole menu, the groups as chips, and the products of the chosen
 * one — tiles with a «+» where there is room, rows on a phone — each with the price the server quotes
 * for *this* order. What the point has stopped is not here at all: the server filters the menu, as it
 * does for the site. A product with something to choose opens the chooser instead of going straight in.
 */

/** The catalog as a page of its own: a phone's «Каталог», with the receipt's total and «К чеку» under it. */
@Composable
internal fun CatalogPage(context: PosContext, order: PosOrder) {
    val words = context.words
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Page(modifier = Modifier.testTag("pos:catalog")) {
                PageHeader(
                    title = words.addDishes,
                    description = listOf(words.toOrder(order.number), receivingOf(context, order)).joinToString(" · "),
                )
                CatalogBody(context, order, columns = 1)
            }
        }
        ActionBar {
            Column(Modifier.weight(1f)) {
                Text(words.inReceipt(order.dishesCount), style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1)
                Text(
                    context.money(order.total),
                    Modifier.testTag("pos:catalog-total"),
                    style = Kit.typography.large.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
            Button(
                words.backToReceipt,
                context.instance::closeCatalog,
                Modifier.weight(1.2f).testTag("pos:to-receipt"),
                size = ButtonSize.Lg,
                leadingIcon = Lucide.Receipt,
            )
        }
    }
}

/** The search, the chips and the products; [columns] 1 draws rows, more — tiles. */
@Composable
internal fun CatalogBody(context: PosContext, order: PosOrder, columns: Int) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val open = state.catalog ?: return
    val catalog = open.catalog
    val searching = open.query.isNotBlank()

    // The groups at the top of the menu stay as chips while one of them is open: the server answers
    // a group with what is inside it, so the top is remembered from when it was shown.
    var top by remember(order.id) { mutableStateOf(emptyList<PosGroup>()) }
    if (open.group == null && !searching && catalog.groups.isNotEmpty() && catalog.groups != top) top = catalog.groups
    // The top of the menu is groups only: a group is chosen at once — the last one looked at, or the first.
    LaunchedEffect(open.group, searching, open.loading, catalog.groups) {
        if (open.group == null && !searching && !open.loading && catalog.items.isEmpty() && catalog.groups.isNotEmpty()) {
            instance.openCatalog(context.lastGroup?.takeIf { id -> catalog.groups.any { it.id == id } } ?: catalog.groups.first().id)
        }
    }

    Input(
        open.query,
        instance::catalogSearch,
        Modifier.testTag("pos:catalog-search"),
        placeholder = words.searchProduct,
        leadingIcon = Lucide.Search,
    )
    if (!searching && top.isNotEmpty()) {
        val selected = catalog.path.firstOrNull()?.id ?: open.group
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            top.forEach { group ->
                Chip(group.name, group.id == selected, { instance.openCatalog(group.id) }, Modifier.testTag("pos:group-${group.id}"))
            }
        }
    }
    // A group with groups inside it: those as a second row of chips, under the first.
    if (!searching && open.group != null && catalog.groups.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            catalog.groups.forEach { group ->
                Chip(group.name, false, { instance.openCatalog(group.id) }, Modifier.testTag("pos:group-${group.id}"))
            }
        }
    }

    val items = catalog.items
    // The art of a product is its group's: the icon guessed from the group's name, the colour by its place.
    val groupIndex = { product: PosProduct -> top.indexOfFirst { it.id == product.groupId }.coerceAtLeast(0) }
    val groupName = { product: PosProduct -> (top + catalog.path).firstOrNull { it.id == product.groupId }?.name ?: product.groupId ?: product.name }
    when {
        open.loading && items.isEmpty() -> Text(words.loading, Modifier.testTag("pos:catalog-loading"), variant = TextVariant.Muted)
        items.isEmpty() && searching -> EmptyState(Lucide.Search, words.nothingFound, words.nothingFoundHint(open.query.trim()), Modifier.testTag("pos:catalog-empty"))
        items.isEmpty() && catalog.groups.isEmpty() -> EmptyState(Lucide.Search, words.nothingFound, words.chooseGroupHint, Modifier.testTag("pos:catalog-empty"))
        items.isEmpty() -> Unit
        columns == 1 -> Panel(flush = true) {
            items.forEachIndexed { i, product ->
                if (i > 0) Separator(inset = 72.dp)
                ProductRow(context, product, groupIndex(product), groupName(product))
            }
        }
        else -> Grid(columns, gap = 12.dp) { items.forEach { ProductTile(context, it, groupIndex(it), groupName(it)) } }
    }
}

/** A product's picture where the menu has none: the group's icon on the group's colour. */
@Composable
private fun ProductArt(group: String, index: Int, size: Dp, modifier: Modifier) {
    val chart = Kit.palette.chart
    val c = chart[index % chart.size]
    Box(modifier.background(c.copy(alpha = if (Kit.colors.isDark) 0.18f else 0.12f)), contentAlignment = Alignment.Center) {
        Icon(groupIcon(group), null, size = size, tint = c)
    }
}

/** The round «+»: in the accent while the product can be added. */
@Composable
private fun AddButton(context: PosContext, product: PosProduct, enabled: Boolean) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (enabled) Kit.colors.primary else Kit.colors.muted)
            .kitClickable(CircleShape, enabled = enabled, hover = false) { pick(context, product) }
            .testTag("pos:product-${product.id}:add"),
        contentAlignment = Alignment.Center,
    ) { Icon(Lucide.Plus, context.words.add, size = 16.dp, tint = if (enabled) Kit.colors.onPrimary else Kit.colors.mutedForeground) }
}

/** A dish with something to choose opens the chooser; the rest go straight in. */
private fun pick(context: PosContext, product: PosProduct) {
    if (product.hasOptions) context.instance.openOptions(dishId = product.id) else context.instance.add(product.id)
}

/** The price — the promotion's, with the old one struck through — and what the stock says. */
@Composable
private fun PriceLine(context: PosContext, product: PosProduct, small: Boolean) {
    val words = context.words
    val price = (if (small) Kit.textStyles.caption else Kit.textStyles.number).copy(fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(context.money(product.salePrice ?: product.price), style = price, maxLines = 1)
        if (product.salePrice != null) {
            Text(
                context.money(product.price),
                style = Kit.textStyles.caption.copy(textDecoration = TextDecoration.LineThrough),
                color = Kit.colors.mutedForeground,
                maxLines = 1,
            )
        }
        if (small) StockHint(words, product)
    }
}

@Composable
private fun StockHint(words: PosWords, product: PosProduct) {
    when {
        product.balance == 0.0 -> Text(words.outOfStock, style = Kit.textStyles.caption, color = Tone.Danger.textColor(), maxLines = 1)
        // -1 is unlimited and says nothing; a number is what the kitchen has left.
        product.balance > 0 -> Text("${words.left} ${product.balance.toInt()}", style = Kit.textStyles.caption, color = Tone.Warn.textColor(), maxLines = 1)
        product.hasOptions -> Text(words.withOptions, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProductRow(context: PosContext, product: PosProduct, index: Int, group: String) {
    val stopped = product.balance == 0.0
    val enabled = !stopped && !context.state.busy
    Row(
        Modifier
            .fillMaxWidth()
            .kitClickable(enabled = enabled) { pick(context, product) }
            .alpha(if (stopped) 0.55f else 1f)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("pos:product-${product.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProductArt(group, index, 20.dp, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(product.name, style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PriceLine(context, product, small = true)
        }
        if (product.inOrder > 0) InReceipt(product.inOrder, Modifier.testTag("pos:product-${product.id}:in-order"))
        AddButton(context, product, enabled)
    }
}

@Composable
private fun ProductTile(context: PosContext, product: PosProduct, index: Int, group: String) {
    val stopped = product.balance == 0.0
    val enabled = !stopped && !context.state.busy
    val shape = RoundedCornerShape(14.dp)
    val chosen = product.inOrder > 0
    Column(
        Modifier
            .clip(shape)
            .background(Kit.colors.card)
            .border(if (chosen) 1.5.dp else 1.dp, if (chosen) Kit.palette.brandBorder else Kit.colors.border, shape)
            .kitClickable(enabled = enabled) { pick(context, product) }
            .alpha(if (stopped) 0.55f else 1f)
            .testTag("pos:product-${product.id}"),
    ) {
        Box {
            ProductArt(group, index, 30.dp, Modifier.fillMaxWidth().height(80.dp))
            if (chosen) InReceipt(product.inOrder, Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("pos:product-${product.id}:in-order"))
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(product.name, style = Kit.textStyles.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            product.code?.takeIf { it.isNotBlank() }?.let { Text(it, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PriceLine(context, product, small = false)
                    StockHint(context.words, product)
                }
                AddButton(context, product, enabled)
            }
        }
    }
}
