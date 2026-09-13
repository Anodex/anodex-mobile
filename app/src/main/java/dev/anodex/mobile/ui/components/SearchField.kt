package dev.anodex.mobile.ui.components

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * Narrowing a long list to the one thing you were looking for.
 *
 * The app had no search of any kind, which is fine for ten of something and
 * useless for four hundred — and four hundred is what a computer that has been
 * working for months actually holds. Scrolling is not a way to find a file whose
 * name you already know.
 *
 * Filters what is already on screen rather than asking the computer. Everything
 * these lists show was fetched whole, so the answer is already here: sending the
 * question away would add a round trip, a failure mode and a spinner to something
 * that can happen between keystrokes.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** Given when a screen opens straight into searching, so the keyboard comes up with it. */
    focusRequester: FocusRequester? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.pill)
            .background(colors.bgInput)
            .padding(horizontal = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        AnodexIcon(AnodexIcon.SEARCH, size = 15.dp, tint = colors.textFaint)

        Box(Modifier.weight(1f).padding(vertical = Spacing.x3)) {
            if (value.isEmpty()) {
                Text(placeholder, style = type.body, color = colors.textFaint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = type.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accentInk),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            )
        }

        // Only once there is something to clear. A permanent × on an empty field is
        // a control that does nothing, sitting where a useful one could be.
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(Touch.minTarget)
                    .clip(Radii.pill)
                    .clickable(role = Role.Button) { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    style = type.body,
                    color = colors.textFaint,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "Clear the search"
                    },
                )
            }
        }
    }
}

/**
 * Whether a haystack contains every word of the needle, in any order.
 *
 * Word-wise rather than a substring match, because the useful query for a
 * conversation is two remembered words that were never adjacent — "parser tests"
 * should find "Fix the failing tests in the parser". A plain `contains` finds that
 * only if you recall the exact phrasing, which is the thing you have forgotten.
 */
fun matchesQuery(haystack: String, query: String): Boolean {
    val words = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val subject = haystack.lowercase()
    return words.all { subject.contains(it) }
}
