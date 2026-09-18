package dev.anodex.mobile.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var measured by remember(document) { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(if (measured > 0.dp) measured else FALLBACK_HEIGHT),
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
                        if (view == null) return
                        for (delay in REMEASURE_DELAYS_MS) {
                            view.postDelayed({
                                // The view may be gone by the time this runs: a
                                // scroll away from the message destroys it while
                                // up to a second of callbacks are still pending,
                                // and reading `contentHeight` off a destroyed
                                // `WebView` is not something to find out about
                                // from a crash report.
                                if (!view.isAttachedToWindow) return@postDelayed
                                val content = view.contentHeight
                                if (content > 0) {
                                    val height = with(density) { (content * view.scale).toDp() }
                                    if (height > measured) measured = height
                                }
                            }, delay)
                        }
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
            // `null` as the base URL, so the document has no origin to inherit and
            // nothing relative to resolve against.
            view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
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

/**
 * When to re-read the height after loading ends.
 *
 * Spread rather than repeated on a timer: a short message settles immediately and
 * a long one with a wide table needs a reflow or two. The last of these is late
 * enough to catch that and early enough that nobody is still looking at a gap.
 */
private val REMEASURE_DELAYS_MS = longArrayOf(0, 120, 400, 1000)

/** Enough to look like a message while the real height is being worked out. */
private val FALLBACK_HEIGHT = 240.dp

/** How many remote images this message is holding back. */
internal fun remoteImageCount(html: String): Int =
    Regex("data-remote-src=\"").findAll(html).count()

/** Every remote URL the message wants, in the order it wants them. */
internal fun remoteImageUrls(html: String): List<String> =
    Regex("data-remote-src=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()

private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
