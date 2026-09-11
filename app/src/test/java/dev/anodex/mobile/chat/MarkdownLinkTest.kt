package dev.anodex.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a reply points, and where it must not.
 *
 * Links were the one piece of Markdown the phone dropped, which was the wrong one to
 * drop: the desktop renders them, and the thing a coding agent most often hands over
 * is an address — a pull request, a doc page, a failing run. On a phone that arrived
 * as a wall of raw characters that could not be followed and could barely be read.
 *
 * The other half of these tests is about restraint. A tap on a link hands a string
 * the model wrote to the system to open, which makes this the one place in the app
 * where generated text becomes an action. Only http, https and mailto get that, and
 * the tests below are the record of what is deliberately refused.
 */
class MarkdownLinkTest {

    private fun spans(source: String) = parseInline(source)

    private fun links(source: String) = spans(source).mapNotNull { it.link }

    // --- what becomes a link ------------------------------------------------------

    @Test
    fun `a bracketed link carries its label and its destination separately`() {
        val span = spans("see [the PR](https://github.com/Anodex/anodex-mobile/pull/94)").last()

        assertEquals("the PR", span.text)
        assertEquals("https://github.com/Anodex/anodex-mobile/pull/94", span.link)
    }

    @Test
    fun `a bare url becomes a link without being asked`() {
        // Agents write far more bare URLs than bracketed ones. Linking only the
        // bracketed half would be linking the rarer half.
        val span = spans("logs at https://ci.example.com/run/7").last()

        assertEquals("https://ci.example.com/run/7", span.text)
        assertEquals("https://ci.example.com/run/7", span.link)
    }

    @Test
    fun `emphasis inside a label survives and the whole thing still points somewhere`() {
        // `[**the failing test**](url)` is one link with emphasis inside it, not a
        // bracket followed by bold text followed by an address.
        val spans = spans("[**the failing test**](https://example.com/x)")

        assertEquals(1, spans.size)
        assertEquals("the failing test", spans[0].text)
        assertTrue(spans[0].bold)
        assertEquals("https://example.com/x", spans[0].link)
    }

    @Test
    fun `a code label keeps being code and gains a destination`() {
        // The shape an agent uses to point at a file: [`ChatScreen.kt`](url).
        val span = spans("open [`ChatScreen.kt`](https://example.com/f) next").first { it.link != null }

        assertEquals("ChatScreen.kt", span.text)
        assertTrue(span.code)
        assertEquals("https://example.com/f", span.link)
    }

    @Test
    fun `a mailto is a destination too`() {
        assertEquals(listOf("mailto:someone@example.com"), links("[write](mailto:someone@example.com)"))
    }

