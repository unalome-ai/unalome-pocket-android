package ai.unalome.pocket.design

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Inter (bundled variable font, SIL OFL 1.1), one instance per weight on its `wght` axis. */
@OptIn(ExperimentalTextApi::class)
val Inter: FontFamily = FontFamily(
    listOf(400, 500, 600, 700, 800).map { weight ->
        Font(
            resId = R.font.inter_variable,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/**
 * The app's text styles, matching the iOS `UnalomeTextStyle` sizes in sp so they follow the
 * system font scale. Monospaced code uses the platform monospace face.
 */
object UnalomeType {
    private fun inter(size: Int, weight: FontWeight, line: Float = 1.35f, tracking: Float = 0f) = TextStyle(
        fontFamily = Inter,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * line).sp,
        letterSpacing = tracking.em,
    )

    /** Two-part headlines ("Nothing leaves / this phone."): extrabold, tight tracking. */
    val Display = inter(34, FontWeight.ExtraBold, line = 1.15f, tracking = -0.025f)
    val Title = inter(22, FontWeight.Bold, line = 1.25f, tracking = -0.025f)
    val Headline = inter(17, FontWeight.SemiBold)
    val Body = inter(17, FontWeight.Normal, line = 1.4f)
    val Callout = inter(15, FontWeight.Normal, line = 1.4f)
    val Footnote = inter(13, FontWeight.Medium)
    /** Eyebrow labels: 11 sp, uppercase (applied by the caller), wide tracking. */
    val Eyebrow = inter(11, FontWeight.SemiBold, tracking = 0.1f)
    val Caption = inter(11, FontWeight.Medium)
    val Code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)
}
