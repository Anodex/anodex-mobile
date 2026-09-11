package dev.anodex.mobile.connection

/**
 * Why the computer went away, in its own words.
 *
 * A socket that dies tells you nothing about why. Asleep, quit, remote access
 * switched off, Wi-Fi gone, laptop carried out of range — every one of them arrives
 * as the same dead connection, which is why the offline screen used to have to
 * offer a list:
 *
 * > Check the computer is awake, that remote access is on in its Settings, and that
 * > Windows Firewall is not blocking Anodex.
 *
 * Three hypotheses, and in most cases the computer knew which one it was. It now
 * says so on the way out, as a WebSocket close code.
 *
 * **These numbers are a wire contract** with `src/shared/remoteFarewell.ts` on the
 * desktop, mirrored here by hand for the same reason the frame types are — there is
 * no shared build between an Electron app and an Android one. `RemoteFarewellTest`
 * pins them, and a desktop that sends a code this build has never heard of falls
 * back to the generic explanation rather than inventing one.
 */
enum class RemoteFarewell(val code: Int) {
    /** Anodex was closed. Nothing is wrong; it will be back when it is opened. */
    QUITTING(4001),

    /** The computer suspended. Waking it is the whole fix. */
    SLEEPING(4002),

    /** Remote access was switched off at the machine, deliberately. */
    DISABLED(4003),

    /**
     * The listener is being rebound, usually onto a different port.
     *
     * The one reason not to settle into an offline screen — the computer is not
     * going anywhere and will be answering again in a moment.
     */
    RESTARTING(4004),

    /** This phone was unpaired at the machine. Reconnecting will not help. */
    UNPAIRED(4005);

    /**
     * What to tell the user, written as a fact rather than a checklist.
     *
     * Each one names the thing to do, because "asleep" and "closed" send somebody to
     * different places and the whole value of being told is in that difference.
     */
    fun explain(hostName: String): String = when (this) {
        QUITTING -> "$hostName closed Anodex. Open it again on the computer and this reconnects on its own."
        SLEEPING -> "$hostName went to sleep. Wake the computer and this reconnects on its own."
        DISABLED -> "Remote access was switched off on $hostName. Turn it back on in its Settings."
        RESTARTING -> "$hostName is restarting its connection. This should only take a moment."
        UNPAIRED -> "$hostName no longer accepts this phone. Pair again from the computer."
    }

    /** Whether to keep trying now, or wait for somebody to do something. */
    val reconnectsImmediately: Boolean
        get() = this == RESTARTING

    companion object {
        /**
         * The farewell for a close code, or null for one this build does not know.
         *
         * Null rather than a guess. A newer desktop may have reasons this app has
         * never heard of, and the generic explanation is honest about that where an
         * invented one would not be.
         */
        fun ofCode(code: Int): RemoteFarewell? = entries.firstOrNull { it.code == code }
    }
}
