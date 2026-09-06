package dev.anodex.mobile.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.anodex.mobile.R

/** Why the phone is being told something. Mirrors the desktop's RemoteNotificationKind. */
enum class NotificationKind {
    NEEDS_APPROVAL,
    FINISHED,
    FAILED;

    companion object {
        fun parse(value: String?): NotificationKind = when (value) {
            "needs-approval" -> NEEDS_APPROVAL
            "failed" -> FAILED
            else -> FINISHED
        }
    }
}

/**
 * Showing the user what the computer is doing while they are not looking at it.
 *
 * **Split into channels by urgency, not by feature.** A run blocked on approval is
 * time-sensitive: it is *stopped* until somebody answers, and it should be able to
 * interrupt. A run finishing is not, and should not. One channel for both leaves
 * the user with two bad options — silence everything, or be woken at 2am because
 * a scheduled task completed (handoff §6.2).
 *
 * Splitting them means the choice is theirs, per kind, in the system settings they
 * already know how to use.
 */
class Notifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val approval = NotificationChannel(
            CHANNEL_APPROVAL,
            "Waiting for you",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "A run is stopped until you approve or deny something."
        }

        val activity = NotificationChannel(
            CHANNEL_ACTIVITY,
            "Finished work",
            // Deliberately below the heads-up threshold. This is news, not a summons.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Runs, scheduled tasks and long replies that have finished."
        }

        manager.createNotificationChannels(listOf(approval, activity))
    }

    /**
     * @return false when the notification could not be shown, which on Android 13+
     *   most often means the user has not granted the permission. The caller should
     *   treat that as information rather than an error: notifications are a
     *   convenience, and the app works without them.
     */
    fun show(id: Int, kind: NotificationKind, title: String, body: String): Boolean {
        if (!canNotify()) return false

        val notification = NotificationCompat.Builder(context, channelFor(kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            // The body can be a sentence or two; a collapsed notification truncates
            // it to one line, and the interesting half is usually at the end.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priorityFor(kind))
            .setCategory(
                if (kind == NotificationKind.NEEDS_APPROVAL) {
                    NotificationCompat.CATEGORY_CALL
                } else {
                    NotificationCompat.CATEGORY_STATUS
                },
            )
            .setAutoCancel(true)
            .build()

        return try {
            manager.notify(id, notification)
            true
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
            false
        }
    }

    /** Take a notification down — the approval was answered somewhere else. */
    fun cancel(id: Int) {
        manager.cancel(id)
    }

    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return manager.areNotificationsEnabled()
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun channelFor(kind: NotificationKind) = when (kind) {
        NotificationKind.NEEDS_APPROVAL -> CHANNEL_APPROVAL
        else -> CHANNEL_ACTIVITY
    }

    private fun priorityFor(kind: NotificationKind) = when (kind) {
        NotificationKind.NEEDS_APPROVAL -> NotificationCompat.PRIORITY_HIGH
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    companion object {
        const val CHANNEL_APPROVAL = "anodex.approval"
        const val CHANNEL_ACTIVITY = "anodex.activity"

        /**
         * A stable id for the approval notification.
         *
         * One at a time, on purpose: only one prompt can be outstanding, and a
         * stable id means answering it on the desktop replaces or clears this one
         * rather than leaving a dead notification the user taps into nothing.
         */
        const val ID_APPROVAL = 1
    }
}
