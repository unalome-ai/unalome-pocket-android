package ai.unalome.pocket.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ColorScheme = darkColorScheme(
    primary = UnalomeColor.GradientMiddle,
    onPrimary = UnalomeColor.Background,
    secondary = UnalomeColor.GradientStart,
    tertiary = UnalomeColor.GradientEnd,
    background = UnalomeColor.Background,
    onBackground = UnalomeColor.TextPrimary,
    surface = UnalomeColor.Background,
    onSurface = UnalomeColor.TextPrimary,
    surfaceVariant = UnalomeColor.BackgroundRaised,
    onSurfaceVariant = UnalomeColor.TextSecondary,
    surfaceContainer = UnalomeColor.BackgroundRaised,
    surfaceContainerHigh = Color(0xFF16131F),
    surfaceContainerHighest = Color(0xFF1C1927),
    outline = UnalomeColor.GlassBorder,
    outlineVariant = UnalomeColor.GlassBorder,
    error = UnalomeColor.Error,
)

private val Typography = Typography(
    displaySmall = UnalomeType.Display,
    titleLarge = UnalomeType.Title,
    titleMedium = UnalomeType.Headline,
    bodyLarge = UnalomeType.Body,
    bodyMedium = UnalomeType.Callout,
    bodySmall = UnalomeType.Footnote,
    labelLarge = UnalomeType.Headline.copy(fontSize = UnalomeType.Callout.fontSize),
    labelMedium = UnalomeType.Footnote,
    labelSmall = UnalomeType.Caption,
)

/** Dark only: the brand has no light theme. */
@Composable
fun UnalomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = Typography, content = content)
}
