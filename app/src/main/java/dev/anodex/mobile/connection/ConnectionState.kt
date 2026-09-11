package dev.anodex.mobile.connection

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The app's top-level state.
 *
 * Anodex Mobile caches nothing — the paired key and UI preferences, and no conversation history
 * (see HANDOFF_REMOTE_MOBILE.md §2 and §10.1). Every screen therefore depends on whether the
 * desktop is reachable *right now*, which makes connection state the root of the UI rather than a
 * banner bolted onto it.
 *
 * There are **four** states, not two. The one that is easy to skip is [Reconnecting], and skipping
 * it is what makes an app feel broken: without a grace period, a lift doorway or a Wi-Fi handoff
 * slams the user between a full-screen offline takeover and the normal UI and back. Flickering
 * between two whole-screen states over a two-second blip is worse than either state.
 */
sealed interface ConnectionState {

    /** No desktop has been paired yet, or the pairing was revoked. Shows the pairing flow. */
    data object Unpaired : ConnectionState

    /** Normal operation. The connection header shows host, model and context. */
    data class Connected(
        val host: HostIdentity,
        val model: ModelStatus?,
    ) : ConnectionState

    /**
     * The socket dropped and we are trying again. **Entered on every drop**, and held for
     * [GRACE_PERIOD] before giving up to [Offline]. The normal UI stays on screen throughout; only
     * the header changes.
     */
    data class Reconnecting(
        val host: HostIdentity,
        val attempt: Int,
        /** Carried through so [Offline] can report it without the machine keeping side state. */
        val lastSeenEpochMs: Long?,
    ) : ConnectionState

    /**
     * Unreachable for longer than the grace period. Takes over the whole screen — and because
     * nothing is cached, this is the screen a user sees most often after the chat itself, so it
     * gets designed rather than defaulted.
     */
    data class Offline(
        val host: HostIdentity,
        val lastSeenEpochMs: Long?,
        val networkChanged: Boolean,
    ) : ConnectionState

    companion object {
        /**
         * How long [Reconnecting] is held before falling through to [Offline].
         *
         * Long enough to ride out a Wi-Fi handoff or a walk past a lift shaft; short enough that a
         * genuinely asleep desktop is reported promptly rather than leaving the user prodding a UI
         * that cannot answer.
         */
        val GRACE_PERIOD: Duration = 6.seconds
    }
}

/**
 * The paired desktop.
 *
 * **Identity, not address.** Pairing binds to this identity and never to an IP: the address differs
 * between Wi-Fi and Tailscale, and pairing to an address would mean re-pairing every time the user
 * changes network. Cheap now, annoying to retrofit later (§10.1).
 */
data class HostIdentity(
    /** Stable id established at pairing; survives every address change. */
    val id: String,
    /** The machine's own name, e.g. "STUDIO-PC". What the user is shown. */
    val displayName: String,
)

/** What the desktop currently has loaded. Feeds the connection header; read-only on the phone. */
data class ModelStatus(
    val name: String,
    /**
     * How much of the context is in use, or **null when the desktop did not say**.
     *
     * Nullable, and that is the whole of the fix. The desktop sends
     * `contextTokensUsed: this.contextSequence?.nextTokenIndex` — which is absent
     * whenever there is no live sequence, meaning most of the time a phone is
     * looking at it. The phone read that absence as the number zero, so the meter
     * sat resolutely empty and looked broken, when what it actually had was no
     * figure at all.
     *
     * Zero and unknown are different claims: one says the context is empty, the
     * other says nobody counted. Drawing an empty bar for the second is this
     * codebase's oldest defect in a new place.
     */
    val contextUsedTokens: Int?,
    val contextTotalTokens: Int,
    /**
     * The conversation the count was measured against.
     *
     * The desktop measures usage for whichever conversation its engine is currently
     * holding, and says which one. A phone open on a different conversation showing
     * that number would be reporting someone else's context as its own — so when
     * this does not match what is on screen, there is again no figure to draw.
     */
    val contextConversationId: String? = null,
    /**
     * The file behind it, when the desktop said.
     *
     * Only used to mark the running model in the picker. Matching on the name would
     * be wrong: the same model at two quantisations carries the same name, and the
     * picker would tick both.
     */
    val path: String = "",
) {
    /**
     * 0f..1f, or null when there is no honest figure to draw.
     *
     * Null when no model is loaded, and null when the desktop has not counted. The
     * meter and the header's ring both draw nothing at all for null, which is the
     * correct rendering of "not known" and distinguishable from a genuinely empty
     * context, which draws an empty track.
     */
    val contextFraction: Float?
        get() {
            if (contextTotalTokens <= 0) return null
            val used = contextUsedTokens ?: return null
            return (used.toFloat() / contextTotalTokens).coerceIn(0f, 1f)
        }
}

/**
 * Whether the phone is on a different network than the one it paired on.
 *
 * "You're on the wrong network" is the actual cause of most unreachable desktops, and saying so
 * turns a generic failure into an actionable one. Note what this deliberately does **not** carry:
 * the network's *name*. Reading the current SSID on Android requires location permission — without
 * it `getSSID()` returns `<unknown ssid>` — and that is a runtime location prompt on an app that
 * otherwise needs none. Knowing *that* the network changed sends the user to check their Wi-Fi,
 * which is nearly all of the value, at none of the cost (§6.1).
 */
enum class NetworkRelation {
    /** Same network as the one we paired on. */
    SAME,

    /** A different network. The likely reason the desktop cannot be reached. */
    CHANGED,

    /** No usable network at all — the phone itself is offline. Say that instead. */
    NONE,

    /** Not yet determined, or the phone has never successfully paired. */
    UNKNOWN,
}
