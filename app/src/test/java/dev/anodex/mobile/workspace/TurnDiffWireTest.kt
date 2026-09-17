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

    private fun envelope(payload: String) =
        turnDiffResultFrom(Json.parseToJsonElement(payload) as JsonObject, fallbackPath = "a.ts")

    @Test
    fun `a refusal carries the computer's own reason`() {
        // The path that had nothing running it, and where the bug was: this used
        // to come back as a null the screen could not tell from "not here yet".
        val result = envelope(
            """
            {
              "ok": false,
              "error": {
                "code": "checkpoint.no-file",
                "message": "That turn did not change that file."
              }
            }
            """.trimIndent()
        )

        assertTrue(result is TurnDiffResult.Failed)
        assertEquals(
            "That turn did not change that file.",
            (result as TurnDiffResult.Failed).reason,
        )
    }

    @Test
    fun `a refusal with no message still says something`() {
        val result = envelope("""{ "ok": false }""")
        assertTrue(result is TurnDiffResult.Failed)
        assertTrue((result as TurnDiffResult.Failed).reason.isNotBlank())
    }

    @Test
    fun `a turn with no checkpoint left is a failure, not a silence`() {
        // `ok(null)` from the desktop. It is not a fault — but it is not a diff
        // either, and a screen given nothing to say waits for ever.
        val result = envelope("""{ "ok": true, "value": null }""")
        assertTrue(result is TurnDiffResult.Failed)
        assertTrue((result as TurnDiffResult.Failed).reason.contains("no longer"))
    }

    @Test
    fun `an answer that is nothing at all is a failure`() {
        // An older desktop that has never heard of this request. The phone is the
        // half that updates first, so it is the half that meets this.
        val result = envelope("""{}""")
        assertTrue(result is TurnDiffResult.Failed)
    }

    @Test
    fun `a good answer comes through as ready`() {
        val result = envelope(
            """{ "ok": true, "value": { "path": "a.ts", "kind": "modified", "binary": false,
                 "added": 1, "removed": 0, "truncated": false,
                 "rows": [{ "type": "added", "text": "one" }] } }"""
        )
        assertTrue(result is TurnDiffResult.Ready)
        assertEquals(1, (result as TurnDiffResult.Ready).diff.added)
    }
}
