package cx.m42.restoapp

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cx.m42.superizer.kit.KitTypography

/**
 * RestoApp's type: one step denser than the kit's default (15/13 sp body instead of 16/14), as the
 * prototype was drawn (`prototype-rikka/cloudApp`, theme/Theme.kt) — an admin reads tables and
 * numbers at a desk as often as on a phone. The platform's own font: on every target RestoApp runs
 * on it has Cyrillic.
 */
internal val RestoTypography: KitTypography = run {
    val family = FontFamily.Default
    fun s(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
        TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.em)
    KitTypography(
        h1 = s(34, 40, FontWeight.Bold, -0.025),
        h2 = s(28, 34, FontWeight.SemiBold, -0.022),
        h3 = s(22, 28, FontWeight.SemiBold, -0.018),
        h4 = s(18, 26, FontWeight.SemiBold, -0.012),
        p = s(15, 22),
        lead = s(17, 26),
        large = s(16, 24, FontWeight.SemiBold, -0.006),
        small = s(13, 18, FontWeight.Medium),
        muted = s(13, 18),
        mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp),
    )
}

/** The accent a fresh install starts with; the person picks another in Settings. */
internal const val RESTO_ACCENT: String = "violet"
