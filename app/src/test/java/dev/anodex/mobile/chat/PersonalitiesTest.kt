package dev.anodex.mobile.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone does with what the computer sends back.
 *
 * The interesting cases are all absences. `active` is legitimately null — that is the
 * free-text style rather than a named personality — so it cannot be treated as a
 * missing field, and a JSON null read carelessly comes back as the string "null",
 * which would then match no personality and quietly select nothing.
 */
class PersonalitiesTest {

    private fun parse(json: String) = parsePersonalityState(Json.parseToJsonElement(json))

    @Test
    fun `reads the personalities and which one is on`() {
        val state = parse(
            """
            {
              "active": "p2",
              "personalities": [
                { "id": "p1", "name": "Vale", "role": "Direct.", "tint": "accent" },
                { "id": "p2", "name": "Wren", "role": "Warm.", "tint": "series-2" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("p2", state.active)
        assertEquals(listOf("Vale", "Wren"), state.personalities.map { it.name })
        assertEquals("series-2", state.personalities[1].tint)
    }

    @Test
    fun `a null active is the free-text style, not the word null`() {
        val state = parse("""{ "active": null, "personalities": [] }""")

        assertNull(state.active)
    }

    @Test
    fun `a personality the user wrote themselves need not have a one-liner or a tint`() {
        val state = parse(
            """{ "active": null, "personalities": [{ "id": "mine", "name": "Mine" }] }""",
        )

        val personality = state.personalities.single()
        assertEquals("", personality.role)
        // The same default the desktop applies, so an untinted personality still
        // draws a dot rather than an invisible one.
        assertEquals("accent", personality.tint)
    }

    @Test
    fun `drops a personality with no id, because it could never be selected`() {
        val state = parse(
            """
            {
              "active": null,
              "personalities": [
                { "name": "No id" },
                { "id": "ok", "name": "Fine" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("ok"), state.personalities.map { it.id })
    }

    @Test
    fun `an unusable answer is an empty list, not a crash`() {
        // A desktop too old to know the channel answers with null, and the phone has
        // to survive that: Settings then says it is waiting rather than falling over.
        assertTrue(parsePersonalityState(null).personalities.isEmpty())
        assertTrue(parsePersonalityState(JsonNull).personalities.isEmpty())
        assertNull(parsePersonalityState(null).active)
    }
}
