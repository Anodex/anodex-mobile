package dev.anodex.mobile.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
