package dev.anodex.mobile.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * A reply typed straight into an "answer ready" notification.
 *
 * Handed to the running app through [ReplyBridge], which sends it in that
 * conversation. When nothing can send it — the app was closed, or it is not
 * connected — the notification says so and keeps the words, rather than the reply
 * disappearing behind a spinner that never stops.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        val notifications = Notifications(context)
        val id = Notifications.replyNotificationId(conversationId)

        if (text.isEmpty()) {
            notifications.cancel(id)
            return
        }

        val sent = ReplyBridge.handler?.invoke(conversationId, text) == true
        if (sent) {
            // Taken down rather than updated: the answer to this reply posts its own
            // notification when it arrives, and a "sent" line in between is noise.
            notifications.cancel(id)
        } else {
            notifications.showReplyNotSent(conversationId, text)
        }
    }

    companion object {
        const val KEY_REPLY = "dev.anodex.mobile.reply"
        const val EXTRA_CONVERSATION_ID = "dev.anodex.mobile.replyConversationId"
    }
}

/**
 * The running app's way of sending a notification reply, for [ReplyReceiver].
 *
 * Returns whether the reply was taken. Set while the app is alive, cleared when it
 * goes, so a reply can tell a live app from a closed one.
 */
object ReplyBridge {
    @Volatile
    var handler: ((conversationId: String, text: String) -> Boolean)? = null
}
