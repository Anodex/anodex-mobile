package dev.anodex.mobile.pairing

import dev.anodex.mobile.connection.HostIdentity

/**
 * Everything the phone keeps about the desktop it is paired to.
 *
 * This is the *entire* local persistence story. Anodex Mobile stores the paired key and UI
 * preferences and nothing else — no conversation cache, no local database (handoff §2, §10.1).
 * Caching transcripts would buy instant scrollback and readable history while the PC sleeps, at
 * the cost of the user's conversations living on a second device, which softens the promise the
 * whole product rests on. A phone that says "MERLIN-PC is asleep" is honest in a way one showing
 * stale transcripts is not.
 *
 * **Do not add fields here casually.** Anything persisted alongside the key inherits its
 * sensitivity and reopens that decision.
 */
data class PairedHost(
    val identity: HostIdentity,

    /**
     * The shared secret established at pairing, authenticating this phone to that desktop.
     *
     * Base64. Encrypted at rest under a hardware-backed key (see [PairedHostStore]) and never
     * logged, never shown, never included in a diagnostic bundle.
     */
    val secret: String,

    /**
     * The self-signed certificate fingerprint pinned when pairing.
     *
     * No real certificate exists for a LAN address, so the phone pins what it saw at pairing and
     * refuses anything else afterwards (§7.2). A mismatch is not a warning to click through — it
     * is the exact signature of someone else answering on that address.
     */
    val certificateFingerprint: String,

    /**
     * Identifier for the network the pairing happened on.
     *
     * Deliberately not the SSID: reading that needs a runtime location permission this app does
     * not ask for. This is only ever compared for equality, to answer "is this the same network I
     * paired on?" — enough to explain most unreachable desktops without naming anything (§6.1).
     */
    val pairedNetworkId: String?,

    /** When the desktop was last reached, for the offline screen. */
    val lastSeenEpochMs: Long?,

    /**
     * Where the desktop was last known to be.
     *
     * A hint, not an identity: pairing binds to [identity], and this is expected to go stale
     * when the user changes network. Kept so a reconnect has somewhere to try first rather
     * than needing a fresh QR every time.
     */
    val address: String,
    val port: Int,
)
