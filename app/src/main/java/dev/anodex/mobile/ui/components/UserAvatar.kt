package dev.anodex.mobile.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anodex.mobile.profile.avatarLook
import dev.anodex.mobile.profile.initialsOf

/**
 * The person, drawn the way the computer draws them: their photo, or their
 * initials on the gradient they picked in the computer's welcome, with the mark's
 * facet of light across it. With neither, the person glyph.
 *
 * The photo is decoded once per image rather than per recomposition — it often
 * sits above a scrolling column.
 */
@Composable
fun UserAvatar(
    base64: String?,
    displayName: String?,
    look: Int,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(base64) {
        base64?.substringAfter("base64,", base64)?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
    val (from, to) = avatarLook(look)
    val initials = initialsOf(displayName)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(from, to)))
            .drawWithContent {
                drawContent()
                if (bitmap == null) {
                    // The facet: light across the upper-left half, as on the mark.
                    val facet = Path().apply {
                        moveTo(0f, 0f); lineTo(this@drawWithContent.size.width, 0f)
                        lineTo(0f, this@drawWithContent.size.height); close()
                    }
                    drawPath(
                        facet,
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
            initials.isNotEmpty() -> Text(
                text = initials,
                style = TextStyle(
                    fontSize = (size.value * 0.38f).sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                ),
            )
            else -> AnodexIcon(
                AnodexIcon.USER,
                size = size * 0.5f,
                tint = Color.White,
                contentDescription = null,
            )
        }
    }
}
