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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.chat.ChatMessage
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing

/**
 * The conversation.
 *
 * Proportions are ported from the desktop's `MessageBubble.module.css` rather than
 * re-derived: a user turn is a right-aligned bubble capped at 78% of the width with
 * a sharper trailing corner pointing back at the sender, and an assistant turn is
 * flat and full width. That asymmetry is what carries the role without an avatar or
 * a label on either side.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    sending: Boolean,
    error: String?,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize().background(colors.bgApp).imePadding()) {
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Ask your computer something.",
                    style = type.body,
                    color = colors.textFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.x6),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.x4),
                verticalArrangement = Arrangement.spacedBy(Spacing.x3),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    vertical = Spacing.x4
                ),
            ) {
                items(messages, key = { it.id }) { message -> MessageRow(message) }
            }
        }

        if (error != null) {
            Text(
                text = error,
                style = type.meta,
                color = colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.x4)
                    .clip(Radii.md)
                    .background(colors.dangerSoft)
                    .padding(Spacing.x3),
            )
        }

        Composer(
            draft = draft,
            enabled = !sending,
            onDraftChange = { draft = it },
            onSend = {
                onSend(draft)
                draft = ""
            },
        )
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val isUser = message.role == ChatMessage.Role.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .clip(Radii.lg)
                    .background(colors.bgSurface2)
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
            ) {
                Text(message.text, style = type.body, color = colors.text)
            }
        } else {
            // Assistant turns are unbubbled and full width, as on the desktop: the
            // reply is the page, not a card sitting on it.
            Text(
                text = message.text.ifEmpty { if (message.streaming) "…" else "" },
                style = type.body,
                color = if (message.streaming && message.text.isEmpty()) {
                    colors.textFaint
                } else {
                    colors.text
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgSurface)
            .padding(Spacing.x3),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(Radii.md)
                .background(colors.bgInput)
                .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
        ) {
            if (draft.isEmpty()) {
                Text("Message", style = type.body, color = colors.textFaint)
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = enabled,
                textStyle = type.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(Modifier.width(if (draft.isBlank()) 0.dp else 88.dp)) {
            if (draft.isNotBlank()) {
                PrimaryButton(label = if (enabled) "Send" else "…", onClick = onSend)
            }
        }
    }
}

@Preview(name = "Chat - dark", showBackground = true, heightDp = 700)
@Composable
private fun PreviewChat() {
    AnodexTheme(darkTheme = true) {
        ChatScreen(
            messages = listOf(
                ChatMessage("1", ChatMessage.Role.USER, "What's failing in the scheduler tests?"),
                ChatMessage(
                    "2",
                    ChatMessage.Role.ASSISTANT,
                    "Two of them. Both come from the monthly recurrence case rolling over a " +
                        "31st into a month that has no 31st.",
                ),
            ),
            sending = false,
            error = null,
            onSend = {},
        )
    }
}

@Preview(name = "Chat - light", showBackground = true, heightDp = 700)
@Composable
private fun PreviewChatLight() {
    AnodexTheme(darkTheme = false) {
        ChatScreen(
            messages = listOf(
                ChatMessage("1", ChatMessage.Role.USER, "Run the tests."),
                ChatMessage("2", ChatMessage.Role.ASSISTANT, "", streaming = true),
            ),
            sending = true,
            error = null,
            onSend = {},
        )
    }
}
