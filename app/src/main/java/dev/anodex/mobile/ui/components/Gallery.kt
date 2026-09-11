package dev.anodex.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * Every shared piece on one page, in both themes.
 *
 * The app had thirty-three previews and twenty-five of them were dark. That is the
 * mechanism by which a light theme becomes second-class: not a decision anybody
 * makes, just the absence of a place to look. The palette's status colours were
 * unreadable on cream for the entire life of the light theme partly because there
 * was no single screen where somebody would have seen all six at once.
 *
 * This is that screen. Anything added to `Surfaces`, `EmptyState`, `Skeleton`,
 * `Chrome` or `AnodexTextField` belongs here too — a component nobody can look at
 * in both themes is a component that has only been designed for one.
 *
 * Private and unreferenced, so R8 drops the whole file from a release build.
 */
@Composable
private fun Gallery() {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgApp)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.x4),
        verticalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Label("Buttons")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            PrimaryButton(label = "Send", onClick = {})
            SecondaryButton(label = "Cancel", onClick = {})
        }
        DangerButton(label = "Unpair…", onClick = {})

        Label("Cards")
        AnodexCard {
            Text("Filled", style = type.bodyEmphasis, color = colors.text)
            Text("Something that exists.", style = type.meta, color = colors.textFaint)
        }
        AnodexCard(edge = colors.accentInk) {
            Text("Filled, edged", style = type.bodyEmphasis, color = colors.text)
            Text("Waiting on you.", style = type.meta, color = colors.textFaint)
        }
        AnodexCard(fill = false, edge = colors.borderStrong) {
            Text("Outlined", style = type.bodyEmphasis, color = colors.text)
            Text("Offered, not yet real.", style = type.meta, color = colors.textFaint)
        }

        Label("Fields")
        AnodexTextField(value = "", onValueChange = {}, placeholder = "76.120.41.76")
        AnodexTextField(value = "192.168.1.14", onValueChange = {})
        AnodexTextField(value = "not an address", onValueChange = {}, isError = true)
        SearchField(value = "", onValueChange = {}, placeholder = "Search files")

        // The case that exposed the collapsed surface ladder: a field *inside* a
        // card, where `bgInput` and `bgSurface` used to be the same value.
        Label("A field inside a card")
        AnodexCard {
            Text("How this phone finds it", style = type.bodyEmphasis, color = colors.text)
            AnodexTextField(value = "", onValueChange = {}, placeholder = "76.120.41.76")
        }

        Label("Rows")
        Text(
            text = "A conversation, active",
            style = type.body,
            color = colors.text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.md)
                .background(colors.bgSurface2)
                .padding(Spacing.x3),
        )
        Text(
            text = "Another one",
            style = type.body,
            color = colors.text,
            modifier = Modifier.fillMaxWidth().padding(Spacing.x3),
        )

        Label("Waiting")
        ListSkeleton(rows = 2, caption = "Asking your computer…")

        Label("Trouble")
        InlineProblem("The computer answered, but not with a mailbox.")

        Label("A reply")
        // The one component whose colours are chosen against a *reply* rather than
        // against the app chrome. A link has to be visibly a link without turning a
        // paragraph into a ransom note, and the code block's ground has to sit on
        // the message surface rather than on the app's — both of which are only
        // decidable by looking at them in both themes, which is what this is for.
        MarkdownText(
            """
            Pushed to [the PR](https://github.com/Anodex/anodex-mobile/pull/94), and
            the run is at https://github.com/Anodex/anodex-mobile/actions. Pull it
            with `git fetch` first.

            ```bash
            git switch fix/the-work-log-fold-never-landed
            ```
            """.trimIndent()
        )

        Label("Ink against base")
        // The point of the whole `*Ink` set, side by side. In Midnight the two
        // columns are identical, which is exactly what should be seen.
        Swatches()

        Label("Rules")
        Hairline()
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(),
        style = AnodexTheme.type.badge,
        color = AnodexTheme.colors.textFaint,
        modifier = Modifier.padding(top = Spacing.x2),
    )
}

@Composable
private fun Swatches() {
    val colors = AnodexTheme.colors
    val rows = listOf(
        Triple("accent", colors.accent, colors.accentInk),
        Triple("danger", colors.danger, colors.dangerInk),
        Triple("warn", colors.warn, colors.warnInk),
        Triple("success", colors.success, colors.successInk),
        Triple("accentGreen", colors.accentGreen, colors.accentGreenInk),
        Triple("accentCyan", colors.accentCyan, colors.accentCyanInk),
    )

    AnodexCard(verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        for ((name, base, ink) in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
            ) {
                Chip(base)
                Chip(ink)
                // Both written out as text, which is the measurement that matters
                // and the one nobody was making.
                Text(name, style = AnodexTheme.type.meta, color = base)
                Text(name, style = AnodexTheme.type.meta, color = ink)
            }
        }
    }
}

@Composable
private fun Chip(colour: Color) {
    Box(Modifier.size(18.dp).clip(Radii.sm).background(colour))
}

@Composable
private fun EmptyStates() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AnodexTheme.colors.bgApp),
    ) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            EmptyState(
                headline = "Nothing in the inbox",
                detail = "Read at the computer, never stored on the phone.",
                icon = AnodexIcon.MAIL,
            )
        }
        Hairline()
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            EmptyState(
                headline = "Could not read your mail",
                detail = "The computer answered, but not with a mailbox.",
                tone = EmptyTone.PROBLEM,
                icon = AnodexIcon.MAIL,
            )
        }
        Hairline()
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            EmptyState(
                headline = "Waiting for your computer…",
                detail = "The mailbox has not been reached yet.",
                tone = EmptyTone.WAITING,
                icon = AnodexIcon.MAIL,
            )
        }
    }
}

@Preview(name = "Gallery — Midnight", heightDp = 1700)
@Composable
private fun PreviewGalleryDark() {
    AnodexTheme(darkTheme = true) { Gallery() }
}

@Preview(name = "Gallery — Light", heightDp = 1700)
@Composable
private fun PreviewGalleryLight() {
    AnodexTheme(darkTheme = false) { Gallery() }
}

@Preview(name = "Empty states — Midnight", heightDp = 620)
@Composable
private fun PreviewEmptyDark() {
    AnodexTheme(darkTheme = true) { EmptyStates() }
}

@Preview(name = "Empty states — Light", heightDp = 620)
@Composable
private fun PreviewEmptyLight() {
    AnodexTheme(darkTheme = false) { EmptyStates() }
}
