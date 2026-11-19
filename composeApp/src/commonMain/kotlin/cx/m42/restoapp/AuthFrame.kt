package cx.m42.restoapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.FlaskConical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.UtensilsCrossed
import com.composables.icons.lucide.Cloud as CloudIcon
import cx.m42.superizer.kit.Icon
import cx.m42.superizer.kit.IconTile
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.palette
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Text
import cx.m42.superizer.kit.TextVariant
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.kit.kitClickable

/**
 * The frame of the host's focused flows — connecting a server, signing in again — as the prototype
 * drew them (`prototype-rikka/cloudApp`, Auth.kt): one column of at most 440 dp, and on a desktop the
 * brand panel beside it. The shell's bar above stays: back and the title are the shell's.
 */
@Composable
internal fun AuthFrame(words: HostWords, tag: String, content: @Composable ColumnScope.() -> Unit) {
    val window = LocalWindowClass.current
    Row(Modifier.fillMaxSize().background(Kit.colors.background).testTag(tag)) {
        if (window == WindowClass.Expanded) BrandPanel(words, Modifier.weight(0.9f).fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = if (window == WindowClass.Compact) 20.dp else 56.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) { content() }
        }
    }
}

/** The title of a step and a line under it: what this step is for. */
@Composable
internal fun AuthHeading(title: String?, text: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, variant = if (LocalWindowClass.current == WindowClass.Compact) TextVariant.H3 else TextVariant.H2)
        if (text != null) Text(text, style = Kit.typography.p, color = Kit.colors.mutedForeground)
    }
}

/**
 * A way to go, as a card: an icon, what it is, a line about it. A chosen one (the cloud being
 * connected) is outlined and ticked; the others lead on, with a chevron.
 */
@Composable
internal fun AuthOption(
    icon: ImageVector,
    tone: Tone,
    title: String,
    text: String,
    tag: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Kit.palette.brandSoft else Kit.colors.card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Kit.palette.brand else Kit.colors.border, shape)
            .kitClickable(onClick = onClick)
            .semantics {
                role = Role.Button
                this.selected = selected
            }
            .padding(16.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, tone, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Kit.typography.p.copy(fontWeight = FontWeight.SemiBold))
            Text(text, variant = TextVariant.Muted)
        }
        if (selected) {
            Icon(Lucide.CircleCheck, contentDescription = null, size = 20.dp, tint = Kit.palette.brand)
        } else {
            Icon(Lucide.ChevronRight, contentDescription = null, size = 18.dp, tint = Kit.colors.mutedForeground)
        }
    }
}

/** The icons of the three ways in, so the screens do not import them one by one. */
internal object AuthIcons {
    val cloud: ImageVector get() = Lucide.CloudIcon
    val server: ImageVector get() = Lucide.Server
    val demo: ImageVector get() = Lucide.FlaskConical
}

/**
 * The brand's side of a desktop's flow: the accent, deepened, with what RestoApp is in three lines.
 * No figures — nothing here pretends to be a server's data.
 */
@Composable
private fun BrandPanel(words: HostWords, modifier: Modifier) {
    val white = Color.White
    val brand = Kit.palette.brand
    val hero = Brush.linearGradient(listOf(lerp(brand, Color.Black, 0.15f), lerp(brand, Color.Black, 0.55f)))
    Box(modifier.padding(12.dp).clip(RoundedCornerShape(24.dp)).background(hero).testTag("auth:brand")) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = 120.dp, y = (-120).dp).size(420.dp).clip(CircleShape).background(white.copy(alpha = 0.06f)))
        Box(Modifier.align(Alignment.BottomStart).offset(x = (-140).dp, y = 160.dp).size(460.dp).clip(CircleShape).background(white.copy(alpha = 0.05f)))
        Column(Modifier.fillMaxSize().padding(40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(white.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Lucide.UtensilsCrossed, contentDescription = null, size = 18.dp, tint = white)
                }
                Text("RestoApp", style = Kit.typography.large, color = white)
            }
            Spacer(Modifier.weight(1f))
            Text(words.brandTitle, style = Kit.typography.h1.copy(fontSize = 36.sp, lineHeight = 42.sp), color = white)
            Spacer(Modifier.height(14.dp))
            Text(words.brandLead, Modifier.widthIn(max = 420.dp), style = Kit.typography.lead, color = white.copy(alpha = 0.82f))
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Lucide.Server to words.brandOwn,
                    Lucide.CloudIcon to words.brandCloud,
                    Lucide.ShieldCheck to words.brandSafe,
                ).forEach { (icon, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(icon, contentDescription = null, size = 15.dp, tint = white.copy(alpha = 0.85f))
                        Text(text, style = Kit.typography.small, color = white.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}
