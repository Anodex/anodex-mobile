package dev.anodex.mobile.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.ui.components.AnodexIcon
import dev.anodex.mobile.ui.components.AnodexMark
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.Brand
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * Speak: the spoken back-and-forth, as its own screen.
 *
 * Two different things are often called voice, and this is the second of them.
 * Dictation — the microphone beside the composer — listens once, turns what it
 * heard into text, and leaves it in the field to be read before it is sent. This
 * is a conversation: it stays open, it listens while it talks, and interrupting
 * it is expected rather than an error.
 *
 * ## Why a mark that breathes rather than a waveform
 *
 * The obvious drawing is a bar of dancing bars, which is what every voice app
 * shows. It is also the one part of a voice screen that says nothing about *whose*
 * voice it is. Anodex already has a shape that means Anodex, so the level drives a
 * halo around the mark instead: listening pulses it in the accent, the far end
 * talking lights it on the brand gradient, and idle leaves it a faint ring. The
 * state is readable at a glance from across a room, which is the actual job —
 * nobody reads a waveform, they check whether the thing is awake.
 *
 * ## What is on screen at this stage
 *
 * The loop currently echoes: the computer sends back what it hears, and the round
 * trip is measured from this phone's own clock. So the numbers below the mark are
 * real and temporary — they are the measurement stage 1 exists to produce, and
 * they come off the screen once there is a voice on the other end.
 *
 * Stateless on purpose, so it can be rendered with invented numbers for review
 * without a desktop, a pairing or a microphone.
 */
@Composable
fun VoiceScreen(
    state: VoiceScreenState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .padding(horizontal = Spacing.x4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Touch.minTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = "Close voice" },
                contentAlignment = Alignment.Center,
            ) {
                AnodexIcon(AnodexIcon.CLOSE, size = 20.dp, tint = colors.textMuted)
            }
            Spacer(Modifier.fillMaxWidth(0.5f))
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            VoiceHalo(state = state)

            Spacer(Modifier.height(Spacing.x8))

            Text(
                text = state.headline(),
                style = type.title,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.x2))
            Text(
                text = state.detail(),
                style = type.body,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )

            if (state.running && state.framesHeard > 0) {
                Spacer(Modifier.height(Spacing.x6))
                Measurement(state)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.x8),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // One control, because there is one decision: talking, or not. The row of
            // camera, speaker, mute and settings other apps put here is four choices
            // nobody makes mid-sentence, and each of them is a thing to mis-tap while
            // holding a phone to your face.
            TalkButton(running = state.running, onClick = if (state.running) onStop else onStart)
        }
    }
}

/**
 * Everything the screen draws, and nothing about how it got there.
 *
 * A screen that took `VoiceLoop` directly could not be rendered without a socket,
 * a microphone and a paired computer — which is exactly the screen nobody checks
 * the look of until it ships.
 */
data class VoiceScreenState(
    val running: Boolean = false,
    /** The computer answered, so the far end is really listening. */
    val connected: Boolean = false,
    /** Somebody is talking into this phone right now. */
    val listening: Boolean = false,
    /** The far end is talking. */
    val answering: Boolean = false,
    /** 0..1, what the microphone is hearing. Drives the halo. */
    val level: Float = 0f,
    val framesHeard: Int = 0,
    val framesLost: Int = 0,
    val medianRoundTripMs: Int = 0,
    val bestRoundTripMs: Int = 0,
    val worstRoundTripMs: Int = 0,
    /** What the computer is called, for the line that says where this is going. */
    val hostName: String = "",
) {
    fun headline(): String = when {
        !running -> "Speak"
        !connected -> "Asking your computer…"
        answering -> "Answering"
        listening -> "Listening"
        else -> "Go ahead"
    }

    // Both branches decide in the same order as [headline], and that is not a
    // coincidence to be tidied away: when they disagreed, the screen said
    // "Answering" over "Stop talking and it will answer" — each line true, the pair
    // of them nonsense. Somebody talking over a reply is told that is allowed.
    fun detail(): String = when {
        !running -> if (hostName.isBlank()) "Talk to Anodex" else "Talk to Anodex on $hostName"
        !connected -> "Waiting for it to answer"
        answering -> "Talk over it whenever you like"
        listening -> "Stop talking and it will answer"
        else -> "It is listening"
    }
}

