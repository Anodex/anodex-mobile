package dev.anodex.mobile.profile

import dev.anodex.mobile.transport.unwrap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which of the two profile reads is wrapped, and which is not.
 *
 * The Profile screen makes two calls and they answer in different shapes.
 * `stats:get-usage-profile` returns `ok(value)`. `settings:get-profile` returns the
 * profile itself, the way `models:get-state` does.
 *
 * Unwrapping the unwrapped one is what shipped. `unwrap` finds no `ok: true`, hands
 * back null, and the screen renders an em dash where a name belongs — with no error,
 * because from the app's point of view nothing failed. The context ring had this same
 * bug in the other direction, which is why `unwrap` is applied per channel and its own
 * documentation says so.
 *
 * `protocol/anodex-protocol.json` records the shape each channel uses, and is the
 * place to check rather than matching whatever the neighbouring call does.
 */
class ProfileEnvelopeTest {

    private val rawProfile: JsonObject = buildJsonObject {
        put("displayName", "Merlin")
        put("planTier", "dev")
        put("accountStatus", "active")
    }

    @Test
    fun `unwrapping a raw answer yields nothing`() {
        // The defect, stated directly. Not an error condition — a correct function
        // given the wrong input, which is exactly why it failed silently.
        assertNull(rawProfile.unwrap())
    }

    @Test
    fun `the raw answer read directly still has the name in it`() {
        assertEquals("\"Merlin\"", rawProfile["displayName"]?.toString())
    }

    @Test
    fun `unwrapping a wrapped answer yields its value`() {
        // The other read on the same screen. Leaving `unwrap` off this one would fail
        // the same way, in the opposite direction.
        val wrapped = buildJsonObject {
            put("ok", true)
            put("value", buildJsonObject { put("lifetimeTokens", 12_900_000L) })
        }

        val value = wrapped.unwrap() as? JsonObject

        assertEquals("12900000", value?.get("lifetimeTokens")?.toString())
    }

    @Test
    fun `a failed envelope unwraps to nothing rather than to its error`() {
        val failed = buildJsonObject {
            put("ok", false)
            put("error", "nope")
        }

        assertNull(failed.unwrap())
    }
}
