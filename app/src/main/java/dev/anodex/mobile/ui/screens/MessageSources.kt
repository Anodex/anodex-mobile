package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import dev.anodex.mobile.chat.WebSource
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * What an answer stood on, under the answer.
 *
 * Two states, and the second is why this is not merely a list. When web tools
 * retrieved pages, they are shown so any claim can be checked. When web tools ran
 * and retrieved *nothing*, the answer necessarily came from the model's training
 * data -- and a confident, well-formatted reply gives the reader no way to tell.
 * That case is said out loud rather than left as an absence somebody has to
 * notice.
 *
 * The same two states the desktop draws, deliberately: this is one feature that
 * happens to have two renderers, and a phone that showed sources differently from
 * the computer would make the same conversation mean two things.
 */
@Composable
fun MessageSources(
    sources: List<WebSource>,
    attempted: Boolean,
    /** While tokens are still arriving nothing is final, so nothing is claimed. */
    streaming: Boolean,
    onOpen: (WebSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    if (streaming) return

    if (sources.isEmpty()) {
        if (!attempted) return
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = Spacing.x2)
                .clip(Radii.md)
                .background(colors.warnSoft)
                .padding(Spacing.x3),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            AnodexIcon(AnodexIcon.ALERT, size = 16.dp, tint = colors.warnInk)
            Text(
                text = "No sources were retrieved. The web tools ran and came back with " +
                    "nothing, so anything above is from the model's training data rather " +
                    "than a page fetched just now.",
                style = type.meta,
                color = colors.warnInk,
            )
        }
        return
    }

    // Collapsed to a count until asked. A long answer can stand on twenty pages,
    // and twenty rows under every reply would bury the conversation in its own
    // footnotes -- while the count alone still answers "did this come from
    // anywhere", which is the question the list exists for.
    var open by remember(sources) { mutableStateOf(sources.size <= INLINE_LIMIT) }
    val fetched = sources.count { it.verified }

    Column(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.x2),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Text(
            text = if (fetched == sources.size) {
                "${sources.size} source" + if (sources.size == 1) "" else "s"
            } else {
                // "3 of 12 fetched" rather than "12 sources": the rest were only
                // ever titles in a result list, and calling a lead a source is the
                // overstatement this whole surface exists to prevent.
                "$fetched of ${sources.size} fetched"
            },
            style = type.badge,
            color = colors.textFaint,
            modifier = Modifier
                // A tap target the size of a finger, with the text centred in it.
                // Left top-aligned the label sat at the top of a 48dp box and the
                // list began well below it, which reads as a gap in the layout
                // rather than as a control.
                .heightIn(min = Touch.minTarget)
                .wrapContentHeight(Alignment.CenterVertically)
                .clip(Radii.sm)
                .clickable { open = !open },
        )

        if (!open) return@Column

        sources.forEachIndexed { index, source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Touch.minTarget)
                    .clip(Radii.md)
                    .clickable { onOpen(source) }
                    .padding(vertical = Spacing.x1),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                // The same number the marker in the text shows, so `[S2]` above and
                // `2` here are visibly the same thing.
                Text(
                    text = "${index + 1}",
                    style = type.meta,
                    color = colors.accentInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.accentSoft)
                        .padding(horizontal = Spacing.x2, vertical = 2.dp),
                )
                Text(
                    text = hostOf(source.url),
                    style = type.meta,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!source.verified) {
                    // Said plainly. This page was a search result and was never
                    // opened, so the model saw a title and a snippet of it.
                    Text("lead", style = type.meta, color = colors.textFaint)
                }
            }
        }
    }
}

/** Beyond this many, the list starts closed. */
private const val INLINE_LIMIT = 4

/** `example.com` out of a URL, which is what a reader recognises at a glance. */
internal fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').removePrefix("www.").ifBlank { url }
