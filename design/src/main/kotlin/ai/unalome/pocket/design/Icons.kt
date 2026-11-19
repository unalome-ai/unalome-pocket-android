package ai.unalome.pocket.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's line icons, drawn for this project on a 24 × 24 grid with a 1.75 stroke, close to the
 * thin SF Symbols look of the iOS app. Kept here rather than pulling in an icon library.
 */
object UnalomeIcons {
    private fun stroked(name: String, vararg paths: String, filled: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = addPathNodes(d),
                        stroke = SolidColor(Color.White),
                        strokeLineWidth = 1.75f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
                filled.forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White)) }
            }
            .build()

    val Back = stroked("Back", "M15 5 L8 12 L15 19")
    val ChevronDown = stroked("ChevronDown", "M7 10 L12 15 L17 10")
    val ChevronRight = stroked("ChevronRight", "M10 7 L15 12 L10 17")
    val Models = stroked("Models", "M7 4 H17 A3 3 0 0 1 20 7 V17 A3 3 0 0 1 17 20 H7 A3 3 0 0 1 4 17 V7 A3 3 0 0 1 7 4 Z", "M9 9 H15 V15 H9 Z")
    val Plus = stroked("Plus", "M12 5 V19 M5 12 H19")
    val Close = stroked("Close", "M6 6 L18 18 M18 6 L6 18")
    val ArrowUp = stroked("ArrowUp", "M12 19 V5 M6 11 L12 5 L18 11")
    val Stop = stroked("Stop", filled = listOf("M8 7 H16 A1 1 0 0 1 17 8 V16 A1 1 0 0 1 16 17 H8 A1 1 0 0 1 7 16 V8 A1 1 0 0 1 8 7 Z"))
    val Mic = stroked(
        "Mic",
        "M9 6 A3 3 0 0 1 15 6 V11 A3 3 0 0 1 9 11 Z",
        "M5.5 11 A6.5 6.5 0 0 0 18.5 11",
        "M12 17.5 V21",
    )
    val Copy = stroked(
        "Copy",
        "M10 8.5 H18.5 A1.5 1.5 0 0 1 20 10 V18.5 A1.5 1.5 0 0 1 18.5 20 H10 A1.5 1.5 0 0 1 8.5 18.5 V10 A1.5 1.5 0 0 1 10 8.5 Z",
        "M15.5 8.5 V5.5 A1.5 1.5 0 0 0 14 4 H5.5 A1.5 1.5 0 0 0 4 5.5 V14 A1.5 1.5 0 0 0 5.5 15.5 H8.5",
    )
    val Regenerate = stroked("Regenerate", "M19 12 A7 7 0 1 1 16.95 7.05", "M17.5 3.5 V7.5 H13.5")
    val Flag = stroked("Flag", "M5.5 21 V4", "M5.5 4.5 H17.5 L15 9 L17.5 13.5 H5.5")
    val Search = stroked("Search", "M17 11 A6 6 0 1 1 5 11 A6 6 0 1 1 17 11 Z", "M15.5 15.5 L20 20")
    val Trash = stroked(
        "Trash",
        "M4 7 H20",
        "M9 7 V5 A1 1 0 0 1 10 4 H14 A1 1 0 0 1 15 5 V7",
        "M6 7 L7 19 A2 2 0 0 0 9 21 H15 A2 2 0 0 0 17 19 L18 7",
        "M10 11 V17 M14 11 V17",
    )
    val NewChat = stroked(
        "NewChat",
        "M12 4 H6 A2 2 0 0 0 4 6 V18 A2 2 0 0 0 6 20 H18 A2 2 0 0 0 20 18 V12",
        "M17.5 3.5 L20.5 6.5 L12 15 H9 V12 Z",
    )
    val Info = stroked("Info", "M21 12 A9 9 0 1 1 3 12 A9 9 0 1 1 21 12 Z", "M12 11 V16.5", "M12 7.75 V7.8")
    val ExternalLink = stroked(
        "ExternalLink",
        "M14 4 H20 V10", "M20 4 L11 13",
        "M18 14 V18 A2 2 0 0 1 16 20 H6 A2 2 0 0 1 4 18 V8 A2 2 0 0 1 6 6 H10",
    )
    val Chats = stroked("Chats", "M6 4 H18 A2 2 0 0 1 20 6 V15 A2 2 0 0 1 18 17 H10 L6 20.5 V17 A2 2 0 0 1 4 15 V6 A2 2 0 0 1 6 4 Z")
    val Menu = stroked("Menu", "M4 7 H20", "M4 12 H20", "M4 17 H14")
    val Check = stroked("Check", "M5 12.5 L10 17.5 L19 7")
    val Sparkles = stroked(
        "Sparkles",
        filled = listOf(
            "M11 3 Q11.8 9.2 18 10 Q11.8 10.8 11 17 Q10.2 10.8 4 10 Q10.2 9.2 11 3 Z",
            "M18.5 14.5 Q18.8 17.2 21 17.5 Q18.8 17.8 18.5 20.5 Q18.2 17.8 16 17.5 Q18.2 17.2 18.5 14.5 Z",
        ),
    )
    val Image = stroked(
        "Image",
        "M6 4 H18 A2 2 0 0 1 20 6 V18 A2 2 0 0 1 18 20 H6 A2 2 0 0 1 4 18 V6 A2 2 0 0 1 6 4 Z",
        "M4 16 L9 11 L14 16 L16.5 13.5 L20 17",
        "M16.5 8.5 A1.5 1.5 0 1 1 13.5 8.5 A1.5 1.5 0 1 1 16.5 8.5 Z",
    )
    val Camera = stroked(
        "Camera",
        "M4 8 A2 2 0 0 1 6 6 H8 L9.5 4 H14.5 L16 6 H18 A2 2 0 0 1 20 8 V17 A2 2 0 0 1 18 19 H6 A2 2 0 0 1 4 17 Z",
        "M15.5 12.5 A3.5 3.5 0 1 1 8.5 12.5 A3.5 3.5 0 1 1 15.5 12.5 Z",
    )
    val Edit = stroked("Edit", "M4 20 H8 L18.5 9.5 L14.5 5.5 L4 16 Z", "M13 7 L17 11")
    val Person = stroked(
        "Person",
        "M15.5 8 A3.5 3.5 0 1 1 8.5 8 A3.5 3.5 0 1 1 15.5 8 Z",
        "M5 20 C5 16 8 14 12 14 C16 14 19 16 19 20",
    )
}
