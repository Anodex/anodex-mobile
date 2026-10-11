package dev.anodex.mobile.notify

import dev.anodex.mobile.transport.PHONE_CAPABILITIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the computer and the phone must agree on for "answer ready".
 *
 * Pinned written out rather than derived, because both ends are separate builds: the
 * desktop sends `reply-ready` only to a phone that announced `reply-ready.1`, and a
 * spelling that drifted on one side would quietly mean no notification, or two.
 */
class ReplyReadyContractTest {

    @Test
    fun `this build announces that it shows answer-ready from the background`() {
        assertTrue(PHONE_CAPABILITIES.contains("reply-ready.1"))
    }

    @Test
    fun `the kind is recognised, and read as finished by anything that does not know it`() {
        assertTrue(NotificationKind.isReplyReady("reply-ready"))
        assertFalse(NotificationKind.isReplyReady("finished"))
        assertEquals(NotificationKind.FINISHED, NotificationKind.parse("reply-ready"))
    }
}
