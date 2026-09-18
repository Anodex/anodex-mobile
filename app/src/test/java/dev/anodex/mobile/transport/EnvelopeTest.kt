package dev.anodex.mobile.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Opening the `Result` a handler answered with.
 *
 * This is two lines of code and it cost a whole round of believing the context ring
 * was fixed. The channel was right, the desktop's projection was right, the phone
 * asked at the right moment — and the reply was handed to the parser still inside
 * its envelope, so the parser looked for `usedTokens` on an object that only has
 * `ok` and `value`, found nothing, and reported nothing. Indistinguishable from a
 * feature that does not work.
 *
 * Not every handler wraps: `models:get-state` answers with the engine state
 * directly. So this is applied per channel, and getting it wrong in either
 * direction is silent.
 */
class EnvelopeTest {

    private fun parse(json: String) = Json.parseToJsonElement(json)

    @Test
    fun `a success gives up its value`() {
        val value = parse("""{"ok":true,"value":{"usedTokens":4949,"contextSize":32768}}""").unwrap()

        assertEquals(4949, ((value as JsonObject)["usedTokens"] as JsonPrimitive).intOrNull)
    }

    @Test
    fun `a failure gives up nothing`() {
        // An `err(...)` carries a code and a message, never a value. Returning the
        // envelope's contents here would hand a parser the error object to read
        // fields off.
        assertNull(parse("""{"ok":false,"error":{"code":"x","message":"y"}}""").unwrap())
    }

    @Test
    fun `the envelope itself is never mistaken for the value`() {
        // The failure that started this: an unopened envelope has none of the fields
        // a caller is looking for, so it parses as an absence rather than an error.
        val envelope = parse("""{"ok":true,"value":{"usedTokens":4949}}""")

        assertNull((envelope as JsonObject)["usedTokens"])
        assertEquals(4949, ((envelope.unwrap() as JsonObject)["usedTokens"] as JsonPrimitive).intOrNull)
    }

    @Test
    fun `a null value inside a success is still a success`() {
        // The desktop answers `ok(null)` when there is nothing honest to report — no
        // conversation, no model loaded. That is not a failure, and the caller tells
        // the two apart by what it does next.
        assertEquals("null", parse("""{"ok":true,"value":null}""").unwrap().toString())
    }

    @Test
    fun `anything that is not an envelope is refused`() {
        // This arrives off the wire, so it is not guaranteed to be anything.
        assertNull(parse(""""a string"""").unwrap())
        assertNull(parse("""[1,2]""").unwrap())
        assertNull(parse("""{}""").unwrap())
        assertNull(parse("""null""").unwrap())
        assertNull(null.unwrap())
    }
}

/**
 * The same envelope, opened by a caller that cannot afford a silent refusal.
 *
 * [unwrap] answers null for a refusal, which suits a caller with a sensible
 * empty answer and ruins one without. Reading a conversation is the latter: the
 * mail client turned a null into an empty list of messages, and the reader drew
 * an empty list of messages as a screen with nothing on it. Two of five
 * conversations in a real inbox were unopenable for weeks, and neither the phone
 * nor the computer ever said a word about why.
 */
class UnwrapOrThrowTest {

    private fun parse(json: String) = Json.parseToJsonElement(json)

    @Test
    fun `a success gives up its value, exactly as unwrap does`() {
        val value = parse("""{"ok":true,"value":[{"id":"m1"}]}""").unwrapOrThrow("open that")

        assertEquals(parse("""[{"id":"m1"}]"""), value)
    }

    @Test
    fun `a refusal repeats what the computer said`() {
        // `err(code, message, detail)` is written for a reader. Inventing a
        // second sentence about the same failure loses the only one that knows
        // which mailbox, which provider, which account.
        try {
            parse("""{"ok":false,"error":{"code":"email.thread-failed","message":"Could not open that conversation."}}""")
                .unwrapOrThrow("open that conversation")
            fail("a refusal must not pass for an answer")
        } catch (failure: IllegalStateException) {
            assertEquals("Could not open that conversation.", failure.message)
        }
    }

    @Test
    fun `a refusal with no words still names the act`() {
        // The channel is not a sentence. "Your computer would not open that
        // conversation" is, and the caller supplies the verb because only it
        // knows what it was asking for.
        try {
            parse("""{"ok":false}""").unwrapOrThrow("open that conversation")
            fail("a refusal must not pass for an answer")
        } catch (failure: IllegalStateException) {
            assertEquals("Your computer would not open that conversation.", failure.message)
        }
    }

    @Test
    fun `a reply that is not an envelope is a fault, not an absence`() {
        // Off the wire, so it is not guaranteed to be anything. Every one of
        // these used to become "no messages in this conversation".
        for (raw in listOf(""""a string"""", """[1,2]""", """null""")) {
            try {
                parse(raw).unwrapOrThrow("open that conversation")
                fail("$raw must not pass for an answer")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("unreadable"))
            }
        }
    }

    @Test
    fun `an empty answer is still an answer`() {
        // The distinction the whole thing exists for. A folder with nothing in
        // it is a fact; a refusal is not, and they must not arrive as the same
        // empty list.
        assertEquals(parse("""[]"""), parse("""{"ok":true,"value":[]}""").unwrapOrThrow("list that"))
    }
}
