package dev.anodex.mobile.email

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which mailbox a search searches.
 *
 * On a phone the folder strip is the only thing saying where you are, so a
 * search that ignores it puts mail from the whole account under a heading that
 * still says Trash — which looks like results rather than like a bug. The field
 * was missing from the request rather than from any provider: Gmail, Microsoft
 * and plain IMAP have all taken a mailbox alongside a query the whole time.
 */
class SearchScopeTest {

    @Test
    fun `a folder is carried with the query`() {
        val request = searchRequest("invoice", 50, "[Gmail]/Trash")

        assertEquals("invoice", request["query"]?.jsonPrimitive?.content)
        assertEquals("[Gmail]/Trash", request["mailbox"]?.jsonPrimitive?.content)
    }

    @Test
    fun `no folder means the account, as it always did`() {
        // The inbox case, and the common one. Sending a mailbox the phone
        // invented here would narrow every search nobody asked to narrow.
        assertNull(searchRequest("invoice", 50, null)["mailbox"])
    }

    @Test
    fun `a blank folder is no folder`() {
        assertNull(searchRequest("invoice", 50, "   ")["mailbox"])
        assertNull(searchRequest("invoice", 50, "")["mailbox"])
    }

    @Test
    fun `the limit is always asked for`() {
        // Without it the computer picks its own, and a phone list that silently
        // holds a different number of rows than it asked for is how "there is no
        // more mail" gets said wrongly.
        assertEquals("25", searchRequest("invoice", 25, null)["limit"]?.jsonPrimitive?.content)
    }
}