    @Test
    fun `parentheses inside a url are kept`() {
        // Real addresses contain them — a disambiguated wiki article, a doc anchor
        // for a generic — and stopping at the first `)` truncates them silently.
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Mercury_(planet)"),
            links("[Mercury](https://en.wikipedia.org/wiki/Mercury_(planet))"),
        )
    }

    // --- what stays as text -------------------------------------------------------

    @Test
    fun `a url in backticks stays code and is not followable`() {
        // Somebody who fenced an address wanted it read, not followed — and this is
        // also what keeps a curl command from becoming three links.
        val span = spans("run `curl https://example.com`").last()

        assertTrue(span.code)
        assertNull(span.link)
    }

    @Test
    fun `a scheme the phone should not open is not a link and is not hidden`() {
        // The whole reason the parser decides this rather than the renderer. A tap
        // is handed to the system to open; `intent:` and friends are a way to have
        // the phone do something on the strength of generated text.
        //
        // Refused, but not swallowed: it stays as the characters the model typed, so
        // the reader can see there was a destination and see what it was.
        for (hostile in listOf(
            "[tap me](javascript:alert(1))",
            "[tap me](intent://scan/#Intent;scheme=zxing;end)",
            "[tap me](file:///sdcard/x)",
            "[tap me](content://settings/secure)",
        )) {
            val spans = spans(hostile)
            assertTrue("followed <$hostile>", spans.all { it.link == null })

            val rebuilt = spans.joinToString("") { it.text }
            assertTrue("hid the destination of <$hostile>", rebuilt.contains("tap me"))
            for (word in listOf("javascript", "intent", "file", "content")) {
                if (hostile.contains(word)) {
                    assertTrue("lost \"$word\" from <$hostile>", rebuilt.contains(word))
                }
            }
        }
    }

    @Test
    fun `an ordinary bracket is left alone`() {
        for (plain in listOf(
            "an array [0] of things",
            "see [1] below",
            "a [bracket] with no target",
            "[label]()",
            "[unclosed](",
        )) {
            assertEquals("linkified <$plain>", emptyList<String>(), links(plain))
        }
    }

    @Test
    fun `the text of a reply is never lost to link parsing`() {
        // The rule the whole parser is built on: an unsupported construct reads as
        // what the model typed rather than disappearing.
        for (source in listOf(
            "a [bracket] with no target",
            "[tap me](javascript:alert(1))",
            "[unclosed](",
            "[](https://example.com)",
            "[label]()",
            "a plain [0] index",
        )) {
            val rebuilt = spans(source).joinToString("") { it.text }
            for (word in Regex("[A-Za-z]{3,}").findAll(source).map { it.value }) {
                assertTrue("lost \"$word\" from <$source>", rebuilt.contains(word))
            }
        }
    }

    // --- where a url ends ---------------------------------------------------------

    @Test
    fun `a full stop at the end of a sentence is not part of the address`() {
        val spans = spans("It is documented at https://example.com/docs.")

        assertEquals("https://example.com/docs", spans.first { it.link != null }.link)
        assertTrue("the full stop was swallowed", spans.last().text.endsWith("."))
    }

    @Test
    fun `a closing bracket that never opened inside the url is not part of it`() {
        assertEquals(
            listOf("https://example.com/a"),
            links("(see https://example.com/a) for more"),
        )
    }

    @Test
    fun `a url ending in a real parenthesis keeps it`() {
        assertEquals(
            listOf("https://example.com/x(y)"),
            links("at https://example.com/x(y) there"),
        )
    }

    @Test
    fun `a scheme with nothing after it is not an address`() {
        assertEquals(emptyList<String>(), links("the https:// scheme"))
        assertEquals(emptyList<String>(), links("http://"))
    }

    @Test
    fun `several links in one line each keep their own destination`() {
        assertEquals(
            listOf("https://a.example.com", "https://b.example.com"),
            links("compare [A](https://a.example.com) with [B](https://b.example.com)"),
        )
    }

    // --- links inside the rest of the document ------------------------------------

    @Test
    fun `a link in a list item and in a heading is a link there too`() {
        val doc = parseMarkdown(
            """
            ## See [the run](https://ci.example.com/1)

            - [the diff](https://example.com/diff)
            - nothing here
            """.trimIndent()
        )

        val heading = doc[0] as MarkdownBlock.Heading
        assertEquals("https://ci.example.com/1", heading.spans.first { it.link != null }.link)

        val list = doc[1] as MarkdownBlock.ListBlock
        assertEquals("https://example.com/diff", list.items[0].first { it.link != null }.link)
        assertTrue(list.items[1].none { it.link != null })
    }

    @Test
    fun `a link inside a fenced block is left as code`() {
        // The block is copied and run, not tapped. Linkifying inside one would also
        // mean the text of a command differed from what it looked like.
        val block = parseMarkdown("```\ncurl https://example.com\n```").single()

        assertTrue(block is MarkdownBlock.CodeBlock)
        assertEquals("curl https://example.com", (block as MarkdownBlock.CodeBlock).text)
    }

    @Test
    fun `a half-typed link while streaming does not flicker into something else`() {
        // Every prefix of a link arrives on its own frame. None of them may throw,
        // and none may claim a destination that has not finished arriving.
        val whole = "see [the PR](https://example.com/pull/94) now"
        for (length in 1..whole.length) {
            val prefix = whole.take(length)
            val spans = parseInline(prefix)
            for (span in spans) {
                val target = span.link ?: continue
                assertTrue(
                    "claimed <$target> from the prefix <$prefix>",
                    whole.contains(target),
                )
            }
        }
    }
}
