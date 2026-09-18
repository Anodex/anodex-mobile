package dev.anodex.mobile.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
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

    AndroidView(
        modifier = modifier.fillMaxWidth(),
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
            word-break: break-word;
          }
          img, table, pre { max-width: 100% !important; height: auto; }
          table { width: auto !important; }
          a { color: #4F8CFF; }
          /* An image the reader has not asked for takes no space and draws no
             broken-image glyph, which otherwise litters a newsletter with grey
             squares that look like a failure rather than a choice. */
          img[data-remote-src] { display: none; }
        </style>
        </head><body>$body</body></html>
    """.trimIndent()
}

/** How many remote images this message is holding back. */
internal fun remoteImageCount(html: String): Int =
    Regex("data-remote-src=\"").findAll(html).count()

/** Every remote URL the message wants, in the order it wants them. */
internal fun remoteImageUrls(html: String): List<String> =
    Regex("data-remote-src=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()

private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
