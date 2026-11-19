package ai.unalome.pocket.features.chat

import ai.unalome.pocket.design.UnalomeColor
import ai.unalome.pocket.design.UnalomeIcons
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A stored image, decoded small off the main thread. */
@Composable
fun Thumbnail(path: String?, size: Dp, modifier: Modifier = Modifier, description: String = "Attached image") {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let { file ->
            withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
                BitmapFactory.decodeFile(file, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.size(size).clip(shape).background(Color(0xFF22202B), shape).border(1.dp, UnalomeColor.GlassBorder, shape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, null, Modifier.size(size), contentScale = ContentScale.Crop) }
            ?: Icon(UnalomeIcons.Image, null, Modifier.size(size / 3), tint = UnalomeColor.TextTertiary)
    }
}

/** The draft's images above the text field, each with a remove button. */
@Composable
fun DraftImages(names: List<String>, isAdding: Boolean, pathOf: (String) -> String?, onRemove: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEachIndexed { index, name ->
            Box {
                Thumbnail(pathOf(name), 64.dp, description = "Image ${index + 1} of ${names.size}")
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC000000))
                        .clickable(role = Role.Button) { onRemove(name) }
                        .semantics { contentDescription = "Remove image ${index + 1}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(UnalomeIcons.Close, null, Modifier.size(14.dp), tint = Color.White)
                }
            }
        }
        if (isAdding) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = UnalomeColor.GradientStart, strokeWidth = 2.dp)
            }
        }
    }
}

/** A sent message's images, right-aligned above its bubble. */
@Composable
fun MessageImages(names: List<String>, pathOf: (String) -> String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
        names.forEachIndexed { index, name ->
            Thumbnail(pathOf(name), if (names.size == 1) 160.dp else 88.dp, description = "Sent image ${index + 1} of ${names.size}")
        }
    }
}
