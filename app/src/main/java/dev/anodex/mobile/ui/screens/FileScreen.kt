package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.EmptyState
import dev.anodex.mobile.ui.components.EmptyTone
import dev.anodex.mobile.ui.components.ScreenScaffold
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.components.fadingEdges
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.workspace.FileContent

/**
 * One file out of the project, as it is on the computer right now.
 *
 * Reached by tapping the tool row that touched it, which is the question a user
 * actually has: the transcript says `Edit src/sim/useDragBody.ts` and the next
 * thought is always *what does it say now*. Without this the phone can report that
 * work happened and not what the work was.
 *
 * A live read, never a cache. A file the phone showed ten minutes ago may have been
 * rewritten twice since, and stale source presented as current is worse than no
 * source at all.
 */
@Composable
fun FileScreen(
    path: String,
    content: FileContent?,
    loading: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Split rather than ellipsised. A path is too long for a phone header, and
    // clipping the end removes the filename — the only part that identifies which
    // file this is. So the directory becomes the subtitle and the name the title.
    val directory = path.substringBeforeLast('/', missingDelimiterValue = "")

    ScreenScaffold(
        title = path.substringAfterLast('/'),
        modifier = modifier,
        subtitle = directory.takeIf { it.isNotEmpty() },
        // A filename set in the body face stops looking like a filename.
        titleStyle = type.mono,
        leading = { SecondaryButton(label = "Back", onClick = onClose) },
    ) { topInset ->
        when {
            loading || content == null -> EmptyState(
                headline = "Reading from your computer…",
                tone = EmptyTone.WAITING,
                icon = AnodexIcon.FOLDER,
                modifier = Modifier.padding(top = topInset),
            )

            content is FileContent.Failed -> EmptyState(
                headline = "Could not read this file",
                detail = content.reason,
                tone = EmptyTone.PROBLEM,
                icon = AnodexIcon.FOLDER,
                modifier = Modifier.padding(top = topInset),
            )

            // Not an error: an image or a 40MB log is a perfectly good file, and the
            // reader should say what it is rather than look like it failed.
            content is FileContent.Unreadable -> EmptyState(
                headline = "Not shown here",
                detail = content.reason,
                icon = AnodexIcon.FOLDER,
                modifier = Modifier.padding(top = topInset),
            )

            content is FileContent.Text -> Text(
                text = content.content,
                style = type.mono,
                color = colors.text,
                // Never wrapped. Indentation is how source is read, and a
                // soft-wrapped line invents structure that is not in the file.
                softWrap = false,
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdges(topInset, 0.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = Spacing.x4,
                        end = Spacing.x4,
                        top = topInset + Spacing.x2,
                        bottom = Spacing.x6,
                    ),
            )
        }
    }
}


@Preview(name = "File", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewFile() {
    AnodexTheme(darkTheme = true) {
        FileScreen(
            path = "src/sim/useDragBody.ts",
            content = FileContent.Text(
                "export function useDragBody(body: Body) {\n" +
                    "  const basis = useRef(cameraBasis())\n" +
                    "  return useCallback(() => {\n" +
                    "    // pinned at drag start\n" +
                    "  }, [])\n" +
                    "}\n"
            ),
            loading = false,
            onClose = {},
        )
    }
}

@Preview(name = "File - too large", showBackground = true, backgroundColor = 0xFF0C0C0C)
@Composable
private fun PreviewFileTooLarge() {
    AnodexTheme(darkTheme = true) {
        FileScreen(
            path = "logs/run.log",
            content = FileContent.Unreadable("This file is too big to send to a phone (41.2 MB)."),
            loading = false,
            onClose = {},
        )
    }
}
