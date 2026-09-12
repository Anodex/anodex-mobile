package dev.anodex.mobile.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.profile.DayOfUse
import dev.anodex.mobile.profile.UsageProfile
import dev.anodex.mobile.profile.UserProfile
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Spacing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

/**
 * Who this is, and what they have been doing.
 *
 * The screen this replaces said "Set it at your computer" and showed nothing else.
 * That was true of *editing* a profile and remains true — the `settings:` prefix is
 * denied to a phone for good reasons — but it was being used to justify showing
 * nothing at all, including the numbers the computer already keeps and which read
 * perfectly well on a small screen.
 *
 * Everything here is a read. Nothing on this screen can be changed from the phone,
 * and the one line that says so is at the bottom rather than in place of the content.
 */
@Composable
fun ProfileScreen(
    user: UserProfile?,
    usage: UsageProfile?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    /** Why there is nothing here, when the reason is not "nothing has happened yet". */
    error: String? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.x5),
    ) {
        Identity(user)

        when {
            error != null -> Note(error)

            // Loading and empty are different sentences. "Nothing yet" under a
            // spinner is a claim the app has not earned.
            usage == null && loading -> Note("Reading your activity…")

            usage == null -> Note("Could not read your activity from the computer.")

            usage.isEmpty -> Note(
                "Nothing recorded yet. This fills in as you use Anodex on the computer."
            )

            else -> {
                Headline(usage)
                TokensOverTime(usage)
                MostUsedTools(usage)
                Activity(usage)
            }
        }

        Text(
            text = "Read from the computer. Your name, avatar and account are changed there.",
            style = type.meta,
            color = colors.textFaint,
            modifier = Modifier.padding(top = Spacing.x2),
        )
    }
}

@Composable
private fun Identity(user: UserProfile?) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(user?.avatarBase64, user?.displayName)
        Spacer(Modifier.width(Spacing.x4))
        Column(Modifier.weight(1f)) {
            Text(
                text = user?.displayName ?: "—",
                style = type.title,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val labels = listOfNotNull(
                user?.planTier?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
                user?.accountStatus?.takeIf { it.isNotBlank() },
            )
            if (labels.isNotEmpty()) {
                Text(
                    text = labels.joinToString(" · "),
                    style = type.meta,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/**
 * The avatar, or the initial standing in for one.
 *
 * Decoded once per image rather than per recomposition: this sits above a scrolling
 * column, and re-decoding a base64 bitmap on every frame of that scroll is the kind
 * of cost that never shows up on a development machine.
 */
@Composable
private fun Avatar(base64: String?, name: String?) {
    val colors = AnodexTheme.colors
    val bitmap = remember(base64) {
        base64?.substringAfter("base64,", base64)?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(listOf(colors.accent, colors.accentInk))
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp),
            )
        } else {
            Text(
                text = name?.trim()?.firstOrNull()?.uppercase() ?: "A",
                style = AnodexTheme.type.title,
                color = colors.textOnAccent,
            )
        }
    }
}

/** The two numbers worth leading with. */
@Composable
private fun Headline(usage: UsageProfile) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Stat("Lifetime tokens", compactCount(usage.lifetimeTokens), Modifier.weight(1f))
        Stat("Replies", compactCount(usage.lifetimeGenerations.toLong()), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bgSurface)
            .padding(Spacing.x4),
    ) {
        Text(value, style = type.title, color = colors.text, maxLines = 1)
        Text(label, style = type.meta, color = colors.textMuted, maxLines = 1)
    }
}

/**
 * Tokens over the last four weeks.
 *
 * Four weeks rather than everything: on a phone, a year of daily bars is a smear,
 * and the question this answers is "have I been busy lately" rather than "what did
 * I do last spring".
 */
