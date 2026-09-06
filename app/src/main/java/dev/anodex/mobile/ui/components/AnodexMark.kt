package dev.anodex.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.R

/**
 * Anodex's brand mark — the faceted "A".
 *
 * The counterpart of the desktop's `AnodexLogo` in its 'mark' variant: the bare letterform, for
 * small inline spots on the app's own chrome. The art is the same file, exported per density by
 * `tools/generate_icons.py`; it is never redrawn here.
 *
 * The mark carries its own violet → blue gradient and is not tinted. It reads correctly on both
 * Anodex palettes without adjustment, which is why there is no theme-dependent variant.
 */
@Composable
fun AnodexMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    contentDescription: String? = "Anodex",
) {
    Image(
        painter = painterResource(R.drawable.anodex_mark),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}
