package dev.anodex.mobile.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the two apps, at the one place a change on the desktop lands.
 *
 * These payloads are what `checkpoints:diff-file` answers with — copied from the
 * contract rather than imagined, because a test that invents the shape it is
 * parsing only proves the parser agrees with itself.
 *
 * The rule worth pinning is the last one. A row type this build has never seen
 * must still draw, as context, because a diff that silently drops lines it does
 * not recognise is a diff that under-reports a change — and that is read by
 * somebody deciding whether to trust a run done while they were away.
 */
class TurnDiffWireTest {

    private fun parse(payload: String) =
        turnDiffFrom(Json.parseToJsonElement(payload) as JsonObject, fallbackPath = "fallback.ts")

    @Test
    fun `an ordinary change comes across whole`() {
        val diff = parse(
            """
            {
              "path": "src/sim/useDragBody.ts",
              "kind": "modified",
              "binary": false,
              "added": 2,
              "removed": 1,
              "truncated": false,
              "rows": [
                { "type": "gap", "text": "", "oldLine": null, "newLine": null, "count": 12 },
                { "type": "unchanged", "text": "export function useDragBody() {", "oldLine": 13, "newLine": 13 },
                { "type": "removed", "text": "  const basis = cameraBasis()", "oldLine": 14, "newLine": null },
                { "type": "added", "text": "  const basis = useRef(cameraBasis())", "oldLine": null, "newLine": 14 }
              ]
            }
            """.trimIndent()
        )

        assertEquals("src/sim/useDragBody.ts", diff.path)
        assertEquals(2, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(4, diff.rows.size)
        assertEquals(DiffRow.Kind.GAP, diff.rows[0].kind)
        assertEquals(12, diff.rows[0].collapsed)
        assertEquals(DiffRow.Kind.REMOVED, diff.rows[2].kind)
        assertEquals("  const basis = useRef(cameraBasis())", diff.rows[3].text)
    }

    @Test
    fun `a binary file arrives with nothing to draw and says which it was`() {
        val diff = parse(
            """
            {
              "path": "assets/logo.png",
              "kind": "created",
              "binary": true,
              "added": 0,
              "removed": 0,
              "truncated": false,
              "rows": []
            }
            """.trimIndent()
        )

        assertTrue(diff.binary)
        assertEquals("created", diff.kind)
        assertTrue(diff.rows.isEmpty())
    }

    @Test
    fun `a cut-short diff keeps the whole count`() {
        // The desktop counts before it cuts, and the phone must carry that
        // through — the summary is what somebody judges the size of a change by.
        val diff = parse(
            """
            {
              "path": "package-lock.json",
              "kind": "modified",
              "binary": false,
              "added": 4000,
              "removed": 3990,
              "truncated": true,
              "rows": [{ "type": "added", "text": "one", "oldLine": null, "newLine": 1 }]
            }
            """.trimIndent()
        )

        assertTrue(diff.truncated)
        assertEquals(4000, diff.added)
        assertEquals(1, diff.rows.size)
    }

    @Test
    fun `a row type this build has never seen still draws`() {
        // Not hypothetical: the desktop's own `DiffLineType` already has a member
        // this screen does not special-case, and more can be added there without
        // this app being rebuilt. Dropping the row would quietly shorten the file.
        val diff = parse(
            """
            {
              "path": "a.ts",
              "kind": "modified",
              "binary": false,
              "added": 0,
              "removed": 0,
              "truncated": false,
              "rows": [{ "type": "something-new", "text": "a line", "oldLine": 1, "newLine": 1 }]
            }
            """.trimIndent()
        )

        assertEquals(1, diff.rows.size)
        assertEquals(DiffRow.Kind.UNCHANGED, diff.rows[0].kind)
        assertEquals("a line", diff.rows[0].text)
    }

    @Test
    fun `a missing field falls back rather than throwing`() {
        // A frame from an older desktop. The phone is the half that gets updated
        // first, so it meets these — and an exception here would take down the
        // screen rather than showing a partly-known diff.
        val diff = parse("""{ "rows": [] }""")

        assertEquals("fallback.ts", diff.path)
        assertEquals("modified", diff.kind)
        assertEquals(0, diff.added)
    }
}
