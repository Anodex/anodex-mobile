package dev.anodex.mobile

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat

/**
 * Where Android's share menu lands, passing what was shared on to the app.
 *
 * Not `MainActivity` itself. A share starts its target inside the sharing app's task,
 * so sharing straight into the main screen made a second copy of the app — a second
 * view model, and a second connection to the computer beside the one already open.
 * This one draws nothing, hands the text and files to the single real instance, and
 * closes.
 *
 * The files travel as `ClipData` with a read grant, which is how a grant this activity
 * received is passed on to the one that will actually read them.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incoming = intent
        val text = sharedText(incoming)
        val uris = sharedUris(incoming)

        if (text != null || uris.isNotEmpty()) {
            val forward = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SHARED_TEXT, text)
                .putParcelableArrayListExtra(EXTRA_SHARED_URIS, ArrayList(uris))
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri(null, uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                forward.clipData = clip
                forward.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(forward)
        }
        finish()
    }

    private fun sharedText(intent: Intent): String? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
        // A page shared from a browser arrives as a bare link with the page's title as
        // the subject. The title is what says what the link is, so it comes along.
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            text == null -> subject
            subject == null || text.contains(subject) -> text
            else -> "$subject\n$text"
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    companion object {
        const val EXTRA_SHARED_TEXT = "dev.anodex.mobile.sharedText"
        const val EXTRA_SHARED_URIS = "dev.anodex.mobile.sharedUris"
    }
}
