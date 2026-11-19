package cx.m42.restoapp.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Banknote
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronsUpDown
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.House
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.MapPinOff
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Receipt
import com.composables.icons.lucide.ShoppingBag
import com.composables.icons.lucide.Ticket
import com.composables.icons.lucide.User
import com.composables.icons.lucide.UtensilsCrossed
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.render.money
import cx.m42.superizer.kit.AppSections
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize
import cx.m42.superizer.kit.ButtonVariant
import cx.m42.superizer.kit.ActionBar
import cx.m42.superizer.kit.ChoiceItem
import cx.m42.superizer.kit.Chip
import cx.m42.superizer.kit.EmptyState
import cx.m42.superizer.kit.FactRow
import cx.m42.superizer.kit.Grid
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.IconTile
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Notice
import cx.m42.superizer.kit.Page
import cx.m42.superizer.kit.PageHeader
import cx.m42.superizer.kit.PageWidth
import cx.m42.superizer.kit.Panel
import cx.m42.superizer.kit.Pill
import cx.m42.superizer.kit.SectionItem
import cx.m42.superizer.kit.SectionTitle
import cx.m42.superizer.kit.Separator
import cx.m42.superizer.kit.StatusBadge
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.QtyStepper
import cx.m42.superizer.kit.byWindow
import cx.m42.superizer.kit.kitClickable
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.textColor
import cx.m42.superizer.kit.textStyles
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.ui.platform.ScreenSteps
import cx.m42.superizer.ui.platform.SystemBackHandler
import cx.m42.superizer.ui.platform.hostDrawsBack

/*
 * The till. The open receipts of the point, the receipt of one of them, the catalog to add from, and
 * the questions of the order as dialogs.
 *
 * A phone shows one of these at a time, switched by the app's own sections at the bottom — «Чеки»,
 * «Чек», «Каталог» — with the total and the one next step in a bar above them. A window wide enough
 * for both (Expanded, and Medium from 700 dp — a tablet in portrait beside the rail) is one screen:
 * the catalog, and the receipt as a fixed pane on the right with its total and its button, the open
 * receipts as chips over the catalog. Narrower Medium windows take the phone's flow, with the
 * sections as tabs in the page header.
 *
 * Nothing here decides anything: every number is the server's, every step is a call of [PosInstance].
 */

/** Everything a screen of the till needs, gathered once. */
internal class PosContext(
    val instance: PosInstance,
    val state: PosState,
    val words: PosWords,
    val currency: String?,
    /** Catalog and receipt side by side. */
    val twoPane: Boolean,
    val place: Place?,
    /** The group the catalog opens at: the one last looked at. */
    val lastGroup: String?,
) {
    fun money(value: Double): String = money(value, currency)
}

/** Below this width a Medium window takes the phone's flow: a catalog of 360 dp beside a 340 dp receipt is the least that reads. */
private val TwoPaneFrom = 700.dp

