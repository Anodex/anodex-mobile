package dev.anodex.mobile.ui.components

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * The step between meaning to do something irreversible and having done it.
 *
 * Anodex had no such step. `DangerButton` carries a comment saying unpairing "is a
 * button you should have to mean" — but a red outline is a description of a button,
 * not a question asked of the person pressing it, and a single tap on a phone in a
 * pocket does not distinguish between the two. The pairing it destroys cannot be
 * recreated from the phone: it needs the desktop, in person, to issue a new QR code.
 * So the cost of a mis-tap is not an undo, it is a walk home.
 *
 * Deliberately not Material's `AlertDialog`: that brings its own corner scale, type
 * ramp and button treatment, and every other surface in this app is drawn from the
 * theme tokens. It would read as a control borrowed from a different application at
 * exactly the moment the user is being asked to trust what it says.
 *
 * Ordering matches the tool-approval card: the safe choice sits first and is the one
 * a thumb reaches without aiming. The destructive action is never the primary weight.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Cancel",
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.border, Radii.lg)
                .background(colors.bgSurface, Radii.lg)
                .padding(Spacing.x5),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            Text(title, style = type.bodyEmphasis, color = colors.text)

            // The consequence, stated concretely. "Are you sure?" tells somebody
            // nothing they did not already know; what they need is what breaks.
            Text(body, style = type.meta, color = colors.textFaint)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.x2),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3, Alignment.End),
            ) {
                SecondaryButton(label = cancelLabel, onClick = onDismiss)
                DangerButton(label = confirmLabel, onClick = onConfirm)
            }
        }
    }
}

/**
 * One line of text, asked for in the same frame as [ConfirmDialog].
 *
 * For renaming, where the answer is a word rather than a yes. Opens with the current
 * value selected-in-place, so a small correction is a small edit, and refuses to
 * confirm a blank — a conversation called nothing is not one anybody can find again.
 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.border, Radii.lg)
                .background(colors.bgSurface, Radii.lg)
                .padding(Spacing.x5),
            verticalArrangement = Arrangement.spacedBy(Spacing.x3),
        ) {
            Text(title, style = type.bodyEmphasis, color = colors.text)

            AnodexTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = placeholder,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (value.isNotBlank()) onConfirm(value.trim()) },
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.x2),
                horizontalArrangement = Arrangement.spacedBy(Spacing.x3, Alignment.End),
            ) {
                SecondaryButton(label = "Cancel", onClick = onDismiss)
                PrimaryButton(
                    label = confirmLabel,
                    onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                )
            }
        }
    }
}
