package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.tooling.preview.Preview
import dev.anodex.mobile.memory.MemoryEntry
import dev.anodex.mobile.scheduler.timeAgo
import dev.anodex.mobile.ui.components.AnodexCard
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.EmptyState
import dev.anodex.mobile.ui.components.EmptyTone
import dev.anodex.mobile.ui.components.ListSkeleton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.components.AnodexTextField
import dev.anodex.mobile.ui.components.SecondaryButton

/**
 * What Anodex remembers about you, and a way to take one back.
 *
 * A memory you cannot see is one you cannot correct, and these are injected into
 * later prompts — so a wrong one keeps being wrong, quietly, in every conversation
 * after it. Being able to read them from wherever you are is most of the value;
 * being able to remove one is the rest.
 *
 * Write, correct and forget. The first two are new: this screen was read-only on
 * the grounds that `memory:create` and `memory:update` "stay denied to remote
 * callers", which was never the case — see `channelPolicy.ts`, where the only
 * refusals are the connection settings, the terminal and critical thinking.
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
    /** Write a new one. Null hides the control, for a screen with no connection. */
    onRemember: ((String) -> Unit)? = null,
    /** Correct the wording of one that is wrong. */
    onReword: ((MemoryEntry, String) -> Unit)? = null,
) {
    var writing by rememberSaveable { mutableStateOf(false) }
    // No title of its own, and no floating chrome. This is a section of Settings
    // rather than a destination — Settings already names it in the header above,
    // and a screen that titles itself inside something that has just titled it says
    // "Memory" twice.
    Column(modifier.fillMaxSize()) {
        // Where it lives is the sentence worth putting here. It is the one real
        // difference between this and everything else on the phone, and this is
        // where somebody wonders about it.
        Text(
            text = "Kept on your computer. Never leaves it.",
            style = AnodexTheme.type.meta,
            color = AnodexTheme.colors.textFaint,
            modifier = Modifier.padding(
                start = Spacing.x4,
                end = Spacing.x4,
                top = Spacing.x2,
                bottom = Spacing.x3,
            ),
        )

        when {
            // The shape of the answer while the answer is on its way, rather than a
            // blank page and then a list arriving in one frame.
            loading && entries.isEmpty() -> ListSkeleton(
                rows = 3,
                lines = 2,
                caption = "Asking your computer…",
                modifier = Modifier.padding(horizontal = Spacing.x3),
            )

            error != null -> EmptyState(
                headline = "Could not read your memory",
                detail = error,
                tone = EmptyTone.PROBLEM,
                icon = AnodexIcon.MEMORY,
            )

            entries.isEmpty() && writing && onRemember != null -> Column(
                Modifier.padding(horizontal = Spacing.x3),
            ) {
                RememberCard(
                    open = true,
                    onOpen = {},
                    onCancel = { writing = false },
                    onSave = { text ->
                        onRemember(text)
                        writing = false
                    },
                )
            }

            entries.isEmpty() -> EmptyState(
                headline = "Nothing remembered yet",
                detail = "Anodex writes these as it learns them. You can add one yourself.",
                icon = AnodexIcon.MEMORY,
                action = onRemember?.let {
                    { SecondaryButton(label = "Remember something", onClick = { writing = true }) }
                },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.x3,
                    end = Spacing.x3,
                    bottom = Spacing.x8,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.x3),
            ) {
                if (onRemember != null) {
                    item(key = "compose") {
                        RememberCard(
                            open = writing,
                            onOpen = { writing = true },
                            onCancel = { writing = false },
                            onSave = { text ->
                                onRemember(text)
                                writing = false
                            },
                        )
                    }
                }

                items(entries, key = { it.id }) { entry ->
                    EntryCard(entry, onForget, onReword)
                }

                item(key = "footnote") {
                    Text(
                        text = "Anodex writes most of these itself as it learns them. " +
                            "Anything you add or correct here is remembered the same way.",
                        style = AnodexTheme.type.meta,
                        color = AnodexTheme.colors.textFaint,
                        modifier = Modifier.padding(Spacing.x2),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: MemoryEntry,
    onForget: ((MemoryEntry) -> Unit)?,
    onReword: ((MemoryEntry, String) -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Held per card rather than per screen: the question is about this line, and a
    // single shared flag would arm every row at once.
    var confirming by remember(entry.id) { mutableStateOf(false) }

    // Keyed on the id so a list that reorders under an open editor does not carry
    // one memory's half-typed correction onto another's card.
    var editing by remember(entry.id) { mutableStateOf<String?>(null) }

    val draft = editing
    if (draft != null && onReword != null) {
        AnodexCard {
            AnodexTextField(
                value = draft,
                onValueChange = { editing = it },
                placeholder = "What Anodex should remember instead",
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provenance(entry),
                    style = type.meta,
                    color = colors.textFaint,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = "Cancel",
                    style = type.label,
                    color = colors.textMuted,
                    modifier = Modifier
                        .heightIn(min = Touch.minTarget)
                        .clip(Radii.md)
                        .clickable { editing = null }
                        .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
                )

                // Unchanged text is not a save. Sending it anyway would bump the
                // entry's `updatedAt` and move it in a list ordered by recency, so
                // opening a memory to read it and closing it would reorder the page.
                val ready = draft.isNotBlank() && draft.trim() != entry.text
                Text(
                    text = "Save",
                    style = type.label,
                    color = if (ready) colors.accentInk else colors.textFaint,
                    modifier = Modifier
                        .heightIn(min = Touch.minTarget)
                        .clip(Radii.md)
                        .clickable(enabled = ready) {
                            onReword(entry, draft.trim())
                            editing = null
                        }
                        .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
                )
            }
        }
        return
    }

    AnodexCard {
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

            if (onReword != null && !confirming) {
                Text(
                    text = "Edit",
                    style = type.label,
                    color = colors.textMuted,
                    modifier = Modifier
                        .heightIn(min = Touch.minTarget)
                        .clip(Radii.md)
                        .clickable { editing = entry.text }
                        .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
                )
            }

            if (onForget != null) {
                Text(
                    text = if (confirming) "Tap again to forget" else "Forget",
                    style = type.label,
                    color = if (confirming) colors.dangerInk else colors.textMuted,
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


/**
 * Writing one down.
 *
 * Closed to a single row until it is wanted. A text field standing open above a
 * list is an invitation to type into the wrong thing, and this list is mostly
 * read -- the model writes these itself as it learns them, and a person adding
 * one is the exception rather than the shape of the screen.
 *
 * No kind picker. `MemoryKind` has five values and they are a retrieval ranking
 * hint, not a decision anybody is making about their own sentence; asking would
 * put a five-way choice in front of a one-line note. `Memory.KIND_DEFAULT`
 * explains which one it settles on and why.
 */
@Composable
private fun RememberCard(
    open: Boolean,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    if (!open) {
        AnodexCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Touch.minTarget)
                    .clip(Radii.md)
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
            ) {
                AnodexIcon(AnodexIcon.PENCIL, size = 18.dp, tint = colors.accentInk)
                Text("Remember something", style = type.body, color = colors.accentInk)
            }
        }
        return
    }

    var text by rememberSaveable { mutableStateOf("") }

    AnodexCard {
        AnodexTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "Something Anodex should know",
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The scope is stated rather than chosen. A memory written from here
            // applies everywhere, which is the honest reading of somebody typing a
            // sentence into a list they reached from Settings rather than from
            // inside a project.
            Text(
                text = "Remembered everywhere",
                style = type.meta,
                color = colors.textFaint,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = "Cancel",
                style = type.label,
                color = colors.textMuted,
                modifier = Modifier
                    .heightIn(min = Touch.minTarget)
                    .clip(Radii.md)
                    .clickable {
                        text = ""
                        onCancel()
                    }
                    .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
            )

            val ready = text.isNotBlank()
            Text(
                text = "Remember",
                style = type.label,
                // Greyed rather than hidden: a button that vanishes while you are
                // deciding whether to press it is worse than one that waits.
                color = if (ready) colors.accentInk else colors.textFaint,
                modifier = Modifier
                    .heightIn(min = Touch.minTarget)
                    .clip(Radii.md)
                    .clickable(enabled = ready) {
                        onSave(text.trim())
                        text = ""
                    }
                    .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
            )
        }
    }
}

/** "Everywhere · learned 3 days ago", or the same scoped to one project. */
private fun provenance(entry: MemoryEntry): String {
    val where = if (entry.isGlobal) "Everywhere" else "This project"
    val pinned = if (entry.pinned) "pinned" else null
    val learned = timeAgo(entry.createdAtEpochMs)?.let { "learned $it" }
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