@Composable
internal fun PosScreen(instance: PosInstance) {
    val runtime = LocalAppRuntime.current
    val lang by runtime.locale.langTag.collectAsState()
    val words = remember(lang) { PosWords(lang) }
    val state by instance.state.collectAsState()
    val facts by instance.server.facts.collectAsState()
    val places by instance.server.places.collectAsState()
    val place by instance.server.place.collectAsState()
    LaunchedEffect(instance) { instance.start() }

    val cls = LocalWindowClass.current
    val density = LocalDensity.current
    var width by remember { mutableStateOf(0.dp) }
    val twoPane = cls == WindowClass.Expanded || (cls == WindowClass.Medium && width >= TwoPaneFrom)

    // What the screen remembers on its own: the group the catalog was last at, the receipt that was
    // open last (where «Чек» leads back to), and a catalog asked for before its receipt exists.
    var lastGroup by remember { mutableStateOf<String?>(null) }
    var lastOrder by remember { mutableStateOf<String?>(null) }
    var pendingCatalog by remember { mutableStateOf(false) }
    var pointDialog by remember { mutableStateOf(false) }
    state.order?.id?.let { if (it != lastOrder) lastOrder = it }
    state.catalog?.let { catalog ->
        val top = catalog.path.firstOrNull()?.id ?: catalog.group
        if (top != null && top != lastGroup) lastGroup = top
    }

    val context = PosContext(instance, state, words, facts?.currency, twoPane, place, lastGroup)
    val order = state.order
    val catalog = state.catalog

    // A receipt asked for from the catalog's section before there was one: once it is there, its catalog.
    LaunchedEffect(order?.id, pendingCatalog) {
        if (pendingCatalog && order != null) {
            pendingCatalog = false
            instance.openCatalog(lastGroup)
        }
    }
    // Side by side the catalog is always there: a receipt opened on a wide window opens it too.
    LaunchedEffect(twoPane, order?.id, catalog == null, state.placed == null) {
        if (twoPane && order != null && catalog == null && state.placed == null) instance.openCatalog(lastGroup)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Kit.palette.canvas)
            .onSizeChanged { width = with(density) { it.width.toDp() } }
            .testTag("pos"),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (state.notice != null || state.error != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = byWindow(16.dp, 24.dp, 32.dp), vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.notice?.let {
                        Notice(Tone.Warn, iconOf(Tone.Warn), words.attention, it, Modifier.testTag("pos:notice")) {
                            Button(words.hide, instance::dismissNotice, Modifier.testTag("pos:notice:close"), variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
                        }
                    }
                    state.error?.let {
                        Notice(Tone.Danger, iconOf(Tone.Danger), words.failed, it, Modifier.testTag("pos:error")) {
                            Button(words.retry, { instance.reload() }, Modifier.testTag("pos:error:retry"), variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val searching = catalog?.query?.isNotEmpty() == true
                ScreenSteps(
                    when {
                        state.placed != null -> 1
                        order == null -> 0
                        twoPane -> 1 + (if (searching) 1 else 0)
                        catalog != null -> 2 + (catalog.path.size - 1).coerceAtLeast(0) + (if (searching) 1 else 0)
                        else -> 1
                    },
                )
                when {
                    places.isEmpty() -> Page(PageWidth.Narrow) {
                        EmptyState(Lucide.MapPinOff, words.noPlaces, words.noPlacesHint, Modifier.testTag("pos:no-places"))
                    }
                    state.placed != null -> {
                        SystemBackHandler(enabled = true) { instance.done() }
                        Placed(context)
                    }
                    twoPane && order != null -> {
                        SystemBackHandler(enabled = true) { if (searching) instance.catalogSearch("") else instance.close() }
                        Till(context, order, width)
                    }
                    twoPane -> Drafts(context, places.size > 1) { pointDialog = true }
                    else -> {
                        if (order != null) {
                            SystemBackHandler(enabled = true) {
                                when {
                                    catalog == null -> instance.close()
                                    searching -> instance.catalogSearch("")
                                    catalog.path.size > 1 -> instance.catalogBack()
                                    else -> instance.closeCatalog()
                                }
                            }
                        }
                        val selected = when {
                            order == null -> SECTION_DRAFTS
                            catalog != null -> SECTION_CATALOG
                            else -> SECTION_RECEIPT
                        }
                        AppSections(
                            items = listOf(
                                SectionItem(SECTION_DRAFTS, words.receiptsTab, Lucide.LayoutGrid, state.drafts.size.takeIf { it > 0 }?.toString()),
                                SectionItem(SECTION_RECEIPT, words.receiptTab, Lucide.Receipt),
                                SectionItem(SECTION_CATALOG, words.catalog, Lucide.UtensilsCrossed),
                            ),
                            selected = selected,
                            onSelect = { id ->
                                when (id) {
                                    SECTION_DRAFTS -> if (order != null) instance.close()
                                    SECTION_RECEIPT -> when {
                                        order != null -> if (catalog != null) instance.closeCatalog()
                                        else -> reopen(context, lastOrder)
                                    }
                                    SECTION_CATALOG -> when {
                                        order != null -> if (catalog == null) instance.openCatalog(lastGroup)
                                        else -> {
                                            pendingCatalog = true
                                            reopen(context, lastOrder)
                                        }
                                    }
                                }
                            },
                        ) {
                            when {
                                order != null && catalog != null -> CatalogPage(context, order)
                                order != null -> ReceiptPage(context, order)
                                else -> Drafts(context, places.size > 1) { pointDialog = true }
                            }
                        }
                    }
                }
            }
        }
        PosSheets(context)
        if (pointDialog) PointDialog(context, places) { pointDialog = false }
    }
}

private const val SECTION_DRAFTS = "drafts"
private const val SECTION_RECEIPT = "receipt"
private const val SECTION_CATALOG = "catalog"

/** «Чек» with no receipt open: the one that was open last, or the newest draft, or a new one. */
private fun reopen(context: PosContext, last: String?) {
    val drafts = context.state.drafts
    val id = last?.takeIf { id -> drafts.any { it.id == id } } ?: drafts.firstOrNull()?.id
    if (id != null) context.instance.open(id) else context.instance.newOrder()
}

// ───────────────────────────── the open receipts ─────────────────────────────

@Composable
private fun Drafts(context: PosContext, choosePoint: Boolean, onPoint: () -> Unit) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val compact = LocalWindowClass.current == WindowClass.Compact
    Page {
        PageHeader(
            title = words.pos,
            description = listOfNotNull(words.drafts, context.place?.title?.takeIf { !choosePoint }).joinToString(" · "),
            actions = {
                if (choosePoint && context.place != null) PointButton(context.place, onPoint)
                Button(
                    words.newOrder,
                    { instance.newOrder() },
                    (if (compact) Modifier.weight(1f) else Modifier).testTag("pos:new"),
                    enabled = !state.busy,
                    leadingIcon = Lucide.Plus,
                )
            },
        )
        when {
            state.drafts.isNotEmpty() -> {
                SectionTitle(words.openReceipts, hint = state.drafts.size.toString())
                Grid(byWindow(1, 2, 3), gap = byWindow(12.dp, 16.dp, 16.dp)) {
                    state.drafts.forEach { DraftCard(context, it) }
                }
            }
            state.loading && !state.loaded -> Text(words.loading, Modifier.testTag("pos:loading"), variant = TextVariant.Muted)
            else -> EmptyState(Lucide.Receipt, words.noDrafts, words.noDraftsHint, Modifier.testTag("pos:empty"))
        }
    }
}

/** The point the till works at, as a field-like button: tapped, the points to choose from. */
@Composable
private fun PointButton(place: Place, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(shape)
            .background(Kit.colors.card)
            .border(1.dp, Kit.colors.border, shape)
            .kitClickable(shape, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("pos:place"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.MapPin, null, size = 16.dp, tint = Kit.colors.mutedForeground)
        Text(place.title, style = Kit.textStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Lucide.ChevronsUpDown, null, size = 14.dp, tint = Kit.colors.mutedForeground)
    }
}

@Composable
private fun PointDialog(context: PosContext, places: List<Place>, onDismiss: () -> Unit) {
    val words = context.words
    PosDialog(
        tag = "pos:places",
        title = words.places,
        onDismiss = onDismiss,
        words = words,
        footer = { PosActions(null, {}, "pos:places:ok", words.close, onDismiss, "pos:places:close") },
    ) {
        Column {
            places.forEach { place ->
                ChoiceItem(
                    title = place.title,
                    selected = place.id == context.place?.id,
                    onClick = {
                        onDismiss()
                        context.instance.server.selectPlace(place.id)
                    },
                    modifier = Modifier.testTag("pos:places:item-${place.id}"),
                    description = place.address,
                )
            }
        }
    }
}

@Composable
private fun DraftCard(context: PosContext, order: PosOrder) {
    val words = context.words
    Panel(Modifier.testTag("pos:draft-${order.id}"), onClick = { context.instance.open(order.id) }, gap = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(serviceIcon(order.serviceType), Tone.Brand, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("№ ${order.number}", style = Kit.typography.p.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                if (order.createdAt > 0) Text(words.clock(order.createdAt), style = Kit.textStyles.caption, color = Kit.colors.mutedForeground, maxLines = 1)
            }
            StatusBadge(words.draft, Tone.Neutral)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(receivingOf(context, order), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                order.customerName?.let { name -> listOfNotNull(name, order.customerPhone).joinToString(" · ") } ?: words.noGuest,
                variant = TextVariant.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Separator()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (order.dishesCount == 0) words.emptyYet else words.positions(order.dishesCount), Modifier.weight(1f), variant = TextVariant.Muted)
            Text(context.money(order.total), style = Kit.textStyles.number.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

// ───────────────────────────── one pane at a time ─────────────────────────────

@Composable
private fun ReceiptPage(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Page(PageWidth.Narrow, Modifier.testTag("pos:receipt")) {
                // The way back to the receipts — only where the host has no arrow of its own (it goes
                // through the same `SystemBackHandler`).
                if (!hostDrawsBack) {
                    Button(words.back, instance::close, Modifier.testTag("pos:back"), variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
                }
                ReceiptHeader(context, order, big = true)
                Panel(flush = true) {
                    if (order.lines.isEmpty()) {
                        EmptyReceipt(words.emptyReceipt, words.emptyReceiptHint)
                    } else {
                        order.lines.forEachIndexed { i, line ->
                            if (i > 0) Separator()
                            LineRow(context, line, 16.dp)
                        }
                    }
                    Separator()
                    AddDishesRow(words.addDishes, enabled = !state.busy) { instance.openCatalog(context.lastGroup) }
                }
                Panel(flush = true) { FieldRows(context, order, 16.dp) }
                Panel(gap = 10.dp) { Totals(context, order) }
                Messages(context, order)
            }
        }
        ActionBar {
            Column(Modifier.weight(1f)) {
                Text(words.total, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
                Text(context.money(order.total), Modifier.testTag("pos:bar-total"), style = Kit.typography.large.copy(fontFeatureSettings = "tnum"), maxLines = 1)
            }
            PrimaryAction(context, order, Modifier.weight(1.6f))
        }
    }
}

// ───────────────────────────── the till (side by side) ─────────────────────────────

@Composable
private fun Till(context: PosContext, order: PosOrder, width: Dp) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val receiptWidth = if (width >= 1000.dp) 420.dp else 340.dp
    val catalogWidth = width - receiptWidth - byWindow(32.dp, 48.dp, 64.dp)
    val columns = when {
        catalogWidth >= 700.dp -> 4
        catalogWidth >= 480.dp -> 3
        catalogWidth >= 400.dp -> 2
        else -> 1
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // The open receipts as tabs: a waiter keeps several tables at once.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = byWindow(16.dp, 24.dp, 32.dp), vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (LocalWindowClass.current == WindowClass.Expanded) {
                    Text(words.receiptsTab.uppercase(), style = Kit.textStyles.overline, color = Kit.colors.mutedForeground)
                    Spacer(Modifier.width(4.dp))
                }
                val tabs = if (state.drafts.any { it.id == order.id }) state.drafts else listOf(order) + state.drafts
                tabs.forEach { other ->
                    Chip(
                        "№ ${other.number} · ${context.money(if (other.id == order.id) order.total else other.total)}",
                        other.id == order.id,
                        { if (other.id != order.id) instance.open(other.id) },
                        Modifier.testTag("pos:tab-${other.id}"),
                    )
                }
                Button(words.newShort, { instance.newOrder() }, Modifier.testTag("pos:tab-new"), variant = ButtonVariant.Ghost, size = ButtonSize.Sm, enabled = !state.busy, leadingIcon = Lucide.Plus)
                Button(words.allReceipts, instance::close, Modifier.testTag("pos:back"), variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
            }
            Separator()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Page(PageWidth.Wide, Modifier.testTag("pos:catalog")) { CatalogBody(context, order, columns) }
            }
        }
        ReceiptPane(context, order, receiptWidth)
    }
}

@Composable
private fun ReceiptPane(context: PosContext, order: PosOrder, width: Dp) {
    val words = context.words
    val border = Kit.colors.border
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Kit.colors.card)
            .drawBehind { drawLine(border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
            .testTag("pos:receipt"),
    ) {
        Box(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 14.dp)) { ReceiptHeader(context, order, big = false) }
        Separator()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (order.lines.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp).testTag("pos:no-lines"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconTile(Lucide.ShoppingBag, Tone.Brand, 44.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(words.emptyReceipt, style = Kit.textStyles.label, textAlign = TextAlign.Center)
                    Text(words.emptyReceiptWide, variant = TextVariant.Muted, textAlign = TextAlign.Center)
                }
            } else {
                order.lines.forEachIndexed { i, line ->
                    if (i > 0) Separator(inset = 20.dp)
                    LineRow(context, line, 20.dp)
                }
            }
            Box(Modifier.fillMaxWidth().background(Kit.colors.muted.copy(alpha = 0.5f)).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(words.orderSection.uppercase(), style = Kit.textStyles.overline, color = Kit.colors.mutedForeground)
            }
            FieldRows(context, order, 20.dp)
        }
        Separator()
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Totals(context, order)
            Messages(context, order)
            PrimaryAction(context, order, Modifier.fillMaxWidth())
        }
    }
}

