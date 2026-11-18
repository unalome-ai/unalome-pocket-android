package ai.unalome.pocket.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry of the unalome: a spiral (confusion) that settles through ever smaller waves into a
 * straight line (understanding), topped by the brand's triangle. Port of the iOS `UnalomeGeometry`.
 *
 * Defined in a unit space, x and y in 0..1 with y pointing down, and fitted into any rectangle
 * by [fit].
 */
object UnalomeGeometry {
    /** Width of the figure relative to its height. */
    const val ASPECT_RATIO = 0.46f

    private const val SPIRAL_CENTER_X = 0.5
    private const val SPIRAL_CENTER_Y = 0.80
    private const val SPIRAL_RADIUS = 0.19
    private const val WAVE_AMPLITUDE = 0.17
    private const val WAVES_TOP_Y = 0.27
    private const val LINE_TOP_Y = 0.115

    /** The line from the centre of the spiral to the top of the straight stroke. */
    val linePoints: List<Offset> by lazy {
        val points = mutableListOf<Offset>()
        // Spiral, winding outwards and ending at its top, heading right.
        val turns = 2.25
        val spiralSamples = 240
        for (index in 0..spiralSamples) {
            val u = index.toDouble() / spiralSamples
            val angle = -PI / 2 - turns * 2 * PI * (1 - u)
            val radius = SPIRAL_RADIUS * u
            points += Offset((SPIRAL_CENTER_X + radius * cos(angle)).toFloat(), (SPIRAL_CENTER_Y + radius * sin(angle)).toFloat())
        }
        // Waves stacked above the spiral, each narrower than the last, fading to zero slope so
        // the curve enters the straight line without a kink.
        val cycles = 2.5
        val waveSamples = 260
        val wavesBottomY = SPIRAL_CENTER_Y - SPIRAL_RADIUS
        for (index in 1..waveSamples) {
            val v = index.toDouble() / waveSamples
            val amplitude = WAVE_AMPLITUDE * (1 - v) * (1 - v)
            points += Offset(
                (0.5 + amplitude * sin(2 * PI * cycles * v)).toFloat(),
                (wavesBottomY - (wavesBottomY - WAVES_TOP_Y) * v).toFloat(),
            )
        }
        points += Offset(0.5f, LINE_TOP_Y.toFloat())
        points
    }

    /** The triangle above the line, pointing up. */
    val tipPoints = listOf(Offset(0.5f, 0f), Offset(0.546f, 0.078f), Offset(0.454f, 0.078f))

    /** Maps a unit-space point into [rect], centred, keeping the figure's proportions. */
    fun fit(rect: Rect): (Offset) -> Offset {
        val side = min(rect.height, rect.width / ASPECT_RATIO)
        val originX = rect.center.x - side / 2
        val originY = rect.center.y - side / 2
        return { point -> Offset(originX + point.x * side, originY + point.y * side) }
    }

    fun linePath(rect: Rect): Path = Path().apply {
        val map = fit(rect)
        linePoints.forEachIndexed { index, point ->
            val p = map(point)
            if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
    }

    fun tipPath(rect: Rect): Path = Path().apply {
        val map = fit(rect)
        tipPoints.forEachIndexed { index, point ->
            val p = map(point)
            if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
}

/** The full unalome figure: the white line and the gradient tip. */
@Composable
fun UnalomeFigure(modifier: Modifier = Modifier, height: Dp = 120.dp, lineColor: Color = Color.White) {
    Canvas(modifier.size(width = height * UnalomeGeometry.ASPECT_RATIO, height = height).clearAndSetSemantics {}) {
        val rect = Rect(Offset.Zero, size)
        val strokeWidth = size.height * 0.022f
        drawPath(
            UnalomeGeometry.linePath(rect),
            lineColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val tip = UnalomeGeometry.tipPath(rect)
        val bounds = tip.getBounds()
        drawPath(tip, Brush.linearGradient(UnalomeColor.GradientColors, bounds.topLeft, bounds.bottomRight))
    }
}

/**
 * The Unalome logo mark: a white ring with the gradient triangle above it.
 * Proportions in units of the ring's outer radius: triangle 0.51 tall and 0.57 wide, a 0.43 gap,
 * then the ring (stroke 0.46).
 */
@Composable
fun UnalomeMark(modifier: Modifier = Modifier, height: Dp = 28.dp) {
    val radius = height / 2.94f
    Canvas(modifier.size(width = radius * 2, height = height).clearAndSetSemantics {}) {
        drawUnalomeMark(this.size)
    }
}

/** Draws the mark into [size] (2 × 2.94 radius units), shared by the composable and icon previews. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnalomeMark(size: Size) {
    val radius = size.height / 2.94f
    val midX = size.width / 2
    val triangle = Path().apply {
        moveTo(midX, 0f)
        lineTo(midX + 0.285f * radius, 0.51f * radius)
        lineTo(midX - 0.285f * radius, 0.51f * radius)
        close()
    }
    drawPath(
        triangle,
        Brush.linearGradient(
            UnalomeColor.GradientColors,
            start = Offset(midX - 0.285f * radius, 0f),
            end = Offset(midX + 0.285f * radius, 0.51f * radius),
        ),
    )
    val stroke = 0.46f * radius
    drawCircle(
        Color.White,
        radius = radius - stroke / 2,
        center = Offset(midX, 0.94f * radius + radius),
        style = Stroke(width = stroke),
    )
}
