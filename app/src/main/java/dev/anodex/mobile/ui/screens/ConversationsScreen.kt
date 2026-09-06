package dev.anodex.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import dev.anodex.mobile.chat.ConversationSummary
import dev.anodex.mobile.ui.components.PrimaryButton
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * The conversations on the computer.
 *
 * A live read, never a cache: the desktop's store is authoritative, so this is
 * empty rather than stale when the machine is unreachable. That is the same
 * bargain the rest of the app makes, and it is why there is no "offline" version
 * of this list to fall back to.
 */
@Composable
fun ConversationsScreen(
    conversations: List<ConversationSummary>,
    loading: Boolean,
    activeId: String?,
    onOpen: (String) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = System.currentTimeMillis(),
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(modifier = modifier.fillMaxSize().background(colors.bgApp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Conversations", style = type.heading, color = colors.text)
            PrimaryButton(label = "New", onClick = onNewChat)
        }

        when {
            loading && conversations.isEmpty() ->
                Centred("Reading from your computer…", colors.textFaint)

            conversations.isEmpty() ->
                Centred("No conversations yet. Start one.", colors.textFaint)

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        active = conversation.id == activeId,
                        nowEpochMs = nowEpochMs,
                        onClick = { onOpen(conversation.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    active: Boolean,
    nowEpochMs: Long,
    onClick: () -> Unit,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.minTarget)
            .background(if (active) colors.bgSurface2 else colors.bgApp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Text(
            text = conversation.title,
            style = if (active) type.bodyEmphasis else type.body,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(relativeLastSeen(conversation.updatedAtEpochMs, nowEpochMs))
                if (conversation.messageCount > 0) {
                    append(" · ")
                    append(conversation.messageCount)
                    append(if (conversation.messageCount == 1) " message" else " messages")
                }
            },
            style = type.meta,
            color = colors.textFaint,
        )
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

private const val PREVIEW_NOW = 1_757_000_000_000L

@Preview(name = "Conversations - dark", showBackground = true, heightDp = 640)
@Composable
private fun PreviewConversations() {
    AnodexTheme(darkTheme = true) {
        ConversationsScreen(
            conversations = listOf(
                ConversationSummary("1", "Scheduler monthly recurrence bug", PREVIEW_NOW - 200_000_000, PREVIEW_NOW - 120_000, 14),
                ConversationSummary("2", "Port the message bubble to Compose", PREVIEW_NOW - 200_000_000, PREVIEW_NOW - 3_600_000, 6),
                ConversationSummary("3", "Untitled", PREVIEW_NOW - 200_000_000, PREVIEW_NOW - 86_400_000, 1),
            ),
            loading = false,
            activeId = "1",
            onOpen = {},
            onNewChat = {},
            nowEpochMs = PREVIEW_NOW,
        )
    }
}

@Preview(name = "Conversations empty - light", showBackground = true, heightDp = 640)
@Composable
private fun PreviewEmpty() {
    AnodexTheme(darkTheme = false) {
        ConversationsScreen(
            conversations = emptyList(),
            loading = false,
            activeId = null,
            onOpen = {},
            onNewChat = {},
            nowEpochMs = PREVIEW_NOW,
        )
    }
}
