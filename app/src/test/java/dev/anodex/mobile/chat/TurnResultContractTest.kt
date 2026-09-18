package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every field a finished turn arrives with, and what this app does about it.
 *
 * The computer answers `chat:send` with a `ChatResult`. On 2026-09-17 that
 * carried eighteen fields and this app read six of them. Two of the twelve it
 * ignored were features the desktop had shown for months: `webSources`, which
 * made every citation in a reply a dead number here *and* on the computer once
 * the conversation was saved, and `memoryUsed`, which made memory retrieval
 * invisible. Nothing failed. Nothing logged. They were found by writing the
 * comparison out by hand.
 *
 * This is that comparison, kept. Every field is listed below as either read or
 * deliberately ignored, with a reason for the second. A field added to
 * `ChatResult` on the desktop and synced here lands in neither list and fails
 * this test, which is the only moment anybody is going to be asked the question
 * "does the phone want this?"
 *
 * It cannot catch a field the desktop adds and nobody syncs -- that is what
 * `tools/sync-design-contract.sh` is for, and why the contract is a reviewed
 * commit rather than a fetch.
 */
class TurnResultContractTest {

    /** Fields this app reads off a finished turn and does something with. */
    private val read = setOf(
        "content",
        "conversationId",
        "messageId",
        "stopped",
        "stopReason",
        "thinking",
        "webSources",
        "webSearchAttempted",
        "memoryUsed",
        "transcriptRecallUsed",
    )

    /**
     * Fields this app deliberately does not read, and why.
     *
     * A reason per entry, because "we ignore it" and "we forgot it" look
     * identical in a list of names -- which is exactly how the two that mattered
     * sat here unnoticed.
     */
    private val ignored = mapOf(
        "turnOutcome" to
            "Already appended to `content` by the computer, so reading it would print it twice.",
        "stats" to
            "Token counts and timings. The phone shows context pressure from the model state " +
                "instead, which is current rather than per-turn.",
        "context" to
            "The Context Ledger, which is the computer's own accounting and has no screen here.",
        "contextBudget" to
            "Engine-reported fixed-context accounting, same reasoning as `context`.",
        "contextAssemblies" to
            "Per-cycle reference assembly reports. Diagnostics for the desktop's own context work.",
        "checkpoint" to
            "The phone reads changed files from `checkpoints:list` instead, which also works " +
                "for a conversation it did not watch being generated.",
        "goalOutcome" to
            "Only present for a goal run, and the phone cannot start one -- it only displays " +
                "the goal of a run started at the computer.",
        "stopDetail" to
            "The long form of `stopReason`, which is read. The detail is desktop diagnostics.",
    )

    private val chatResultFields: Set<String> by lazy {
        val stream = javaClass.getResourceAsStream("/anodex-protocol.json")
            ?: error(
                "protocol/anodex-protocol.json is not on the test classpath — " +
                    "run tools/sync-design-contract.sh",
            )
        val contract = Json.parseToJsonElement(stream.reader().readText()).jsonObject
        val definitions = contract["definitions"]?.jsonObject
            ?: error("the contract has no definitions")
        val chatResult = definitions["ChatResult"]?.jsonObject
            ?: error("the contract has no ChatResult")
        chatResult["properties"]?.jsonObject?.keys
            ?: error("ChatResult has no properties")
    }

    @Test
    fun `every field of a finished turn is accounted for`() {
        val accounted = read + ignored.keys
        val unaccounted = chatResultFields - accounted

        assertTrue(
            "The computer now sends these on every turn and this app neither reads them nor " +
                "says why not: ${unaccounted.sorted()}. Add each to `read` once it is used, or " +
                "to `ignored` with the reason. This is the check that `webSources` did not have.",
            unaccounted.isEmpty(),
        )
    }

    @Test
    fun `nothing is listed that the computer no longer sends`() {
        // The other direction, and the one that rots quietly: a field removed on
        // the desktop leaves a name here that reads like a decision and is only
        // a memory of one.
        val stale = (read + ignored.keys) - chatResultFields
        assertTrue("`ChatResult` no longer has: ${stale.sorted()}", stale.isEmpty())
    }

    @Test
    fun `a field cannot be both read and ignored`() {
        val both = read intersect ignored.keys
        assertTrue("listed twice: ${both.sorted()}", both.isEmpty())
    }

    @Test
    fun `every ignored field says why`() {
        for ((field, reason) in ignored) {
            assertTrue("$field has no reason", reason.length > 30)
        }
    }

    @Test
    fun `the contract is the one the desktop generated`() {
        // A sanity check on the sync rather than on the app: an empty or
        // truncated copy would make every assertion above pass for the wrong
        // reason, which is the failure mode a contract test has to not have.
        assertTrue("suspiciously few fields", chatResultFields.size >= 15)
        assertEquals(true, chatResultFields.contains("content"))
    }
}