// ───────────────────────────── receipt parts ─────────────────────────────

/** «№ D1001 · Черновик», how it is handed over, and the `⋯` with «Бросить заказ». */
@Composable
private fun ReceiptHeader(context: PosContext, order: PosOrder, big: Boolean) {
    val words = context.words
    val cls = LocalWindowClass.current
    val subtitle = receivingOf(context, order)
    if (big && cls != WindowClass.Compact) {
        // The page header of a one-pane window wider than a phone: it carries the sections' tabs.
        PageHeader(
            title = "№ ${order.number}",
            description = subtitle,
            badge = { StatusBadge(words.draft, Tone.Neutral) },
            actions = { ReceiptMore(context, order) },
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "№ ${order.number}",
                    Modifier.weight(1f, fill = false).testTag("pos:number"),
                    variant = if (big) TextVariant.H3 else TextVariant.H4,
                    maxLines = 1,
                )
                StatusBadge(words.draft, Tone.Neutral)
            }
            Text(subtitle, Modifier.testTag("pos:service"), variant = TextVariant.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ReceiptMore(context, order)
    }
}

@Composable
private fun EmptyReceipt(title: String, hint: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp).testTag("pos:no-lines"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = Kit.textStyles.label)
        Text(hint, variant = TextVariant.Muted)
    }
}

