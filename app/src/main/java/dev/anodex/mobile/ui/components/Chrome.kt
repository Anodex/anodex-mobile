package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * How far past a floating bar its scrim keeps fading.
 *
 * Short. The scrim exists to give the controls something to sit against, not to put
 * the bar back — past this the page is at full strength again.
 */
val SCRIM_FADE = 28.dp

/** How far into the scrim the page's colour has arrived, as a fraction of its height. */
const val SCRIM_HOLD = 0.55f

/**
 * How much of the page's colour the scrim carries behind the bar itself.
 *
 * Not all of it. Solid would be a bar again, and watching the page continue behind
 * the chrome is the thing worth keeping — it just cannot cost you the control.
 */
const val SCRIM_ALPHA = 0.82f

/**
 * Dissolves scrolling content into the bars floating over it.
 *
 * `DstIn` multiplies what is already drawn by the alpha of this rectangle, so black
 * keeps a pixel and transparent removes it. It needs its own layer to blend against,
 * which is what `CompositingStrategy.Offscreen` buys; without it the blend would
 * reach the whole canvas and take the page with it.
 *
 * Lived in `ChatScreen` as a private modifier, which is why it was the only screen in
 * the app that had it.
 */
fun Modifier.fadingEdges(top: Dp, bottom: Dp): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()

            val height = size.height
            if (height <= 0f) return@drawWithContent

            val topStop = (top.toPx() / height).coerceIn(0f, 1f)
            val bottomStop = (1f - bottom.toPx() / height).coerceIn(topStop, 1f)
            if (topStop == 0f && bottomStop == 1f) return@drawWithContent

            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    topStop to Color.Black,
                    bottomStop to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * The band of page colour a floating bar sits against.
 *
 * [height] is the bar's own measured height; the fade continues [SCRIM_FADE] past it.
 */
@Composable
fun TopScrim(height: Dp, modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(height + SCRIM_FADE)
            .background(
                Brush.verticalGradient(
                    0f to colors.bgApp,
                    SCRIM_HOLD to colors.bgApp.copy(alpha = SCRIM_ALPHA),
                    1f to Color.Transparent,
                )
            )
    )
}

/**
 * A screen whose title hangs over its own content.
 *
 * Releases 0.55 through 0.57 gave the conversation floating bars and a transcript
 * that dissolves under them. Every other screen kept a static heading sitting in a
 * column above a list with a hard top edge — so the app had one screen that looked
 * designed and eight that looked like the version before it. The difference is not
 * subtle in the hand: a list that stops dead under a heading looks like it has
 * nothing above it, and one that fades out looks like it continues.
 *
 * The title measures itself and hands [content] the height it came to, because it is
 * not a fixed number: a subtitle appears on some screens, a search field appears on
 * others once a list is long enough to need one. The content is responsible for two
 * things with that number — fading under it with [fadingEdges], and leaving itself
 * enough top padding to scroll clear of it. Both are one line each, and making them
 * the caller's job is what keeps this from having to know what a `LazyColumn` is.
 *
 * @param onTitleClick makes the title the way back to whatever contains it — the
 *   workspace's project name is the route to the list of projects. It takes the
 *   accent and a chevron when set, because a title that silently happens to be
 *   tappable is a control nobody finds.
 * @param leading a control before the title — a way back out of a detail screen.
 * @param titleStyle overridden only where the title is a literal rather than a name:
 *   the file reader's title is a filename, and a path set in the body face stops
 *   looking like a path.
 * @param trailing controls on the title's own line, right-aligned.
 * @param beneath anything that belongs to the chrome but not the title line — a
 *   search field, a filter row. It scrolls with the bar, not with the list.
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    titleStyle: TextStyle? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    beneath: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable (topInset: Dp) -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val density = LocalDensity.current

    var chromeHeight by remember { mutableStateOf(0.dp) }

    Box(modifier.fillMaxSize().background(colors.bgApp)) {
        // Under the chrome, and composed first so the bar draws over it.
        content(chromeHeight)

        TopScrim(chromeHeight, Modifier.align(Alignment.TopCenter))

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { chromeHeight = with(density) { it.height.toDp() } }
                .padding(
                    start = Spacing.x4,
                    end = Spacing.x4,
                    top = Spacing.x4,
                    bottom = Spacing.x3,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.x1),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                leading?.invoke(this)

                if (onTitleClick != null) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Touch.minTarget)
                            .clip(Radii.md)
                            // No horizontal padding of its own, so the title still
                            // starts on the same line as the subtitle beneath it. The
                            // 48dp height is what makes the target big enough.
                            .clickable(onClick = onTitleClick),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
                    ) {
                        Text(
                            text = title,
                            style = titleStyle ?: type.heading,
                            color = colors.accentInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        AnodexIcon(AnodexIcon.CHEVRON_RIGHT, size = 16.dp, tint = colors.accentInk)
                    }
                } else {
                    Text(
                        text = title,
                        style = titleStyle ?: type.heading,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                trailing?.invoke(this)
            }

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = type.meta,
                    color = colors.textFaint,
                )
            }

            beneath?.invoke(this)
        }
    }
}

/**
 * What a list under a [ScreenScaffold] should pad itself by.
 *
 * The top is the bar's height plus a little, so the first row arrives clear of it
 * rather than pinned to its underside. Gathered here so nine screens cannot each
 * invent their own answer.
 */
fun listPadding(
    topInset: Dp,
    horizontal: Dp = Spacing.x3,
    bottom: Dp = Spacing.x6,
): PaddingValues = PaddingValues(
    start = horizontal,
    end = horizontal,
    top = topInset + Spacing.x2,
    bottom = bottom,
)
