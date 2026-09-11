package dev.anodex.mobile

/**
 * What a screen should hold after a read that might not have arrived.
 *
 * The rule this codebase keeps re-learning, given one name and one place.
 * `AGENTS.md` calls reporting a failure as an emptiness the most common defect
 * shape here and says it has been found in six-plus places; four more were found
 * and fixed the same week. Every one of them was written by somebody who knew the
 * rule perfectly well — `getOrDefault(emptyList())` is not a lapse in
 * understanding, it is the shortest thing to type.
 *
 * So the shortest thing to type is now the correct one. [orKeep] is fewer
 * characters than `getOrDefault(emptyList())` and does the right thing, which is
 * the only mechanism that has ever reliably stopped a recurring mistake.
 *
 * It also puts the decision somewhere a test can reach. The refreshes all live in
 * `AnodexViewModel`, which takes an `Application`, builds its own store, monitor
 * and notifier, and cannot be instantiated in a JVM unit test — which is why that
 * one file concentrates a defect the rest of the app is tested against.
 */
data class Refreshed<T>(
    /** What to show: the new reading, or the last good one. */
    val value: T,
    /** What went wrong, or null when nothing did. Clearing it is as important as setting it. */
    val error: String?,
)

/**
 * Keep what you had, and say what went wrong.
 *
 * Stale is not the same as wrong. A list that failed to refresh still describes
 * things that exist on the computer — the conversations are still there, the
 * projects are still there — so it stays on screen, with the failure said above it
 * rather than in place of it. Replacing it with an empty list turns "we could not
 * ask" into "there is nothing", which is a claim about the user's machine made on
 * the strength of a request that never arrived.
 *
 * @param previous what the screen is showing now. On failure it goes on showing it.
 * @param whenItFails what to say if the throwable has nothing useful to say itself.
 *   Written for the person reading it, not for the log.
 */
fun <T> Result<T>.orKeep(previous: T, whenItFails: String): Refreshed<T> = fold(
    onSuccess = { Refreshed(it, null) },
    onFailure = { failure ->
        // A blank message is as useless as no message, and both happen: plenty of
        // exceptions carry "" or the class name and nothing more.
        val said = failure.message?.takeIf { it.isNotBlank() }
        Refreshed(previous, said ?: whenItFails)
    },
)
