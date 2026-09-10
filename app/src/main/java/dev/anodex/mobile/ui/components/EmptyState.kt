package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * What a screen says when the list is empty.
 *
 * There were five of these — `Centred` three times over in three files, `Notice`,
 * and the memory screen's own hand-built column — and they disagreed about type,
 * colour, alignment and how wide a line was allowed to get. That matters more than
 * a duplicated composable usually would, because this is the state a screen shows
 * on the very first run, before anything has ever loaded: for a lot of people it is
 * the first thing the app says to them.
 *
 * **The tone is the whole point.** This codebase has one recurring defect — an error
 * turned into an empty list, so a screen says "nothing here" when the truth is "the
 * request failed" — and the empty state is where that lie is told. Making the
 * difference a required argument means a screen cannot report absence and failure in
 * the same voice by accident.
 */
enum class EmptyTone {
    /** Genuinely nothing here, and that is fine. */
    QUIET,

    /** Something went wrong and this screen does not know what it is missing. */
    PROBLEM,

    /** Still asking. Not an answer yet — see [ListSkeleton] for the loading shape. */
    WAITING,
}

@Composable
fun EmptyState(
    headline: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: EmptyTone = EmptyTone.QUIET,
    icon: AnodexIcon? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
            modifier = Modifier
                .padding(Spacing.x6)
                // A centred paragraph running the full width of a phone is hard to
                // read and looks like an error page. Held to roughly forty characters,
                // which is where a centred line stops needing to be tracked back.
                .widthIn(max = 320.dp),
        ) {
            if (icon != null) {
                AnodexIcon(
                    icon = icon,
                    size = 28.dp,
                    // Faint. It is a mark for the eye to land on, not an illustration,
                    // and an empty screen is not an occasion.
                    tint = if (tone == EmptyTone.PROBLEM) colors.dangerInk else colors.textFaint,
                )
            }

            Text(
                text = headline,
                style = type.bodyEmphasis,
                color = when (tone) {
                    EmptyTone.PROBLEM -> colors.dangerInk
                    else -> colors.textMuted
                },
                textAlign = TextAlign.Center,
            )

            if (detail != null) {
                Text(
                    text = detail,
                    style = type.meta,
                    color = colors.textFaint,
                    textAlign = TextAlign.Center,
                )
            }

            action?.invoke()
        }
    }
}

/**
 * A failure said next to the thing that still worked.
 *
 * [EmptyState] can only speak when a screen has nothing at all to show. Half the
 * screens here can fail *and* still have content — the scheduler reads its tasks and
 * offers its starters from two different places — and before this there was nowhere
 * for the failure to go, so it went nowhere: the screen rendered its offers and never
 * mentioned that the list above them had not loaded.
 *
 * That is the same defect this codebase keeps finding in its data layer, arriving
 * through the UI instead. `AGENTS.md` puts it plainly: when a call can fail, say it
 * failed.
 */
@Composable
fun InlineProblem(text: String, modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.lg)
            .background(colors.dangerSoft)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnodexIcon(AnodexIcon.INFO, size = 16.dp, tint = colors.dangerInk)
        Text(text, style = AnodexTheme.type.meta, color = colors.dangerInk)
    }
}
