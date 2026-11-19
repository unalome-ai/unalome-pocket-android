package ai.unalome.pocket.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The site's plain `.glass` surface: white 5% fill and a 1 dp white 8% border. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(UnalomeRadius.Card)): Modifier =
    this.clip(shape).background(UnalomeColor.GlassFill, shape).border(1.dp, UnalomeColor.GlassBorder, shape)

/**
 * Floating chrome (composer, pills, toolbar buttons). Android has no Liquid Glass, so it is the
 * raised background under the glass fill: opaque enough to read over scrolled text.
 */
fun Modifier.floatingGlass(shape: Shape = CircleShape): Modifier =
    this.clip(shape)
        .background(Color(0xFF1A1822), shape)
        .border(1.dp, UnalomeColor.GlassBorder, shape)

/** A capsule of floating glass. */
@Composable
fun Pill(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier.floatingGlass(CircleShape).then(clickable).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Text filled with the brand gradient. */
@Composable
fun GradientText(text: String, style: TextStyle, modifier: Modifier = Modifier, brush: Brush = UnalomeGradient.Brand) {
    Text(text, modifier, style = style.copy(brush = brush))
}

/** A round glass button holding one icon, like the iOS toolbar buttons. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    tint: Color = UnalomeColor.TextPrimary,
) {
    Box(
        modifier
            .size(size)
            .floatingGlass(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp), tint = if (enabled) tint else tint.copy(alpha = 0.35f))
    }
}

/** A small filled dot, used for "on device" in the status pill. */
@Composable
fun StatusDot(modifier: Modifier = Modifier, color: Color = UnalomeColor.GradientStart) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

/** The main action of a screen or card: brand gradient, dark label, fully rounded. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (enabled) UnalomeGradient.Brand else SolidColor(Color(0xFF1B4D48)), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = UnalomeType.Headline, color = if (enabled) UnalomeColor.Background else Color(0xFF2E7C72), maxLines = 1)
    }
}

/** A quieter action next to a primary one: glass capsule, coloured label. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = UnalomeColor.GradientStart,
) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(UnalomeColor.GlassFill, CircleShape)
            .border(1.dp, UnalomeColor.GlassBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = UnalomeType.Callout.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
    }
}

/** A small outlined label, e.g. "Recommended". */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier
            .border(1.dp, color.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = UnalomeType.Eyebrow,
        color = color,
    )
}

/** A thin progress track filled with the brand gradient; [fraction] `null` shows an empty track. */
@Composable
fun GradientProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(6.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f), CircleShape),
    ) {
        if (fraction != null && fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(UnalomeGradient.Horizontal, CircleShape))
        }
    }
}
