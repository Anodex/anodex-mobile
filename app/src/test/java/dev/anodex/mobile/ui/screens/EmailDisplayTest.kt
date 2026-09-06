package dev.anodex.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two things every inbox row shows: who it is from, and how long ago.
 *
 * Both are read at a glance and neither is recoverable when wrong — a row that says
 * `"Ada Lovelace" <ada@example.com>` in full has pushed the subject off the screen,
 * and one that says the wrong age sends the user to the wrong message.
 */
class EmailDisplayTest {

    @Test
    fun `a display name wins over the address`() {
        // The address is forty characters of noise on a phone-width row.
        assertEquals("Ada Lovelace", senderName("Ada Lovelace <ada@example.com>"))
    }

    @Test
    fun `a quoted display name loses its quotes`() {
        assertEquals("Lovelace, Ada", senderName("\"Lovelace, Ada\" <ada@example.com>"))
    }

    @Test
    fun `a bare address is shown as itself`() {
        assertEquals("billing@example.com", senderName("billing@example.com"))
    }

    @Test
    fun `an address in angle brackets with no name loses the brackets`() {
        assertEquals("ada@example.com", senderName("<ada@example.com>"))
    }

    @Test
    fun `an empty sender does not crash the row`() {
        // Malformed headers exist, and a thread with one still has to be openable.
        assertEquals("", senderName(""))
    }

    @Test
    fun `recent mail reads as minutes, then hours, then days`() {
        val now = 1_700_000_000_000L
        val minute = 60_000L

        assertEquals("now", relativeTime(now - 30_000, now))
        assertEquals("5m", relativeTime(now - 5 * minute, now))
        assertEquals("3h", relativeTime(now - 3 * 60 * minute, now))
        assertEquals("2d", relativeTime(now - 2 * 24 * 60 * minute, now))
        assertEquals("3w", relativeTime(now - 21 * 24 * 60 * minute, now))
    }

    @Test
    fun `the boundaries do not report the larger unit early`() {
        val now = 1_700_000_000_000L
        val minute = 60_000L

        // 59 minutes is not an hour, and 23 hours is not a day. Rounding up here
        // makes something that arrived this morning look like yesterday.
        assertEquals("59m", relativeTime(now - 59 * minute, now))
        assertEquals("23h", relativeTime(now - 23 * 60 * minute, now))
        assertEquals("6d", relativeTime(now - 6 * 24 * 60 * minute, now))
    }

    @Test
    fun `a message from the future reads as now rather than a negative age`() {
        // Clock skew between the phone and the mail server is ordinary.
        val now = 1_700_000_000_000L

        assertEquals("now", relativeTime(now + 60_000, now))
    }

    @Test
    fun `a missing date shows nothing rather than fifty years`() {
        // A zero timestamp is an absent one. Rendering it as an age would date every
        // affected message to 1970.
        assertEquals("", relativeTime(0, 1_700_000_000_000L))
    }
}
