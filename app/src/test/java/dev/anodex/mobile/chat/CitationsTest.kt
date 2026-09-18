package dev.anodex.mobile.chat

import dev.anodex.mobile.ui.screens.linkCitations
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Citations, and the ones that must not become links.
 *
 * Found on 2026-09-17 in the owner's own transcripts: 915 assistant messages,
 * three carrying `[S..]` markers, and not one of those three carrying a source.
 * The computer had sent the sources with every turn; this phone never read the
 * field, never saved it, and the desktop then drew those conversations with dead
 * numbers too, because it renders what was written.
 *
 * The real message that exposed it is the first case below, kept verbatim.
 */
class CitationsTest {

    private val sources = listOf(
        WebSource(id = "S1", title = "Avalanche coach", url = "https://nhl.com/avs", verified = true),
        WebSource(id = "S6", title = "2025-26 season", url = "https://example.com/season", verified = true),
        WebSource(id = "S7", title = "Sakic presser", url = "https://example.com/sakic", verified = false),
    )

    @Test
    fun `a marker becomes a numbered link to its page`() {
        val text = "Bednar and his staff will return [S1][S7]."
        assertEquals(
            "Bednar and his staff will return [1](https://nhl.com/avs)[3](https://example.com/sakic).",
            linkCitations(text, sources),
        )
    }

    @Test
    fun `the number follows arrival order, not the id`() {
        // `[S6]` is the second source the model met, so it reads as 2 -- the same
        // number the list under the reply gives it. Rendering it as 6 would point
        // at a row that is not there.
        assertEquals("[2](https://example.com/season)", linkCitations("[S6]", sources))
    }

    @Test
    fun `a marker with no source is left alone`() {
        // The failure this has to survive. A model inventing a citation is ordinary;
        // drawing it as a link to some other page would be a citation pointing at
        // the wrong thing, which is worse than one that does not work.
        assertEquals("nothing backs this [S9]", linkCitations("nothing backs this [S9]", sources))
    }

    @Test
    fun `prose that merely contains brackets is untouched`() {
        for (text in listOf("see [Section 2]", "the array [S] is empty", "[S0] is not an id")) {
            assertEquals(text, linkCitations(text, sources))
        }
    }

    @Test
    fun `a reply with no sources is returned unchanged`() {
        // Not merely an optimisation. Before the sources were carried at all, every
        // reply was this case, and rewriting markers with nothing to point them at
        // is what would have produced the broken links in the first place.
        assertEquals("cites [S1] anyway", linkCitations("cites [S1] anyway", emptyList()))
    }

    @Test
    fun `a closing parenthesis in a url does not end the link early`() {
        // Wikipedia disambiguation urls do this constantly, and markdown ends the
        // destination at the first unescaped `)`.
        val wiki = listOf(
            WebSource(id = "S1", title = "Mercury", url = "https://en.wikipedia.org/wiki/Mercury_(planet)"),
        )
        assertEquals(
            "[1](https://en.wikipedia.org/wiki/Mercury_(planet%29)",
            linkCitations("[S1]", wiki),
        )
    }
}

/**
 * The merge that runs when the computer's transcript arrives over what this phone
 * already has.
 *
 * Its whole job is keeping what only the phone holds — the tool rows, the changed
 * files, the thinking. Sources belong on that list for a reason the others do not
 * have: a conversation saved before sources were ever recorded comes back from the
 * computer without them, so taking its empty list on faith blanks the sources of a
 * reply somebody is looking straight at.
 */
class SyncKeepsSourcesTest {

    private val source = WebSource("S1", "Avalanche coach", "https://nhl.com/avs", verified = true)

    private fun message(text: String, sources: List<WebSource>) = ChatMessage(
        id = "m1",
        role = ChatMessage.Role.ASSISTANT,
        text = text,
        webSources = sources,
        webSearchAttempted = sources.isNotEmpty(),
    )

    @Test
    fun `an older transcript does not blank what the phone has`() {
        val merged = keepWhatOnlyThisPhoneHas(
            had = message("the reply, as this phone has it", listOf(source)),
            computer = message("the reply, edited at the computer", emptyList()),
        )
        assertEquals(listOf(source), merged.webSources)
        assertEquals("the reply, edited at the computer", merged.text)
    }

    @Test
    fun `the computer's own list wins when it has one`() {
        val newer = WebSource("S1", "Updated", "https://example.com/newer", verified = true)
        val merged = keepWhatOnlyThisPhoneHas(
            had = message("old text", listOf(source)),
            computer = message("new text", listOf(newer)),
        )
        assertEquals(listOf(newer), merged.webSources)
    }

    @Test
    fun `an unchanged reply keeps the phone's copy whole`() {
        val merged = keepWhatOnlyThisPhoneHas(
            had = message("same", listOf(source)),
            computer = message("same", emptyList()),
        )
        assertEquals(listOf(source), merged.webSources)
    }
}