@Composable
private fun AddDishesRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().kitClickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp).testTag("pos:add"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Kit.palette.brandSoft), contentAlignment = Alignment.Center) {
            Icon(Lucide.Plus, null, size = 15.dp, tint = Kit.palette.onBrandSoft)
        }
        Text(text, Modifier.weight(1f), style = Kit.textStyles.label, color = Kit.palette.onBrandSoft)
        Icon(Lucide.ChevronRight, null, size = 16.dp, tint = Kit.colors.mutedForeground)
    }
}

/** One line of the receipt: the product, what goes with it, its − n +, its note, and what it costs. */
@Composable
private fun LineRow(context: PosContext, line: PosLine, pad: Dp) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 12.dp).testTag("pos:line-${line.lineId}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.name, style = Kit.textStyles.label)
                // What was chosen with it, as the kitchen will read it: «Острый · Сыр ×2».
                val sub = listOfNotNull(
                    line.options.takeIf { it.isNotEmpty() }?.joinToString(" · ") { option -> option.name + if (option.amount > 1) " ×${option.amount}" else "" },
                    "${context.money(line.price)} ${words.perItem}".takeIf { line.amount > 1 },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = Kit.textStyles.caption, color = Kit.colors.mutedForeground)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    context.money(line.total),
                    Modifier.testTag("pos:line-${line.lineId}:total"),
                    style = Kit.textStyles.number.copy(fontWeight = FontWeight.SemiBold),
                )
                line.oldPrice?.let {
                    Text(
                        context.money(it * line.amount),
                        style = Kit.textStyles.caption.copy(textDecoration = TextDecoration.LineThrough),
                        color = Kit.colors.mutedForeground,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // At 1 the minus is a bin: taking the last one off is the same gesture, and the server drops the line.
            Box(Modifier.testTag("pos:line-${line.lineId}:amount")) {
                QtyStepper(line.amount, { instance.setLine(line.lineId, it) }, min = 1, removeLabel = words.removeLine)
            }
            LineLink(
                icon = Lucide.MessageSquare,
                text = line.comment?.takeIf { it.isNotBlank() }?.let { "«$it»" } ?: words.comment,
                accent = !line.comment.isNullOrBlank(),
                enabled = !state.busy,
                tag = "pos:line-${line.lineId}:comment",
                modifier = Modifier.weight(1f, fill = false),
            ) { instance.openSheet(PosSheet.LineComment, line.lineId) }
            // Only where there is something to choose: the server says so on the line.
            if (line.hasOptions) {
                LineLink(Lucide.UtensilsCrossed, words.options, false, !state.busy, "pos:line-${line.lineId}:options") {
                    instance.openOptions(lineId = line.lineId)
                }
            }
        }
    }
}

