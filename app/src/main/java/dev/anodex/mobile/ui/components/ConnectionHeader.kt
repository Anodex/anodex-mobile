package dev.anodex.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anodex.mobile.connection.ConnectionState
import dev.anodex.mobile.connection.HostIdentity
import dev.anodex.mobile.connection.ModelStatus
import dev.anodex.mobile.ui.theme.AnodexTheme
import dev.anodex.mobile.ui.theme.LocalReducedMotion
import dev.anodex.mobile.ui.theme.Motion
import dev.anodex.mobile.ui.theme.Radii
import dev.anodex.mobile.ui.theme.Spacing
import dev.anodex.mobile.ui.theme.Touch

/**
 * The strip that says *which machine is doing the work*.
 *
 * Present on every screen, and load-bearing rather than decorative: without it the phone gives no
 * sense that the model, the files and the tools are somewhere else. It carries the host, the
 * connection state, which model that host has loaded, and how full its context is.
 */
@Composable
fun ConnectionHeader(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    /** Opens the conversation list. Null on screens where there is nothing to open. */
    onOpenConversations: (() -> Unit)? = null,
) {
    val colors = AnodexTheme.colors
    val type = AnodexTheme.type

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bgSurface)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        if (onOpenConversations != null) {
            // The whole left edge is the target rather than a small glyph: this is a
            // phone, and a 48dp region is the difference between a control that works
            // one-handed and one that does not.
            Box(
                modifier = Modifier
                    .size(Touch.minTarget)
                    .clip(Radii.md)
                    .clickable(onClick = onOpenConversations),
                contentAlignment = Alignment.Center,
            ) {
                StatusDot(state)
            }
        } else {
            StatusDot(state)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.hostName() ?: "Not paired",
                style = type.bodyEmphasis,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.statusLine(),
                style = type.meta,
                color = state.statusColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val model = (state as? ConnectionState.Connected)?.model
        if (model != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = model.name,
                    style = type.meta,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ContextMeter(
                    fraction = model.contextFraction,
                    modifier = Modifier.padding(top = Spacing.x1),
                )
            }
        }
    }
}

/**
 * How full the desktop's context window is.
 *
 * Warm and then dangerous as it fills, because a nearly-full context is the thing most likely to
 * explain a reply that arrives truncated or a conversation that starts compacting — worth seeing
 * before it happens rather than diagnosing after.
 */
@Composable
private fun ContextMeter(
    fraction: Float?,
    modifier: Modifier = Modifier,
) {
    if (fraction == null) return
    val colors = AnodexTheme.colors

    val fill = when {
        fraction >= 0.9f -> colors.danger
        fraction >= 0.7f -> colors.warn
        else -> colors.accent
    }
    val animatedFill by animateColorAsState(fill, Motion.normal(), label = "contextFill")
    val animatedFraction by animateFloatAsState(fraction, Motion.normal(), label = "contextWidth")

    Box(
        modifier = modifier
            .width(56.dp)
            .height(3.dp)
            .clip(Radii.pill)
            .background(colors.bgElevated),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedFraction)
                .height(3.dp)
                .clip(Radii.pill)
                .background(animatedFill),
        )
    }
}

/**
 * The state indicator, and the one piece of designed motion in this component.
 *
 * Connecting pulses — that is state being conveyed, not character, so it runs while the state
 * lasts. Arriving at [ConnectionState.Connected] flares once and settles: a rare, event-driven
 * moment, which is the only category the house rule allows bespoke motion for. It never loops.
 */
@Composable
private fun StatusDot(state: ConnectionState) {
    val colors = AnodexTheme.colors
    val reducedMotion = LocalReducedMotion.current
    val target = state.statusColor()
    val color by animateColorAsState(target, Motion.normal(), label = "dotColor")

    val isSettling = state is ConnectionState.Connected
    val isWorking = state is ConnectionState.Reconnecting

    // One-shot arrival: scale overshoots and returns as the connection lands.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(isSettling) {
        if (isSettling && !reducedMotion) {
            arrived = false
            arrived = true
        }
    }
    val arrivalScale by animateFloatAsState(
        targetValue = if (arrived || !isSettling) 1f else 1.6f,
        animationSpec = tween(Motion.LONG_MS, easing = Motion.decelerate),
        label = "dotArrival",
    )

    // Held pulse while reconnecting. This one repeats deliberately: it is the visual form of "still
    // trying", and it stops the moment the state does.
    val pulse = rememberInfiniteTransition(label = "dotPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "dotPulseAlpha",
    )

    val alpha = if (isWorking && !reducedMotion) pulseAlpha else 1f

    Box(
        modifier = Modifier
            .size(8.dp)
            .scale(arrivalScale)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

// --- state → presentation ----------------------------------------------------------------------

private fun ConnectionState.hostName(): String? = when (this) {
    is ConnectionState.Connected -> host.displayName
    is ConnectionState.Reconnecting -> host.displayName
    is ConnectionState.Offline -> host.displayName
    ConnectionState.Unpaired -> null
}

private fun ConnectionState.statusLine(): String = when (this) {
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Reconnecting -> "Reconnecting…"
    is ConnectionState.Offline -> "Offline"
    ConnectionState.Unpaired -> "Scan the QR code on your computer"
}

@Composable
private fun ConnectionState.statusColor(): Color {
    val colors = AnodexTheme.colors
    return when (this) {
        is ConnectionState.Connected -> colors.success
        is ConnectionState.Reconnecting -> colors.warn
        is ConnectionState.Offline -> colors.danger
        ConnectionState.Unpaired -> colors.textFaint
    }
}

// --- previews ----------------------------------------------------------------------------------

private val PreviewHost = HostIdentity(id = "h1", displayName = "MERLIN-PC")

@Preview(name = "Connected — dark", backgroundColor = 0xFF0C0C0C, showBackground = true)
@Composable
private fun PreviewConnected() {
    AnodexTheme(darkTheme = true) {
        ConnectionHeader(
            ConnectionState.Connected(
                host = PreviewHost,
                model = ModelStatus("Qwen3-30B", contextUsedTokens = 5_800, contextTotalTokens = 8_192),
            )
        )
    }
}

@Preview(name = "Reconnecting — dark", backgroundColor = 0xFF0C0C0C, showBackground = true)
@Composable
private fun PreviewReconnecting() {
    AnodexTheme(darkTheme = true) {
        ConnectionHeader(
            ConnectionState.Reconnecting(PreviewHost, attempt = 2, lastSeenEpochMs = null)
        )
    }
}

@Preview(name = "Connected — light", backgroundColor = 0xFFF9F8F5, showBackground = true)
@Composable
private fun PreviewConnectedLight() {
    AnodexTheme(darkTheme = false) {
        ConnectionHeader(
            ConnectionState.Connected(
                host = PreviewHost,
                model = ModelStatus("Qwen3-30B", contextUsedTokens = 7_700, contextTotalTokens = 8_192),
            )
        )
    }
}
