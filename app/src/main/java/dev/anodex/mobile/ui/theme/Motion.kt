package dev.anodex.mobile.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Anodex's motion tokens, ported from `styles/theme.css`.
 *
 * **The house rule on bespoke motion, which applies here exactly as it does on the desktop:**
 * designed, characterful motion is reserved for *rare, event-driven moments* — a first connection,
 * a reconnect, a conversation's first reply — and is never an ambient loop. Everything else uses
 * the plain [standard] curve at one of the durations below and gets out of the way. An app that
 * is always moving is an app that is always distracting.
 */
object Motion {
    /** `--ease` — the default. Symmetric, unremarkable, correct for almost everything. */
    val standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** `--ease-out` — decelerating hard. For things arriving on screen. */
    val decelerate: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** `--ease-emphasized` — for the rare moment that should feel deliberate. */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** `--transition-fast` (120ms). Hover/press feedback; state that should feel instant. */
    const val FAST_MS = 120

    /** `--transition` (180ms). The default transition. */
    const val NORMAL_MS = 180

    /** `--motion-short` (160ms). */
    const val SHORT_MS = 160

    /** `--motion-medium` (220ms). Element arrival — the desktop's `messageIn`. */
    const val MEDIUM_MS = 220

    /** `--motion-long` (420ms). Whole-screen transitions and the one-shot arrival flares. */
    const val LONG_MS = 420

    /**
     * How long a reply takes to settle into place. See [arrive].
     *
     * Longer than every other duration here, and chosen by looking at it rather than
     * derived from the scale: 220, 320, 420 and 880 were put side by side at real
     * speed and this is the one that was picked. It is deliberately past the point
     * where motion is felt rather than watched — the reply landing is meant to be
     * seen landing.
     *
     * The cost is real and was accepted knowingly: a reply cannot be read until it
     * has arrived, so this is added to every answer the app gives. It is the only
     * duration in the app allowed to be this long, and it is spent exactly once per
     * turn, on the one event the app exists to deliver.
     */
    const val ARRIVAL_MS = 880

    fun <T> fast(): FiniteAnimationSpec<T> = tween(FAST_MS, easing = standard)
    fun <T> normal(): FiniteAnimationSpec<T> = tween(NORMAL_MS, easing = standard)
    /**
     * A reply arriving — the desktop's `messageIn`.
     *
     * The one piece of character motion in the app that is not also a state
     * indicator, which is why it gets [decelerate]: it should look like something
     * coming to rest, not like something being faded up.
     */
    fun <T> arrive(): FiniteAnimationSpec<T> = tween(ARRIVAL_MS, easing = decelerate)
    fun <T> screen(): FiniteAnimationSpec<T> = tween(LONG_MS, easing = emphasized)
}

/**
 * Whether the user has asked the system to reduce or disable animation.
 *
 * The desktop honours `@media (prefers-reduced-motion: reduce)` by dropping its arrival flares and
 * the activity shimmer entirely. Android's equivalent signal is the animator duration scale, which
 * accessibility settings and battery savers both set to zero. Composables that add motion for
 * character — as opposed to motion that conveys state — should read this and render the settled
 * end state instead.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * What this app should do about animation, whatever the phone is doing.
 *
 * `SYSTEM` defers to the phone's own animator scale, which is where accessibility
 * settings land and is the right default. The other two exist because deferring is
 * not always what somebody wants: an app can be the one thing they want still, or the
 * one thing they want moving.
 */
enum class MotionPreference(val label: String, val description: String) {
    SYSTEM("Follow the phone", "Matches your accessibility settings"),
    REDUCED("Reduce motion", "Still, in this app only"),
    FULL("Full motion", "Animate even if the phone does not"),
}

@Composable
internal fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}
