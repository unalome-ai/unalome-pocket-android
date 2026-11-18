package ai.unalome.pocket.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colour tokens from the unalome.ai stylesheet, as in the iOS `UnalomeColor`. */
object UnalomeColor {
    /** Base background, `#06050a`. */
    val Background = Color(0xFF06050A)
    /** Raised background, `#0d0b14`. */
    val BackgroundRaised = Color(0xFF0D0B14)
    /** Glass surface fill: white at 5%. */
    val GlassFill = Color.White.copy(alpha = 0.05f)
    /** Glass surface border: white at 8%, drawn 1 dp wide. */
    val GlassBorder = Color.White.copy(alpha = 0.08f)

    val TextPrimary = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.6f)
    /** White at 50%: the faintest text that still reads at 4.5:1 on the backgrounds and glass. */
    val TextTertiary = Color.White.copy(alpha = 0.5f)

    val Emerald = Color(0xFF10B981)
    val Teal = Color(0xFF14B8A6)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFF43F5E)

    /** The brand gradient stops: `#34d399` → `#2dd4bf` → `#22d3ee`. */
    val GradientStart = Color(0xFF34D399)
    val GradientMiddle = Color(0xFF2DD4BF)
    val GradientEnd = Color(0xFF22D3EE)
    val GradientColors = listOf(GradientStart, GradientMiddle, GradientEnd)
}

object UnalomeGradient {
    /** CSS 135°: from the top-left corner to the bottom-right corner of the drawn area. */
    val Brand: Brush = Brush.linearGradient(UnalomeColor.GradientColors, start = Offset.Zero, end = Offset.Infinite)

    /** Left to right, for thin outlines where a diagonal reads the same. */
    val Horizontal: Brush = Brush.horizontalGradient(UnalomeColor.GradientColors)
}

/** Corner radii: 16 for cards, 12 for controls; pills are fully rounded. */
object UnalomeRadius {
    val Card = 16.dp
    val Control = 12.dp
}