@Composable
private fun LineLink(
    icon: ImageVector,
    text: String,
    accent: Boolean,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val color = if (accent) Kit.palette.onBrandSoft else Kit.colors.mutedForeground
    Row(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .kitClickable(RoundedCornerShape(7.dp), enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, size = 14.dp, tint = color)
        Text(text, style = Kit.textStyles.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The questions of the order as rows: each with its answer, each a dialog when tapped. */
@Composable
private fun FieldRows(context: PosContext, order: PosOrder, pad: Dp) {
    val words = context.words
    val rows = buildList {
        add(FieldRowSpec(PosSheet.Service, Lucide.MapPin, words.receiving, receivingOf(context, order), "pos:step-service"))
        if (order.serviceType == PosOrder.DELIVERY) add(FieldRowSpec(PosSheet.Address, Lucide.House, words.address, order.address, "pos:step-address"))
        add(FieldRowSpec(PosSheet.Guest, Lucide.User, words.guest, order.customerName, "pos:step-guest"))
        add(FieldRowSpec(PosSheet.Time, Lucide.Clock, words.time, timeOf(context, order), "pos:step-time"))
        add(FieldRowSpec(PosSheet.Payment, Lucide.Banknote, words.payment, order.paymentMethodTitle, "pos:step-payment"))
        add(FieldRowSpec(PosSheet.Promocode, Lucide.Ticket, words.promocode, order.promotionCode, "pos:step-promocode"))
        add(
            FieldRowSpec(
                PosSheet.Details, Lucide.MessageSquare, words.details,
                listOfNotNull(order.comment?.takeIf { it.isNotBlank() }, order.personsCount?.let { "${words.persons}: $it" }).joinToString(" · ").ifEmpty { null },
                "pos:step-details",
            ),
        )
    }
    rows.forEachIndexed { i, row ->
        if (i > 0) Separator(inset = pad + 28.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .kitClickable { context.instance.openSheet(row.sheet) }
                .padding(horizontal = pad, vertical = 10.dp)
                .testTag(row.tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(row.icon, null, size = 16.dp, tint = Kit.colors.mutedForeground)
            Text(row.label, Modifier.width(byWindow(96.dp, 96.dp, 88.dp)), variant = TextVariant.Muted, maxLines = 1)
            Text(
                row.value ?: words.notSet,
                Modifier.weight(1f),
                color = if (row.value == null) Kit.colors.mutedForeground else Kit.colors.foreground,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Lucide.ChevronRight, null, size = 14.dp, tint = Kit.colors.mutedForeground)
        }
    }
}

private class FieldRowSpec(val sheet: PosSheet, val icon: ImageVector, val label: String, val value: String?, val tag: String)

/** The totals as the server counted them, in its order: products, discount, delivery, bonuses, total. */
@Composable
private fun Totals(context: PosContext, order: PosOrder) {
    val words = context.words
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FactRow("${words.basket} · ${order.dishesCount}", context.money(order.basketTotal), Modifier.testTag("pos:basket"))
        if (order.discountTotal != 0.0) {
            FactRow(listOfNotNull(words.discount, order.promotionCode).joinToString(" · "), modifier = Modifier.testTag("pos:discount")) {
                Text("−" + context.money(order.discountTotal), style = Kit.textStyles.number, color = Tone.Success.textColor())
            }
        }
        if (order.deliveryCost != 0.0) FactRow(words.delivery, context.money(order.deliveryCost), Modifier.testTag("pos:delivery"))
        if (order.bonusesTotal != 0.0) {
            FactRow(words.bonuses, modifier = Modifier.testTag("pos:bonuses")) {
                Text("−" + context.money(order.bonusesTotal), style = Kit.textStyles.number, color = Tone.Success.textColor())
            }
        }
        // A delivery the server gave to another kitchen: said where it is cooked.
        order.cookingPlaceTitle
            ?.takeIf { order.cookingPlaceId != null && order.cookingPlaceId != order.placeId }
            ?.let { FactRow(words.cooks, modifier = Modifier.testTag("pos:cooks")) { Pill(it, Tone.Warn) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(words.total, Modifier.weight(1f), style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold))
        Text(context.money(order.total), Modifier.testTag("pos:total"), style = Kit.typography.h4.copy(fontFeatureSettings = "tnum"))
    }
}

/** The server's word on the last step, and its note about the order: on screen until the next step. */
@Composable
private fun Messages(context: PosContext, order: PosOrder) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    state.message?.let { PosNotice(words, it.tone, it.text, "pos:message", instance::dismissMessage) }
    order.message?.takeIf { it.isNotBlank() && it != state.message?.text }?.let { PosNotice(words, null, it, "pos:server-note") }
}

/** The check, then the order: two steps, one place for the button, which changes its words. */
@Composable
private fun PrimaryAction(context: PosContext, order: PosOrder, modifier: Modifier) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    if (order.canPlace) {
        Button(
            "${words.place} · ${context.money(order.total)}",
            instance::place,
            modifier.testTag("pos:place-order"),
            size = ButtonSize.Lg,
            enabled = !state.busy,
            leadingIcon = Lucide.Check,
        )
    } else {
        Button(
            words.check,
            instance::check,
            modifier.testTag("pos:check"),
            size = ButtonSize.Lg,
            enabled = !state.busy && order.canCheck,
        )
    }
}

// ───────────────────────────── accepted ─────────────────────────────

/** «Заказ принят»: the number to say out loud, what was agreed, and the way on. */
@Composable
private fun Placed(context: PosContext) {
    val (instance, state, words) = Triple(context.instance, context.state, context.words)
    val order = state.placed ?: return
    val compact = LocalWindowClass.current == WindowClass.Compact
    Page(PageWidth.Narrow, Modifier.testTag("pos:placed")) {
        Column(
            Modifier.fillMaxWidth().padding(top = byWindow(16.dp, 48.dp, 64.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconTile(Lucide.CircleCheck, Tone.Success, 64.dp)
            Spacer(Modifier.height(8.dp))
            Text(words.accepted, variant = if (compact) TextVariant.H3 else TextVariant.H2, textAlign = TextAlign.Center)
            Text(
                words.passedTo(order.number, order.cookingPlaceTitle ?: context.place?.title),
                Modifier.testTag("pos:placed-number"),
                variant = TextVariant.Muted,
                textAlign = TextAlign.Center,
            )
        }
        Panel(gap = 10.dp) {
            FactRow(words.receiving, receivingOf(context, order))
            FactRow(words.guest, order.customerName ?: words.noCustomer)
            order.address?.let { FactRow(words.address, it) }
            FactRow(words.time, timeOf(context, order))
            FactRow(words.payment, order.paymentMethodTitle ?: words.notSet)
            if (order.discountTotal != 0.0) FactRow(words.discount, "−" + context.money(order.discountTotal))
            Separator()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(words.total, Modifier.weight(1f), style = Kit.textStyles.label.copy(fontWeight = FontWeight.SemiBold))
                Text(context.money(order.total), style = Kit.typography.large.copy(fontFeatureSettings = "tnum"))
            }
        }
        state.message?.let { PosNotice(words, it.tone, it.text, "pos:placed-message", title = words.serverSays) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(words.newOrderAgain, { instance.newOrder() }, Modifier.fillMaxWidth().testTag("pos:placed-new"), size = ButtonSize.Lg, leadingIcon = Lucide.Plus)
            Button(words.toOpenReceipts, instance::done, Modifier.fillMaxWidth().testTag("pos:placed-done"), variant = ButtonVariant.Outline, size = ButtonSize.Lg)
        }
    }
}

// ───────────────────────────── words of a receipt ─────────────────────────────

/** What the «Получение» row reads: the way, and the table when there is one. */
internal fun receivingOf(context: PosContext, order: PosOrder): String =
    listOfNotNull(
        context.words.serviceType(order.serviceType),
        order.table?.let { "${context.words.table} $it" },
    ).joinToString(" · ")

/** What the «Время» row reads. */
internal fun timeOf(context: PosContext, order: PosOrder): String = when {
    order.maxWaitMinutes != null -> "${context.words.maxWait} ${order.maxWaitMinutes} ${context.words.minutes}"
    order.date != null -> context.words.clock(order.date)
    else -> context.words.asap
}