/**
 * The mark, with a ring that answers to sound.
 *
 * Three rings rather than one: a faint constant, one that follows the level, and a
 * wider soft one that lags behind it. A single ring tracking the level exactly
 * looks mechanical — it is the lag that reads as something breathing rather than a
 * meter moving.
 */
@Composable
private fun VoiceHalo(state: VoiceScreenState) {
    val colors = AnodexTheme.colors

    val tint = when {
        state.answering -> colors.accentViolet
        state.listening -> colors.accent
        state.running -> colors.accentCyan
        else -> colors.border
    }

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(HALO_SIZE)) {
            val centre = Offset(size.width / 2, size.height / 2)
            val base = size.minDimension / 2 * 0.52f
            val reach = size.minDimension / 2 * 0.46f
            val level = state.level.coerceIn(0f, 1f)

            // The constant one. Present even when nothing is happening, so the screen
            // is not empty before the first word.
            drawCircle(
                color = colors.border.copy(alpha = 0.55f),
                radius = base,
                center = centre,
                style = Stroke(width = 1.5.dp.toPx()),
            )

            if (!state.running) return@Canvas

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = 0.18f), Color.Transparent),
                    center = centre,
                    radius = base + reach * level,
                ),
                radius = base + reach * level,
                center = centre,
            )

            drawCircle(
                color = tint.copy(alpha = 0.85f),
                radius = base + reach * level * 0.55f,
                center = centre,
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        AnodexMark(size = MARK_SIZE)
    }
}

/**
 * The round trip, while it is the point.
 *
 * Deliberately plain and deliberately temporary. Stage 1 exists to find out what a
 * real phone on a real network costs before any model is added to blame, and a
 * number nobody can see is a number nobody checks. It comes off the screen when
 * there is a voice on the other end instead of an echo.
 */
@Composable
private fun Measurement(state: VoiceScreenState) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Column(
        modifier = Modifier
            .clip(Radii.lg)
            .background(colors.bgSurface)
            .border(1.dp, colors.border, Radii.lg)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.x1),
    ) {
        Text(
            text = "${state.medianRoundTripMs} ms",
            style = type.display,
            color = colors.text,
        )
        Text(
            text = "round trip, middle of the last hundred",
            style = type.meta,
            color = colors.textFaint,
        )
        Text(
            text = "${state.bestRoundTripMs}–${state.worstRoundTripMs} ms  ·  " +
                "${state.framesHeard} back  ·  ${state.framesLost} lost",
            style = type.meta,
            color = colors.textMuted,
        )
    }
}

/**
 * Start, and then Stop.
 *
 * Wide rather than round: the send button is a circle because it commits one thing
 * and this holds a conversation open, and the two should not be mistaken for each
 * other by a thumb. On the brand gradient when it starts, because starting is the
 * thing this screen is for; on the danger wash when it is the way out, the same
 * pairing the composer already uses.
 */
@Composable
private fun TalkButton(running: Boolean, onClick: () -> Unit) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    val paint = if (running) {
        Modifier.background(colors.dangerSoft)
    } else {
        Modifier.background(Brand.gradient(colors)).background(Brand.sheen)
    }

    Row(
        modifier = Modifier
            .height(TALK_HEIGHT)
            .clip(Radii.pill)
            .then(paint)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.x6)
            .semantics { contentDescription = if (running) "Stop speaking" else "Start speaking" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        AnodexIcon(
            icon = if (running) AnodexIcon.STOP else AnodexIcon.MIC,
            size = 18.dp,
            tint = if (running) colors.dangerInk else colors.textOnAccent,
            contentDescription = null,
        )
        Text(
            text = if (running) "Stop" else "Speak",
            style = type.bodyEmphasis,
            color = if (running) colors.dangerInk else colors.textOnAccent,
        )
    }
}

private val HALO_SIZE = 220.dp
private val MARK_SIZE = 72.dp
private val TALK_HEIGHT = 56.dp
