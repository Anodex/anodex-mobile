package dev.anodex.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The offline screen's "last seen" phrasing.
 *
 * Worth testing despite being small: it is the one piece of copy on that screen that is computed
 * rather than written, it crosses three unit boundaries, and it is read by someone who is already
 * unhappy that their desktop is unreachable. An off-by-one that says "0 minutes ago" is the kind
 * of thing nobody notices until a user does.
 */
class RelativeLastSeenTest {

    private val now = 1_757_000_000_000L

    private fun ago(millis: Long) = relativeLastSeen(now - millis, now)

    @Test
    fun `under a minute reads as moments`() {
        assertEquals("moments ago", ago(0))
        assertEquals("moments ago", ago(59_000))
    }

    @Test
    fun `singular units are not pluralised`() {
        assertEquals("a minute ago", ago(60_000))
        assertEquals("an hour ago", ago(60 * 60_000))
        assertEquals("yesterday", ago(24 * 60 * 60_000))
    }

    @Test
    fun `plural units count correctly`() {
        assertEquals("14 minutes ago", ago(14 * 60_000))
        assertEquals("59 minutes ago", ago(59 * 60_000))
        assertEquals("3 hours ago", ago(3 * 60 * 60_000))
        assertEquals("23 hours ago", ago(23 * 60 * 60_000))
        assertEquals("4 days ago", ago(4L * 24 * 60 * 60_000))
    }

    @Test
    fun `a clock that ran backwards does not produce a negative duration`() {
        // The desktop's last-seen timestamp and the phone's clock are set independently, so a
        // phone whose clock is behind can legitimately compute a future last-seen. Say "moments"
        // rather than "-3 minutes ago".
        assertEquals("moments ago", relativeLastSeen(now + 5 * 60_000, now))
    }
}