@Composable
private fun TokensOverTime(usage: UsageProfile) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val days = remember(usage) { usage.recentDays(DAYS_CHARTED, LocalDate.now()) }
    val peak = remember(days) { days.maxOfOrNull { it.tokens } ?: 0L }

    Column {
        Text("Tokens over time", style = type.bodyEmphasis, color = colors.text)
        Text(
            text = "Last $DAYS_CHARTED days",
            style = type.meta,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(Spacing.x3))

        if (peak == 0L) {
            Note("No activity in the last $DAYS_CHARTED days.")
            return@Column
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            val gap = 2.dp.toPx()
            val slot = size.width / days.size
            val barWidth = max(1f, slot - gap)

            days.forEachIndexed { index, day ->
                // A day with activity always draws something. Rounding a real but
                // small day to nothing would read as a day off.
                val fraction = day.tokens.toFloat() / peak
                val height = if (day.tokens == 0L) 0f else max(2.dp.toPx(), size.height * fraction)
                if (height <= 0f) return@forEachIndexed

                drawRoundRect(
                    color = if (day.tokens == peak) colors.accent else colors.accentSoft,
                    topLeft = Offset(index * slot, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 3f),
                )
            }
        }
    }
}

@Composable
private fun MostUsedTools(usage: UsageProfile) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type
    val tools = usage.mostUsedTools.take(TOOLS_LISTED)
    if (tools.isEmpty()) return

    val busiest = tools.first().count.coerceAtLeast(1)

    Column {
        Text("Most used tools", style = type.bodyEmphasis, color = colors.text)
        Spacer(Modifier.height(Spacing.x3))

        tools.forEach { tool ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tool.name,
                    style = type.body,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.42f),
                )
                Box(
                    Modifier
                        .weight(0.43f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.bgSurface),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(tool.count.toFloat() / busiest)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.accent),
                    )
                }
                Text(
                    text = compactCount(tool.count.toLong()),
                    style = type.meta,
                    color = colors.textMuted,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(0.15f)
                        .padding(start = Spacing.x2),
                )
            }
        }
    }
}

/** The facts that do not fit a bar. */
@Composable
private fun Activity(usage: UsageProfile) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    val rows = buildList {
        if (usage.currentStreakDays > 0) add("Current streak" to days(usage.currentStreakDays))
        if (usage.longestStreakDays > 0) add("Longest streak" to days(usage.longestStreakDays))
        usage.peakDay?.takeIf { it.tokens > 0 }?.let {
            add("Busiest day" to "${it.date} · ${compactCount(it.tokens)}")
        }
        usage.peakHour?.let { add("Busiest hour" to hour(it)) }
        if (usage.sessionCount > 0) add("Conversations" to compactCount(usage.sessionCount.toLong()))
        usage.favouriteModelName?.let { add("Most used model" to it) }
        if (usage.longestGenerationMs > 0) {
            add("Longest single reply" to duration(usage.longestGenerationMs))
        }
    }
    if (rows.isEmpty()) return

    Column {
        Text("Activity", style = type.bodyEmphasis, color = colors.text)
        Spacer(Modifier.height(Spacing.x3))
        rows.forEach { (label, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.x2)
            ) {
                Text(label, style = type.body, color = colors.textMuted, modifier = Modifier.weight(1f))
                Text(
                    text = value,
                    style = type.body,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = AnodexTheme.type.body,
        color = AnodexTheme.colors.textMuted,
    )
}

private const val DAYS_CHARTED = 28
private const val TOOLS_LISTED = 5

/**
 * A count somebody can read at a glance.
 *
 * Lifetime token counts reach seven and eight figures, and the exact digit is never
 * the point on a phone — "2.4M" is the answer; "2,418,773" is a number to be counted.
 */
internal fun compactCount(value: Long): String = when {
    value >= 1_000_000_000 -> trimmed(value / 1_000_000_000.0) + "B"
    value >= 1_000_000 -> trimmed(value / 1_000_000.0) + "M"
    value >= 1_000 -> trimmed(value / 1_000.0) + "K"
    else -> value.toString()
}

private fun trimmed(value: Double): String {
    val rounded = String.format(Locale.US, "%.1f", value)
    return rounded.removeSuffix(".0")
}

private fun days(count: Int): String = if (count == 1) "1 day" else "$count days"

/** A local hour as somebody would say it, rather than as the data stores it. */
internal fun hour(value: Int): String = when {
    value == 0 -> "12 AM"
    value < 12 -> "$value AM"
    value == 12 -> "12 PM"
    else -> "${value - 12} PM"
}

/** A duration at the coarsest unit that still says something. */
internal fun duration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
