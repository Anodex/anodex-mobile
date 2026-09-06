package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import dev.anodex.mobile.ui.components.SecondaryButton
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

    Column(modifier.fillMaxSize().background(colors.bgApp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            SecondaryButton(label = "Back", onClick = onClose)

            // Split rather than ellipsised. A path is too long for a phone header,
            // and clipping the end removes the filename — the only part that
            // identifies which file this is.
            Column(Modifier.weight(1f)) {
                val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
                if (directory.isNotEmpty()) {
                    Text(
                        text = directory,
                        style = type.meta,
                        color = colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = path.substringAfterLast('/'),
                    style = type.mono,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            loading || content == null -> Centred("Reading from your computer…", colors.textFaint)

            content is FileContent.Failed -> Centred(content.reason, colors.danger)

            // Not an error: an image or a 40MB log is a perfectly good file, and the
            // reader should say what it is rather than look like it failed.
            content is FileContent.Unreadable -> Centred(content.reason, colors.textMuted)

            content is FileContent.Text -> Text(
                text = content.content,
                style = type.mono,
                color = colors.text,
                // Never wrapped. Indentation is how source is read, and a
                // soft-wrapped line invents structure that is not in the file.
                softWrap = false,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(Spacing.x4),
            )
        }
    }
}

@Composable
private fun Centred(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = AnodexTheme.type.body,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Spacing.x6),
        )
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
