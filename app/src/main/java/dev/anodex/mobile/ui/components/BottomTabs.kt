package dev.anodex.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Motion
import dev.anodex.mobile.ui.theme.Spacing

/** The four surfaces, in the order the design sample fixes them. */
enum class AppTab(val label: String, val icon: AnodexIcon) {
    CHATS("Chats", AnodexIcon.CHAT),
    AGENTS("Agents", AnodexIcon.BOT),
    EMAIL("Email", AnodexIcon.MAIL),
    HOST("Host", AnodexIcon.MONITOR),
}

/**
 * The app's spine.
 *
 * Before this, every surface was a full-screen takeover reached from a button
 * somewhere else and left with the back gesture — so where you were was a fact
 * you had to remember, and Agents was two taps deep behind the conversation list.
 * A phone app is expected to tell you where you are without being asked.
 *
 * Four fixed destinations rather than a drawer or a dynamic list. They are the
 * four things the desktop actually does, they never change, and a bar that is
 * always the same shape becomes muscle memory in a way a menu never does.
 */
@Composable
fun BottomTabs(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    /** Agent runs waiting on a human. Zero hides the badge entirely. */
    agentBadge: Int = 0,
    /** Unread threads. Zero hides the badge entirely. */
    emailBadge: Int = 0,
) {
    val colors = AnodexTheme.colors

    Column(modifier.fillMaxWidth().background(colors.bgSurface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.x1, bottom = Spacing.x2),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (tab in AppTab.entries) {
                TabButton(
                    tab = tab,
                    selected = tab == selected,
                    badge = when (tab) {
                        AppTab.AGENTS -> agentBadge
                        AppTab.EMAIL -> emailBadge
                        else -> 0
                    },
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabButton(
    tab: AppTab,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    // Colour is the only thing that moves. An indicator that slides between tabs
    // is the fashionable choice and it animates on every single navigation, which
    // is exactly the kind of continuous decoration the house style rules out.
    val tint by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.textFaint,
        animationSpec = Motion.fast(),
        label = "tabTint",
    )

    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            // A comfortable target regardless of how short the label is.
            .defaultMinSize(minHeight = 48.dp)
            .padding(top = Spacing.x2)
            .semantics {
                this.selected = selected
                contentDescription =
                    if (badge > 0) "${tab.label}, $badge waiting" else tab.label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            AnodexIcon(tab.icon, size = 20.dp, tint = tint, contentDescription = null)

            if (badge > 0) {
                Badge(badge, Modifier.offset(x = 9.dp, y = (-5).dp))
            }
        }

        Text(text = tab.label, style = type.badge, color = tint, maxLines = 1)
    }
}

/**
 * The count of things waiting on the user.
 *
 * Amber rather than red, and it means "waiting on you" rather than "unread" — a
 * red dot on a phone is a demand, and the only thing here entitled to make one
 * is an agent that has actually stopped and cannot continue without an answer.
 */
@Composable
private fun Badge(count: Int, modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Box(
        modifier
            .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
            .clip(CircleShape)
            .background(colors.warn)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Past nine the exact number stops being information and starts being a
        // wide pill that pushes the icon off centre.
        Text(
            text = if (count > 9) "9+" else count.toString(),
            style = type.badge,
            color = colors.bgBase,
            maxLines = 1,
        )
    }
}
