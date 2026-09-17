package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.EmptyState
import dev.anodex.mobile.ui.components.EmptyTone
import dev.anodex.mobile.ui.components.ScreenScaffold
import dev.anodex.mobile.ui.theme.AnodexColors
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.DiffTone
import dev.anodex.mobile.ui.theme.diffInk
import dev.anodex.mobile.ui.theme.diffWash
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch
import dev.anodex.mobile.workspace.DiffRow
import dev.anodex.mobile.workspace.TurnDiff

/**
 * What one turn changed inside one file.
 *
 * The phone could already list which files a turn touched and by how many bytes.
 * That is enough to notice a build went somewhere unexpected and not enough to do
 * anything about it — `Checkpoints` says so in its own words, and gives it as the
 * reason restoring is not offered from a phone: undoing an afternoon of work needs
 * a diff in front of you, and a filename and a byte count is not that.
 *
 * This is that diff. The computer draws it, because the computer has the file, and
 * what arrives here is already collapsed to the lines around each change.
 *
 * Lines wrap, which is the opposite of [FileScreen]'s rule and is deliberate.
 * Source is read by its indentation, so the file reader never wraps — but a diff is
 * read by its markers, and one that has to be scrolled sideways on every line to
 * find out which side of the change you are looking at is not readable at all.
 */
@Composable
fun DiffScreen(
    path: String,
    diff: TurnDiff?,
    loading: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Open the file as it stands now. Null where there is no socket to ask. */
    onOpenFile: (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    val directory = path.substringBeforeLast('/', missingDelimiterValue = "")

    ScreenScaffold(
        title = path.substringAfterLast('/'),
        modifier = modifier,
        subtitle = directory.takeIf { it.isNotEmpty() },
        titleStyle = type.mono,
        leading = {
            Box(
                modifier = Modifier
                    .size(Touch.minTarget)
                    .clip(Radii.md)
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                AnodexIcon(
                    AnodexIcon.CHEVRON_LEFT,
                    size = 20.dp,
                    tint = colors.textMuted,
                    contentDescription = "Back",
                )
            }
        },
        trailing = if (onOpenFile == null) {
            null
        } else {
            {
                Box(
                    modifier = Modifier
                        .size(Touch.minTarget)
                        .clip(Radii.md)
                        .clickable(role = Role.Button, onClick = onOpenFile),
                    contentAlignment = Alignment.Center,
                ) {
                    AnodexIcon(
                        AnodexIcon.FOLDER,
                        size = 20.dp,
                        tint = colors.textMuted,
                        // Named for the difference that matters here: this screen is
                        // the turn's record, and the file may have moved on since.
                        contentDescription = "Open the file as it is now",
                    )
                }
            }
        },
        beneath = if (diff == null) null else ({ DiffSummary(diff) }),
    ) { topInset ->
        when {
            loading || diff == null -> EmptyState(
                headline = "Reading from your computer…",
                tone = EmptyTone.WAITING,
                icon = AnodexIcon.FOLDER,
                modifier = Modifier.padding(top = topInset),
            )

            // Not a failure. An image or a compiled file changed, and the honest
            // report is which one it was, not an empty screen.
            diff.binary -> EmptyState(
                headline = "Nothing to show here",
                detail = "This is not a text file, so there is no diff to draw.",
                icon = AnodexIcon.FOLDER,
                modifier = Modifier.padding(top = topInset),
            )

            diff.rows.isEmpty() -> EmptyState(
                headline = "Nothing changed inside it",
                detail = "The file was touched, but its contents came out the same.",
                icon = AnodexIcon.FOLDER,
                modifier = Modifier.padding(top = topInset),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = topInset + Spacing.x2, bottom = Spacing.x6),
            ) {
                items(diff.rows) { row -> DiffLine(row, colors) }

                if (diff.truncated) {
                    item {
                        Text(
                            text = "This change is too big to show in full. " +
                                "The counts above are the whole of it.",
                            style = type.meta,
                            color = colors.textFaint,
                            modifier = Modifier.fillMaxWidth().padding(Spacing.x4),
                        )
                    }
                }
            }
        }
    }
}

/** "+12 −3", in the chrome, so the size of a change is known before scrolling it. */
@Composable
private fun DiffSummary(diff: TurnDiff) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.x4, end = Spacing.x4, bottom = Spacing.x2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Text(kindWord(diff.kind), style = type.meta, color = colors.textMuted)
        if (diff.added > 0) {
            Text("+${diff.added}", style = type.mono, color = colors.diffInk(DiffTone.ADDED))
        }
        if (diff.removed > 0) {
            Text("−${diff.removed}", style = type.mono, color = colors.diffInk(DiffTone.REMOVED))
        }
    }
}

private fun kindWord(kind: String): String = when (kind) {
    "created" -> "Added this file"
    "deleted" -> "Deleted this file"
    else -> "Changed"
}

@Composable
private fun DiffLine(row: DiffRow, colors: AnodexColors) {
    val type = AnodexTheme.type

    if (row.kind == DiffRow.Kind.GAP) {
        Text(
            text = if (row.collapsed == 1) "1 unchanged line" else "${row.collapsed} unchanged lines",
            style = type.meta,
            color = colors.textFaint,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bgSurface)
                .padding(horizontal = Spacing.x4, vertical = Spacing.x1),
        )
        return
    }

    // A blank is the empty half of a replacement, not a line: it takes the surface
    // rather than a tone, so it reads as absence.
    val tint = if (row.kind == DiffRow.Kind.BLANK) {
        colors.bgSurface
    } else {
        colors.diffWash(row.kind.tone())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .padding(horizontal = Spacing.x3, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        // Fixed, and never scrolled away, because it is the one thing every line in
        // a diff is read for: which side of the change this is.
        Text(
            text = marker(row.kind),
            style = type.mono,
            color = colors.diffInk(row.kind.tone()),
        )
        Text(
            text = row.text,
            style = type.mono,
            color = if (row.kind == DiffRow.Kind.UNCHANGED) colors.textMuted else colors.text,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun DiffRow.Kind.tone(): DiffTone = when (this) {
    DiffRow.Kind.ADDED -> DiffTone.ADDED
    DiffRow.Kind.REMOVED -> DiffTone.REMOVED
    else -> DiffTone.CONTEXT
}

private fun marker(kind: DiffRow.Kind): String = when (kind) {
    DiffRow.Kind.ADDED -> "+"
    DiffRow.Kind.REMOVED -> "−"
    else -> " "
}

@Preview(name = "Diff", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewDiff() {
    AnodexTheme(darkTheme = true) {
        DiffScreen(
            path = "src/sim/useDragBody.ts",
            diff = TurnDiff(
                path = "src/sim/useDragBody.ts",
                kind = "modified",
                binary = false,
                added = 2,
                removed = 1,
                rows = listOf(
                    DiffRow(DiffRow.Kind.GAP, "", collapsed = 12),
                    DiffRow(DiffRow.Kind.UNCHANGED, "export function useDragBody(body: Body) {"),
                    DiffRow(DiffRow.Kind.REMOVED, "  const basis = cameraBasis()"),
                    DiffRow(DiffRow.Kind.ADDED, "  const basis = useRef(cameraBasis())"),
                    DiffRow(DiffRow.Kind.ADDED, "  // pinned at drag start"),
                    DiffRow(DiffRow.Kind.UNCHANGED, "  return useCallback(() => {"),
                ),
                truncated = false,
            ),
            loading = false,
            onClose = {},
            onOpenFile = {},
        )
    }
}
