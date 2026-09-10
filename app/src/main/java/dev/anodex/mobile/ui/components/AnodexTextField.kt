package dev.anodex.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Motion
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * Somewhere to type, built from tokens like everything else.
 *
 * This replaces the app's last Material control — a single `OutlinedTextField` on the
 * host screen, which arrived with Material's indicator line, Material's 56dp height,
 * Material's corner scale and Material's floating-label machinery, in the middle of a
 * screen otherwise made of Anodex cards. It sat on the pairing path, where the style
 * note in `AGENTS.md` says a borrowed control is worst: at the moment the app is
 * asking you to trust it.
 *
 * The edge carries the state, which is the same sentence the composer's pill already
 * follows. Resting it is a plain border; focused it lifts to `borderFocus`, the one
 * colour this palette holds fixed across every theme; wrong it goes to `danger`. A
 * border in this app means something, so there is no third decorative state.
 */
@Composable
fun AnodexTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    textStyle: TextStyle = AnodexTheme.type.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = AnodexTheme.colors
    var focused by remember { mutableStateOf(false) }

    val edge by animateColorAsState(
        targetValue = when {
            // The error outranks the focus. A field you are still typing in is a field
            // that is still wrong, and saying so is more use than saying "you are here".
            isError -> colors.danger
            focused -> colors.borderFocus
            else -> colors.border
        },
        animationSpec = Motion.fast(),
        label = "fieldEdge",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .clip(Radii.md)
            .background(colors.bgInput)
            .border(1.dp, edge, Radii.md)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder != null) {
            Text(placeholder, style = textStyle, color = colors.textFaint)
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = textStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accentInk),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
        )
    }
}
