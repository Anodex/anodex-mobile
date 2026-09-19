package dev.anodex.mobile.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.anodex.mobile.ui.theme.AnodexTheme
import java.io.ByteArrayInputStream

/**
 * A message drawn as the sender wrote it.
 *
 * The phone used to show the plain-text part and nothing else, on the reasoning
 * that HTML mail is "a whole security surface -- remote images, tracking pixels,
 * layout that fights the app". Every part of that was already handled a layer
 * down: `main/email/htmlBody.ts` on the desktop strips scripts, inlines `cid:`
 * attachments as data URIs, and moves every remote URL onto `data-remote-src` so
 * nothing is fetched until somebody asks. What arrives here is sanitized output,
 * not a sender's markup.
 *
 * What refusing it actually cost was legibility. A newsletter whose plain-text
 * part is two screens of tracking links -- which is common, because senders do not
 * expect anyone to read it -- arrived as two screens of tracking links.
 *
 * Three things this view does not do, all enforced rather than assumed:
 *
 * - **No JavaScript.** Off by default in a `WebView` and never turned on. The
 *   desktop already removed the scripts; this is the second lock on the same door.
 * - **No network, ever.** Every request is refused by
 *   [WebViewClient.shouldInterceptRequest] rather than by trusting that the
 *   sanitizer left nothing behind. A tracking pixel that survived every other step
 *   still does not load, and the refusal is a rule here rather than an absence of
 *   URLs.
 * - **No navigation.** A tapped link is handed back to the caller, which is how a
 *   link gets looked at before it is followed rather than after.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MailBody(
    html: String,
    modifier: Modifier = Modifier,
    /** Remote images the reader asked for, by their original URL. */
    images: Map<String, String> = emptyMap(),
    onLink: ((String) -> Unit)? = null,
    /**
     * Fill the space given instead of growing to fit the message.
     *
     * The difference decides who scrolls. A `WebView` only ever paints its own
     * viewport: sized to its content it has no scroll of its own and paints all
     * of it, which is what a short message wants -- but sized to *less* than its
     * content it paints the top and leaves the remainder of the view blank,
     * which is what a long one was doing. Screens of nothing, and no amount of
     * capping fixes it, because the cap is what creates it.
     *
     * So a message that is the only thing on the screen is given the screen and
     * scrolls itself. Measuring is for the other case: several messages in one
     * conversation, where each has to sit inline in a column that scrolls.
     */
    fill: Boolean = false,
) {
    val colors = AnodexTheme.colors
    val document = wrapMailHtml(html, images, colors.text.toArgb(), colors.bgApp.toArgb())

    // Measured, because the reader this sits in scrolls.
    //
    // A `WebView` inside a vertical scroll is handed an unbounded height, which it
    // does not cope with: it settles on nothing, or on one screen of a message that
    // is ten. Either way the reader sees a blank where the mail should be, which is
    // worse than the plain text this replaced.
    //
    // `contentHeight` is read on page-finished rather than asked for in JavaScript,
    // which stays off. It is in CSS pixels, so it is density-scaled here. Until it
    // arrives the view keeps a screenful so the layout does not jump from nothing
    // to full height in one frame.
    // Deliberately **not** keyed on the document.
    //
    // It was, and that is what cut the message in half. `AndroidView`'s factory
    // runs once, so the `WebViewClient` created in it closes over whichever state
    // object existed then. Re-keying on the document builds a *new* state on the
    // next load -- which is exactly what happens when the pictures arrive -- so
    // from that moment the view was measuring itself correctly and writing the
    // answer into an object nothing was reading, while the layout read a fresh
    // zero and settled on the fallback. Measured 2328dp, drawn 240dp, and the
    // rest of the mail below the cut.
    //
    // One state, stable for the life of the composable, reset in `update` where
    // the document actually changes.
    val measured = remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    /** What the view already holds, so it is not reloaded for nothing. */
    val loaded = remember { LoadedDocument() }

    AndroidView(
        modifier = if (fill) {
            modifier.fillMaxSize()
        } else {
            modifier.fillMaxWidth().height(
                if (measured.value > 0.dp) measured.value else FALLBACK_HEIGHT
            )
        },
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.loadsImagesAutomatically = true
                // Data URIs only: the images that are actually here. Everything else
                // is refused below, so this permits inline attachments and nothing
                // that would leave the device.
                settings.blockNetworkLoads = true
                settings.setSupportZoom(false)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                // Left on, unlike before. While the view was as tall as its
                // content there was never anything to scroll; now that a very
                // long message is capped, its own scrollbar is how the rest of
                // it is reached.
                // On when the view is the scrolling region, off when it is
                // sized to its content and has nothing of its own to scroll.
                isVerticalScrollBarEnabled = fill

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Re-read, several times, and keep the tallest.
                        //
                        // `onPageFinished` is not "laid out": it fires when loading
                        // ends, and `contentHeight` at that moment is whatever has
                        // been measured so far. Reading it once produced a message
                        // cut off mid-way -- the heading, the paragraph and the
                        // table, and nothing after them -- which is the failure this
                        // measurement exists to prevent, arrived at from the other
                        // side.
                        //
                        // Taken as a maximum rather than a last value, because a
                        // reflow can report a smaller intermediate height and a
                        // message that shrinks after you start reading is worse than
                        // one that is briefly too tall.
                        if (view == null || fill) return

                        // Read until the height stops growing, rather than for a
                        // fixed second.
                        //
                        // The fixed second was wrong, and wrong in the direction
                        // that loses mail: a newsletter whose pictures arrive as
                        // data URIs is still decoding and laying them out well
                        // after the last scheduled read, so the view kept the
                        // height the text alone needed and the rest of the message
                        // was cut off -- a photograph sliced in half with the Reply
                        // button under it. Nothing said so; the page scrolled to
                        // its end and simply ended early.
                        //
                        // Polling `contentHeight` is a field read, not JavaScript,
                        // which stays off. It stops as soon as the number has held
                        // still, so a short message costs a handful of reads.
                        view.post(object : Runnable {
                            private var previous = 0
                            private var held = 0
                            private val startedAt = SystemClock.uptimeMillis()

                            override fun run() {
                                // The view may be gone by the time this runs: a
                                // scroll away from the message destroys it while
                                // callbacks are still pending, and reading
                                // `contentHeight` off a destroyed `WebView` is not
                                // something to find out about from a crash report.
                                if (!view.isAttachedToWindow) return

                                val content = view.contentHeight
                                if (content <= 0) {
                                    schedule()
                                    return
                                }

                                // The value that stops changing, not the largest
                                // one seen.
                                //
                                // Keeping the largest was the obvious rule and it
                                // was wrong in a way that only shows on a heavy
                                // message: while pictures are decoding, the
                                // document is briefly laid out at their natural
                                // size, before `max-width: 100%` has anything to
                                // constrain. That intermediate height is the tallest
                                // reading by a long way, so the maximum locked it in
                                // and the reader scrolled through a screen and a
                                // half of nothing after the last line.
                                //
                                // Settling handles both failures with one rule: it
                                // does not stop early, because it waits for the
                                // number to hold still, and it does not keep a
                                // transient peak, because it takes whatever is true
                                // when the movement ends.
                                // Clamped, because this number is not ours.
                                //
                                // `contentHeight` is whatever the renderer thinks
                                // mid-reflow, and it went straight into a layout
                                // constraint: on a picture-heavy newsletter it
                                // briefly said 89,010, and the app went down with
                                // `Can't represent a width of 0 and height of
                                // 267030 in Constraints`.
                                //
                                // Only reached when this message is one of
                                // several -- a message on its own fills the screen
                                // and is never measured -- so the ceiling is about
                                // surviving a bad reading rather than about how
                                // long a reply can be.
                                val raw = with(density) { (content * view.scale).toDp() }
                                measured.value = raw.coerceAtMost(MAX_INLINE_HEIGHT)

                                // Settling is about the *reading*, not about the
                                // height that came out of it. Tying it to the
                                // capped value meant a message over the cap never
                                // settled -- the reading kept changing while the
                                // height did not, so the loop ran to its fifteen
                                // second limit on every long newsletter.
                                if (content != previous) {
                                    previous = content
                                    held = 0
                                } else {
                                    held++
                                }

                                schedule()
                            }

                            private fun schedule() {
                                val elapsed = SystemClock.uptimeMillis() - startedAt
                                if (held < SETTLED_READS && elapsed < GIVE_UP_MEASURING_MS) {
                                    view.postDelayed(this, MEASURE_EVERY_MS)
                                }
                            }
                        })
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        // Inline data is already in the document and costs nothing to
                        // fetch. Everything else -- including anything the sanitizer
                        // missed -- is answered with nothing rather than reaching the
                        // network, which is what stops a tracking pixel telling a
                        // sender the message was opened.
                        if (url.startsWith("data:")) return null
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        onLink?.invoke(url)
                        // Always true: this view never navigates. A message that
                        // replaced itself with a page would be a phishing surface
                        // wearing the frame of something already trusted.
                        return true
                    }
                }
            }
        },
        update = { view ->
            // Only when the document actually changed.
            //
            // `update` runs on every recomposition, and measuring the height causes
            // one -- so loading unconditionally meant each measurement reloaded the
            // page it had just measured, which restarted the measurement. The
            // message arrived in the end by always keeping the taller number, and
            // spent the whole way reloading itself.
            if (loaded.document != document) {
                loaded.document = document
                // A different document has a different height, and the old one is
                // no longer an answer to anything. Reset here rather than by
                // re-keying the state, so the view keeps writing to the object the
                // layout reads.
                measured.value = 0.dp
                // `null` as the base URL, so the document has no origin to inherit
                // and nothing relative to resolve against.
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            // A `WebView` outlives the composable that made it unless it is told
            // not to. It holds a reference to its context, a render process, and
            // in this case up to four pending re-measure callbacks -- so a thread
            // scrolled through leaves one behind per message, and a mailbox read
            // for ten minutes leaves a pile.
            //
            // Emptied before destroying: `destroy()` on a view still displaying a
            // document is documented as undefined behaviour, and loading a blank
            // page first is the sanctioned way to stop it.
            view.stopLoading()
            view.loadUrl("about:blank")
            view.removeAllViews()
            view.destroy()
        },
    )
}

