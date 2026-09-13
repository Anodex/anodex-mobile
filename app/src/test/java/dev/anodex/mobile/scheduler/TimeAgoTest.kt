package dev.anodex.mobile.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A time that has already passed, read against a computer clock that may run ahead. */
class TimeAgoTest {

    private val now = 1_789_300_000_000L

    @Test
    fun `a computer clock a few seconds ahead still reads as just now`() {
        assertEquals("just now", timeAgo(now + 4_000, now))
        assertEquals("just now", timeAgo(now + 10 * 60_000, now))
    }

    @Test
    fun `the past reads as it always did`() {
        assertEquals("5 minutes ago", timeAgo(now - 5 * 60_000, now))
    }

    @Test
    fun `no time is no label`() {
        assertNull(timeAgo(null, now))
        assertNull(timeAgo(0, now))
    }
}
