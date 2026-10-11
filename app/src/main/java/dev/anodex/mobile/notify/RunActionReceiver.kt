package dev.anodex.mobile.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.anodex.mobile.connection.BackgroundLink

/**
 * Where a plan's Approve or Reject goes when it is pressed on the notification itself.
 *
 * The running app takes it through [RunActionBridge]. With the app closed, the
 * background connection answers it ([BackgroundLink.answerPlan]); only when there is no
 * connection at all does the notification say to open Anodex, rather than swallowing
 * the press. Opening the app from here is not an option: Android no
 * longer lets a notification button start an activity through a receiver.
 */
class RunActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: return
        val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
        val notifications = Notifications(context)

        val handler = RunActionBridge.handler
        if (handler != null) {
            handler(runId, approve)
            notifications.cancel(Notifications.ID_APPROVAL)
            return
        }
        // The app is closed: answered over the background connection, when there is one.
        if (BackgroundLink.answerPlan(runId, approve)) {
            notifications.cancel(Notifications.ID_APPROVAL)
            return
        }
        notifications.showOpenToAnswer(intent.getStringExtra(EXTRA_CONVERSATION_ID))
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
