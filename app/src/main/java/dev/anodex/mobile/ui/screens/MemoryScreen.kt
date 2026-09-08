package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.memory.MemoryEntry
import dev.anodex.mobile.scheduler.relativeTime
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * What Anodex remembers about you, and a way to take one back.
 *
 * A memory you cannot see is one you cannot correct, and these are injected into
 * later prompts — so a wrong one keeps being wrong, quietly, in every conversation
 * after it. Being able to read them from wherever you are is most of the value;
 * being able to remove one is the rest.
 *
 * Read and forget only. Writing a memory from a phone would be a way to steer
 * every future conversation from a device that might be in somebody else's hand,
 * so `memory:create` and `memory:update` stay denied to remote callers and there
 * is deliberately no control here that would call them.
 *
 * Forgetting asks first. It is not undoable from this screen and the thing being
 * removed is a sentence somebody may not be able to reconstruct.
 */
@Composable
fun MemoryScreen(
    entries: List<MemoryEntry>,
    loading: Boolean,
    modifier: Modifier = Modifier,
    /** Why the list is empty, when the reason is not "nothing is remembered". */
    error: String? = null,
    onForget: ((MemoryEntry) -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        Text(
            text = "Memory",
            style = type.heading,
            color = colors.text,
            modifier = Modifier.padding(
                start = Spacing.x4,
                end = Spacing.x4,
                top = Spacing.x4,
            ),
        )

        // Where it lives is the sentence worth putting on this screen. It is the
        // one real difference between this and everything else on the phone, and
        // this is where somebody wonders about it.
        Text(
            text = "Kept on your computer. Never leaves it.",
            style = type.meta,
            color = colors.textFaint,
            modifier = Modifier.padding(
                start = Spacing.x4,
                end = Spacing.x4,
                top = Spacing.x1,
                bottom = Spacing.x3,
            ),
        )

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.x2),
                    modifier = Modifier.padding(Spacing.x6),
                ) {
                    Text(
                        text = when {
                            loading -> "Asking your computer…"
                            error != null -> "Could not read your memory"
                            else -> "Nothing remembered yet"
                        },
                        style = type.bodyEmphasis,
                        color = if (error != null) colors.danger else colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                    if (!loading) {
                        Text(
                            text = error
                                ?: "Anodex writes these as it learns them, at the computer.",
                            style = type.meta,
                            color = colors.textFaint,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.x3),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            items(entries, key = { it.id }) { entry ->
                EntryCard(entry, onForget)
            }

            item(key = "footnote") {
                Text(
                    text = "New memories are written at the computer. From here you can " +
                        "read them and take one back.",
                    style = type.meta,
                    color = colors.textFaint,
                    modifier = Modifier.padding(Spacing.x2),
                )
            }
        }
    }
}

@Composable
private fun EntryCard(entry: MemoryEntry, onForget: ((MemoryEntry) -> Unit)?) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Held per card rather than per screen: the question is about this line, and a
    // single shared flag would arm every row at once.
    var confirming by remember(entry.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.xl)
            .background(colors.bgSurface)
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text(entry.text, style = type.body, color = colors.text)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            // Where it applies, because a memory scoped to one project behaving as
            // if it were global is the kind of wrongness that is hard to spot from
            // the text alone.
            Text(
                text = provenance(entry),
                style = type.meta,
                color = colors.textFaint,
                modifier = Modifier.weight(1f),
            )

            if (onForget != null) {
                Text(
                    text = if (confirming) "Tap again to forget" else "Forget",
                    style = type.label,
                    color = if (confirming) colors.danger else colors.textMuted,
                    modifier = Modifier
                        .heightIn(min = Touch.minTarget)
                        .clip(Radii.md)
                        .clickable {
                            // Two taps, because this cannot be undone from here and
                            // the thing removed is a sentence nobody may be able to
                            // write again from memory.
                            if (confirming) onForget(entry) else confirming = true
                        }
                        .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
                )
            }
        }
    }
}

/** "Everywhere · learned 3 days ago", or the same scoped to one project. */
private fun provenance(entry: MemoryEntry): String {
    val where = if (entry.isGlobal) "Everywhere" else "This project"
    val pinned = if (entry.pinned) "pinned" else null
    val learned = relativeTime(entry.createdAtEpochMs)?.let { "learned $it" }
    return listOfNotNull(where, pinned, learned).joinToString(" · ")
}

@Preview(name = "Memory - dark", showBackground = true, heightDp = 700)
@Composable
private fun PreviewMemory() {
    AnodexTheme(darkTheme = true) {
        MemoryScreen(
            entries = listOf(
                MemoryEntry(
                    id = "1",
                    text = "Prefers pull requests over pushing straight to main.",
                    kind = "preference",
                    projectId = null,
                    createdAtEpochMs = System.currentTimeMillis() - 14L * 86_400_000,
                    pinned = true,
                ),
                MemoryEntry(
                    id = "2",
                    text = "Runs one local engine — llama.cpp only.",
                    kind = "fact",
                    projectId = null,
                    createdAtEpochMs = System.currentTimeMillis() - 3L * 86_400_000,
                    pinned = false,
                ),
                MemoryEntry(
                    id = "3",
                    text = "The test suite is run with `cargo test` from the crate root.",
                    kind = "fact",
                    projectId = "p_1",
                    createdAtEpochMs = System.currentTimeMillis() - 86_400_000,
                    pinned = false,
                ),
            ),
            loading = false,
            onForget = {},
        )
    }
}