/**
 * The sender's HTML inside a page that belongs to this app.
 *
 * Two jobs. The first is fitting: mail is written for a desktop window, so without
 * `max-width: 100%` on everything a 700px table makes the whole message scroll
 * sideways. The second is the theme -- a dark app showing a message with no
 * background of its own should not render black text on black.
 *
 * Remote images stay parked on `data-remote-src` until [images] carries them. The
 * substitution is by exact URL rather than by pattern, so a request the reader did
 * not approve cannot be smuggled in by a near-match.
 */
internal fun wrapMailHtml(
    html: String,
    images: Map<String, String>,
    textArgb: Int,
    backgroundArgb: Int,
): String {
    var body = html
    for ((url, data) in images) {
        body = body.replace("data-remote-src=\"$url\"", "src=\"$data\"")
    }

    return """
        <!DOCTYPE html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body {
            margin: 0; padding: 0;
            background: ${hex(backgroundArgb)};
            color: ${hex(textArgb)};
            font-family: -apple-system, sans-serif;
            font-size: 15px; line-height: 1.5;
            /* `overflow-wrap`, not `word-break`. The second one splits *inside*
               words whenever a line is tight, which in a narrow newsletter column
               turned a masthead reading "Daily" into five stacked letters. This
               one only breaks a word that could not fit on a line of its own,
               which is the case it was added for: a long unbroken URL. */
            overflow-wrap: break-word;
          }
          /* Held to the screen. `max-width` alone is not enough: it cannot shrink
             a table below the width its own contents demand, so a 600px
             newsletter kept its width and ran off the right edge with half of
             every headline missing. `width: auto` is what actually makes it
             reflow, and it goes back.
             
             It was removed once, on the theory that it caused a masthead to stack
             one letter per line. It did not -- `word-break` did, by splitting
             inside words the moment a column got tight. That is the line that
             needed narrowing, and `overflow-wrap` above is the narrower version.
             Both were changed at once and the wrong one got the blame; the
             overflow that came back was worse than the bug being fixed. */
          img, table, pre { max-width: 100% !important; }
          table { width: auto !important; }
          img { height: auto; }
          a { color: #4F8CFF; }
          /* An image the reader has not asked for takes no space and draws no
             broken-image glyph, which otherwise litters a newsletter with grey
             squares that look like a failure rather than a choice. */
          img[data-remote-src] { display: none; }
        </style>
        </head><body>$body</body></html>
    """.trimIndent()
}

