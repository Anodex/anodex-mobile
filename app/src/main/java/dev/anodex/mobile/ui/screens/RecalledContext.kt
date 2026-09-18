package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
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
import dev.anodex.mobile.chat.RecalledChat
import dev.anodex.mobile.chat.RecalledMemory
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * What the computer reached for before it answered.
 *
 * Two kinds of thing, shown the same way. A stored memory that was retrieved
 * into this turn, and an excerpt of an older conversation that was pulled in
 * because it looked relevant to the question.
 *
 * Both were already being sent to this phone with every turn and both were
 * thrown away here -- the same gap as the web sources, found by the same probe:
 * comparing every field of the computer's answer against what this app reads.
 *
 * Closed until asked. Retrieval happens on most turns and the rows would bury
 * the conversation in its own footnotes; the line alone still answers the
 * question people actually have, which is *whether* anything was recalled rather
 * than what. That matters because a reply shaped by a stored fact and one
 * written without it look identical, and only one of them is checkable.
 */
@Composable
fun RecalledContext(
    memories: List<RecalledMemory>,
    chats: List<RecalledChat>,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    onOpenChat: ((RecalledChat) -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Nothing is final while tokens are still arriving, so nothing is claimed.
    if (streaming || (memories.isEmpty() && chats.isEmpty())) return

    var open by remember(memories, chats) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.x1),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Touch.minTarget)
                .wrapContentHeight(Alignment.CenterVertically)
                .clip(Radii.sm)
                .clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            AnodexIcon(AnodexIcon.MEMORY, size = 14.dp, tint = colors.textFaint)
            Text(recalledSummary(memories.size, chats.size), style = type.badge, color = colors.textFaint)
        }

        if (!open) return@Column

        for (memory in memories) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.md)
                    .background(colors.bgSurface)
                    .padding(Spacing.x3),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            ) {
                // The kind, because it is why this one ranked: `identity` is
                // retrieved ahead of almost everything, and knowing that is the
                // difference between "it remembered" and "it guessed".
                if (memory.kind.isNotBlank()) {
                    Text(
                        text = memoryKindLabel(memory.kind),
                        style = type.meta,
                        color = colors.accentInk,
                    )
                }
                Text(memory.text, style = type.meta, color = colors.textMuted)
            }
        }

        for (chat in chats) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.md)
                    .background(colors.bgSurface)
                    .let { base ->
                        if (onOpenChat == null) base else base.clickable { onOpenChat(chat) }
                    }
                    .padding(Spacing.x3),
                verticalArrangement = Arrangement.spacedBy(Spacing.x1),
            ) {
                Text(
                    text = chat.title,
                    style = type.meta,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // One excerpt, not all of them. The rest are in the conversation
                // itself, which is a tap away and reads better than a pile of
                // fragments under an answer.
                chat.excerpts.firstOrNull()?.let { line ->
                    Text(
                        text = line.text,
                        style = type.meta,
                        color = colors.textFaint,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * "Recalled 2 memories", "Recalled a past chat", "Recalled 2 memories and 3 past chats".
 *
 * Counted rather than listed, because the count is the part that is read at a
 * glance and the list is the part that is opened on purpose.
 */
internal fun recalledSummary(memories: Int, chats: Int): String {
    val parts = buildList {
        if (memories == 1) add("a memory") else if (memories > 1) add("$memories memories")
        if (chats == 1) add("a past chat") else if (chats > 1) add("$chats past chats")
    }
    return "Recalled " + parts.joinToString(" and ")
}

/** The desktop's own labels for `MemoryKind`, so the two read the same. */
internal fun memoryKindLabel(kind: String): String = when (kind) {
    "identity" -> "Identity"
    "convention" -> "Convention"
    "gotcha" -> "Gotcha"
    "preference" -> "Preference"
    "open_task" -> "Open task"
    // A kind added on the computer and not known here. Shown as it arrived
    // rather than hidden: an unfamiliar label is information, a blank is not.
    else -> kind.replaceFirstChar { it.uppercase() }
}
