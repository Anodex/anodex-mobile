package dev.anodex.mobile.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Where a plan's Approve or Reject goes when it is pressed on the notification itself.
 *
 * The connection to the computer belongs to the running app, so the answer is handed
 * to it through [RunActionBridge]. When nothing is listening — the app was closed for
 * good, and the connection with it — the notification says to open Anodex rather
 * than swallowing the press. Opening the app from here is not an option: Android no
 * longer lets a notification button start an activity through a receiver.
 */
class RunActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val notifications = Notifications(context)

        val handler = RunActionBridge.handler
        if (handler == null) {
            notifications.showOpenToAnswer(intent.getStringExtra(EXTRA_CONVERSATION_ID))
            return
        }
        handler(runId, approve)
        notifications.cancel(Notifications.ID_APPROVAL)
    }

    companion object {
        const val EXTRA_RUN_ID = "dev.anodex.mobile.runId"
        const val EXTRA_APPROVE = "dev.anodex.mobile.approve"
        const val EXTRA_CONVERSATION_ID = "dev.anodex.mobile.actionConversationId"
    }
}

/**
 * The running app's way of answering a plan, for [RunActionReceiver].
 *
 * Set while the app holds a connection and cleared when it lets go, so a press can
 * tell a live app from a closed one.
 */
object RunActionBridge {
    @Volatile
    var handler: ((runId: String, approve: Boolean) -> Unit)? = null
}
