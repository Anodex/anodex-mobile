package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.components.SecondaryButton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/** What the manual flow is waiting on. */
sealed interface ManualPairState {
    data object Entering : ManualPairState
    data object Probing : ManualPairState

    /**
     * The host answered and this is the certificate it presented.
     *
     * Nothing has been sent to it yet. The user's comparison is what decides
     * whether anything will be.
     */
    data class Confirming(val fingerprint: String) : ManualPairState

    data object Pairing : ManualPairState
}

/**
 * Pair by typing, when the camera cannot be used.
 *
 * A scanned QR carries the certificate fingerprint, so the phone pins before it
 * speaks. A typed code carries an address and nothing else — so this flow looks
 * at the certificate first, shows its fingerprint, and only proceeds if the user
 * confirms it matches their computer.
 *
 * The confirmation screen is deliberately not reassuring. It is the entire
 * security of this path, and a step that reads as a formality gets treated as one.
 */
@Composable
fun ManualPairScreen(
    state: ManualPairState,
    error: String?,
    onProbe: (address: String, port: Int, code: String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.x6),
        verticalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        when (state) {
            is ManualPairState.Confirming -> {
                Text("Does this match your computer?", style = type.title, color = colors.text)
                Text(
                    text = "Anodex on your computer is showing a fingerprint under the QR code. " +
                        "It must be identical to this one, character for character.",
                    style = type.body,
                    color = colors.textMuted,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Radii.lg)
                        .background(colors.bgSurface2)
                        .padding(Spacing.x5),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.fingerprint,
                        style = type.mono.copy(fontSize = type.heading.fontSize),
                        color = colors.text,
                        textAlign = TextAlign.Center,
                    )
                }

                Text(
                    text = "If it does not match, something other than your computer answered. " +
                        "Stop, and pair by scanning the code instead.",
                    style = type.body,
                    color = colors.warnInk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Radii.md)
                        .background(colors.warnSoft)
                        .padding(Spacing.x4),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                    SecondaryButton(label = "It doesn't match", onClick = onCancel)
                    PrimaryButton(label = "It matches", onClick = onConfirm)
                }
            }

            else -> {
                Text("Enter the details", style = type.title, color = colors.text)
                Text(
                    text = "On your computer: Anodex → Settings → Remote → Pair a phone, then " +
                        "open \"Can't scan it?\".",
                    style = type.body,
                    color = colors.textMuted,
                )

                Field(
                    label = "Address",
                    value = address,
                    placeholder = "192.168.1.42",
                    keyboard = KeyboardType.Uri,
                    onChange = { address = it },
                )
                Field(
                    label = "Port",
                    value = port,
                    placeholder = "8765",
                    keyboard = KeyboardType.Number,
                    onChange = { port = it.filter(Char::isDigit) },
                )
                Field(
                    label = "Code",
                    value = code,
                    placeholder = "K7QP-4M2X",
                    keyboard = KeyboardType.Text,
                    // Typed once, read off a screen. Autocorrect would silently
                    // "fix" a code that is not a word.
                    capitalize = true,
                    onChange = { code = it },
                )

                val ready = address.isNotBlank() && port.isNotBlank() && code.isNotBlank()
                val busy = state is ManualPairState.Probing || state is ManualPairState.Pairing

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                    SecondaryButton(label = "Cancel", onClick = onCancel)
                    if (ready && !busy) {
                        PrimaryButton(
                            label = "Continue",
                            onClick = { onProbe(address.trim(), port.toIntOrNull() ?: 0, code) },
                        )
                    }
                }

                if (busy) {
                    Text(
                        text = if (state is ManualPairState.Probing) {
                            "Looking for that computer…"
                        } else {
                            "Pairing…"
                        },
                        style = type.body,
                        color = colors.textMuted,
                    )
                }
            }
        }

        if (error != null) {
            Text(
                text = error,
                style = type.body,
                color = colors.dangerInk,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.md)
                    .background(colors.dangerSoft)
                    .padding(Spacing.x4),
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    keyboard: KeyboardType,
    onChange: (String) -> Unit,
    capitalize: Boolean = false,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        Text(label, style = type.label, color = colors.textMuted)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.md)
                .background(colors.bgInput)
                .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = type.body, color = colors.textFaint)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = type.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accentInk),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboard,
                    capitalization = if (capitalize) {
                        KeyboardCapitalization.Characters
                    } else {
                        KeyboardCapitalization.None
                    },
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "Manual entry - dark", showBackground = true, heightDp = 760)
@Composable
private fun PreviewManualEntry() {
    AnodexTheme(darkTheme = true) {
        ManualPairScreen(
            state = ManualPairState.Entering,
            error = null,
            onProbe = { _, _, _ -> },
            onConfirm = {},
            onCancel = {},
        )
    }
}

@Preview(name = "Confirm fingerprint - dark", showBackground = true, heightDp = 760)
@Composable
private fun PreviewConfirm() {
    AnodexTheme(darkTheme = true) {
        ManualPairScreen(
            state = ManualPairState.Confirming("A4 1C 9E 22 07  B8 3D 5F E1 60"),
            error = null,
            onProbe = { _, _, _ -> },
            onConfirm = {},
            onCancel = {},
        )
    }
}
