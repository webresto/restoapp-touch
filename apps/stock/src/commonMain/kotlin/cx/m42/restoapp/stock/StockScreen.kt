package cx.m42.restoapp.stock

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.components.ToggleRow
import cx.m42.superizer.ui.platform.SystemBackHandler

private const val TAG = StockApp.ID

/** The list with its search and filters, or one dish's editor — switched on [StockInstance.open]. */
@Composable
internal fun StockScreen(instance: StockInstance) {
    val runtime = LocalAppRuntime.current
    val langTag by runtime.locale.langTag.collectAsState()
    val strings = remember(langTag) { runtime.locale.pick(StockStringTables, StockStringsRu) }
    val items by instance.items.collectAsState()
    val open by instance.open.collectAsState()

    val item = items.firstOrNull { it.id == open }
    if (item != null) {
        SystemBackHandler(enabled = true) { instance.back() }
        ItemPage(item, instance, strings)
    } else {
        ItemList(items, instance, strings)
    }
}

@Composable
private fun ItemList(items: List<StockItem>, instance: StockInstance, strings: StockStrings) {
    val tokens = AppTheme
    val filter by instance.filter.collectAsState()
    val query by instance.query.collectAsState()
    val shown = instance.shown(items, filter, query)

    Column(modifier = Modifier.fillMaxSize().testTag("$TAG:list")) {
        // AppTextField puts the modifier on the field inside its frame, so the margin goes on a box.
        Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            AppTextField(
                value = query,
                onValueChange = instance::search,
                placeholder = strings.search,
                modifier = Modifier.testTag("$TAG:search"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEach { each ->
                val label = when (each) {
                    Filter.Limited -> strings.limited
                    Filter.Stop -> strings.stop
                    Filter.All -> strings.all
                }
                Chip(
                    label = label,
                    count = items.count { each.shows(it) },
                    selected = each == filter,
                    tag = "$TAG:filter-${each.name.lowercase()}",
                    onClick = { instance.select(each) },
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            if (shown.isEmpty()) {
                Text(
                    text = if (query.isBlank() && filter == Filter.Limited) strings.allInStock else strings.nothingFound,
                    style = tokens.body,
                    color = tokens.muted,
                    modifier = Modifier.padding(20.dp).testTag("$TAG:empty"),
                )
            }
            shown.groupBy { it.group }.forEach { (group, inGroup) ->
                Text(
                    text = group,
                    style = tokens.label.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                )
                inGroup.forEachIndexed { index, each ->
                    if (index > 0) RowDivider()
                    ItemRow(each, instance, strings)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = strings.demoHint,
                style = tokens.footnote,
                color = tokens.muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            AppButton(
                text = strings.resetDemo,
                onClick = { instance.resetDemo() },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("$TAG:reset"),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Chip(label: String, count: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) tokens.primary else tokens.surface)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = tokens.label.copy(fontWeight = FontWeight.Medium),
            color = if (selected) tokens.primaryForeground else tokens.foreground,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            style = tokens.label,
            color = if (selected) tokens.primaryForeground.copy(alpha = 0.7f) else tokens.muted,
        )
    }
}

/** "Безлимит", "Нет в наличии" or "7 шт" — core's three readings of one number. */
private fun StockStrings.balanceOf(item: StockItem): String = when {
    item.unlimited -> unlimited
    item.balance == 0 -> outOfStock
    else -> left(item.balance, item.unit)
}

@Composable
private fun ItemRow(item: StockItem, instance: StockInstance, strings: StockStrings) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { instance.open(item.id) }
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            .testTag("$TAG:row-${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = tokens.body,
                color = if (item.enabled) tokens.foreground else tokens.disabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rub(item.price) + if (item.enabled) "" else " · " + strings.off,
                style = tokens.label,
                color = tokens.muted,
            )
        }
        if (item.limited) {
            AppButton(
                text = "−",
                onClick = { instance.minus(item.id) },
                enabled = item.balance > 0,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$TAG:minus-${item.id}"),
            )
        }
        Text(
            text = strings.balanceOf(item),
            style = tokens.label.copy(fontWeight = FontWeight.Medium),
            color = when {
                item.unlimited -> tokens.muted
                item.balance == 0 -> tokens.danger
                item.balance <= 3 -> tokens.warning
                else -> tokens.foreground
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 6.dp).testTag("$TAG:balance-${item.id}"),
        )
        if (item.limited) {
            AppButton(
                text = "+",
                onClick = { instance.plus(item.id) },
                variant = ButtonVariant.Outline,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$TAG:plus-${item.id}"),
            )
        }
    }
}

@Composable
private fun ItemPage(item: StockItem, instance: StockInstance, strings: StockStrings) {
    val tokens = AppTheme
    var typed by remember(item.id) { mutableStateOf(if (item.balance > 0) item.balance.toString() else "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.pageBackground)
            .verticalScroll(rememberScrollState())
            .testTag("$TAG:item"),
    ) {
        AppButton(
            text = "← " + strings.back,
            onClick = { instance.back() },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.padding(8.dp).testTag("$TAG:back"),
        )
        Text(
            text = item.name,
            style = tokens.header,
            color = tokens.foreground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            text = item.group + " · " + rub(item.price),
            style = tokens.label,
            color = tokens.muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Text(
            text = strings.balanceOf(item),
            style = tokens.subtitle.copy(fontWeight = FontWeight.SemiBold),
            color = if (item.balance == 0) tokens.danger else tokens.foreground,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).testTag("$TAG:balance"),
        )

        Section(title = strings.balance, hint = strings.balanceHint) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(
                    value = typed,
                    onValueChange = { text -> typed = text.filter { it.isDigit() }.take(5) },
                    placeholder = "0",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("$TAG:balance-field"),
                )
                AppButton(
                    text = strings.setBalance,
                    onClick = { typed.toIntOrNull()?.let { instance.setBalance(item.id, it) } },
                    enabled = typed.toIntOrNull() != null,
                    size = ButtonSize.Default,
                    modifier = Modifier.fillMaxWidth().testTag("$TAG:set"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        text = strings.unlimited,
                        onClick = { instance.unlimited(item.id) },
                        enabled = !item.unlimited,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Default,
                        modifier = Modifier.weight(1f).testTag("$TAG:unlimited"),
                    )
                    AppButton(
                        text = strings.outOfStock,
                        onClick = { instance.stop(item.id) },
                        enabled = item.balance != 0,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Default,
                        modifier = Modifier.weight(1f).testTag("$TAG:stop"),
                    )
                }
            }
        }

        Section(title = strings.sale) {
            ToggleRow(
                label = strings.onSale,
                checked = item.enabled,
                onCheckedChange = { instance.setEnabled(item.id, it) },
                modifier = Modifier.testTag("$TAG:enabled"),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