/** Which document a view already holds. Not state: writing it must not recompose. */
private class LoadedDocument {
    var document: String? = null
}

/** How often to re-read the height while it is still moving. */
private const val MEASURE_EVERY_MS = 150L

/**
 * Reads at the same height before the message is called measured.
 *
 * Five, so roughly three quarters of a second of stillness. Fewer, and a pause
 * between two pictures decoding reads as the end of the document. The height
 * follows every reading in the meantime, so this decides when to stop watching
 * rather than what to believe.
 */
private const val SETTLED_READS = 5

/**
 * Long enough for a heavy newsletter's pictures, short enough to end.
 *
 * A document still growing after fifteen seconds has a height nothing can pin
 * down, and one that keeps changing under the reader is its own problem.
 */
private const val GIVE_UP_MEASURING_MS = 15_000L

/**
 * A ceiling on a number that comes from a web page.
 *
 * `contentHeight` is the renderer's opinion mid-reflow and it can be absurd --
 * 89,010 once, which became 267,030 pixels in a layout constraint and crashed
 * the app. Two thousand is generous for a reply stacked in a conversation,
 * which is the only thing measured now.
 */
private val MAX_INLINE_HEIGHT = 2_000.dp

/** Enough to look like a message while the real height is being worked out. */
private val FALLBACK_HEIGHT = 240.dp

/** How many remote images this message is holding back. */
internal fun remoteImageCount(html: String): Int =
    Regex("data-remote-src=\"").findAll(html).count()

/** Every remote URL the message wants, in the order it wants them. */
internal fun remoteImageUrls(html: String): List<String> =
    Regex("data-remote-src=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()

private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
