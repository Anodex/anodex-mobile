package dev.anodex.mobile.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.app.Service
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.anodex.mobile.MainActivity
import dev.anodex.mobile.R

/**
 * Keeps the app alive while it is connected to the computer.
 *
 * ## Why this has to exist
 *
 * Android kills backgrounded processes, and it does so without warning and without
 * regard for what they were in the middle of. Everything the phone knows — the
 * socket, the open conversation, an approval the user has not answered yet — lives
 * in the app's process and nothing is cached to disk, so a kill is not a pause. It
 * is the whole session gone, and the user finds out by opening the app and being
 * told it is reconnecting.
 *
 * That is exactly the moment this app is supposed to be useful: the phone is in a
 * pocket while a long run works on the computer, and the notification saying the run
 * needs an answer has to arrive without the app being open to receive it.
 *
 * ## Why it is honest about itself
 *
 * A foreground service is a permanent notification the user cannot dismiss, which is
 * a real cost and the reason Android makes it the price of staying alive. So it is
 * only started **while actually connected**, torn down the moment the connection is
 * given up, and its notification says something true and useful — which machine, and
 * whether the link is up — rather than "Anodex is running".
 *
 * It deliberately does **not** own the connection. `AnodexViewModel` still does. This
 * raises the process's priority and holds it in memory; moving the socket in here as
 * well would be a much larger change with no way to verify it — there is no Android
 * toolchain on the machine this is built on, so CI compiles and unit-tests it and
 * nothing exercises the runtime.
 */
class ConnectionService : Service() {

    /** Nothing binds to this. It exists to hold the process, not to be talked to. */
    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hostName = intent?.getStringExtra(EXTRA_HOST) ?: "your computer"
        val connected = intent?.getBooleanExtra(EXTRA_CONNECTED, true) ?: true

        ensureChannel()

        // Never allowed to take the app down with it.
        //
        // `startForeground` throws for a whole family of reasons that depend on the
        // Android version, the declared service type, which permissions happen to be
        // granted, and whether the app was in the foreground at the moment it was
        // called. This crashed the app on launch once already: the service was
        // declared `connectedDevice`, which on Android 14 and later requires one of a
        // set of prerequisite permissions this app has no business holding — so it
        // threw `SecurityException`, the process died, and the next launch
        // reconnected and did it again.
        //
        // This service is an optimisation. Losing it costs the process some
        // resilience when backgrounded; letting it throw costs the whole app.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(hostName, connected),
                // The constant only exists from API 34. Below that the type is
                // taken from the manifest and this argument is ignored anyway.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not go to the foreground; carrying on without it", e)
            // A started service that never reaches the foreground is killed by the
            // system anyway, and an ongoing notification for something not running
            // would be a lie. Stand down cleanly instead.
            stopSelf()
            return START_NOT_STICKY
        }

        // Not restarted with a null intent if the system kills us: without the host
        // name there is nothing truthful to put in the notification, and the app will
        // start this again the moment it reconnects.
        return START_NOT_STICKY
    }

    private fun buildNotification(hostName: String, connected: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (connected) "Connected to $hostName" else "Reconnecting to $hostName")
            .setContentText(
                if (connected) {
                    "Anodex can reach your computer."
                } else {
                    "Trying to reach your computer."
                }
            )
            .setContentIntent(open)
            // Silent and unrankable: this is a status line, not news. Every actual
            // event the user needs gets its own notification on its own channel.
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Connection",
            // The lowest importance that still permits a foreground service. It has
            // to be visible; it does not have to be noticed.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while the app is connected to your computer."
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ConnectionService"
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_HOST = "host"
        private const val EXTRA_CONNECTED = "connected"

        /**
         * Start or update the service.
         *
         * Safe to call repeatedly — a second `startForegroundService` on a running
         * service just delivers another `onStartCommand`, which is how the
         * notification gets updated when the link drops or comes back.
         *
         * Must be called while the app is in the foreground. Android 12 and later
         * throw `ForegroundServiceStartNotAllowedException` for a start from the
         * background, so this is called on the connection transition, which only
         * happens with the app open.
         */
        fun start(context: Context, hostName: String, connected: Boolean) {
            val intent = Intent(context, ConnectionService::class.java)
                .putExtra(EXTRA_HOST, hostName)
                .putExtra(EXTRA_CONNECTED, connected)

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start the connection service", it) }
            // Swallowed on purpose. A refused start means the app is backgrounded and
            // the process may be killed later — which is the situation this improves,
            // not one it is required for. Crashing the app over it would be worse
            // than the problem.
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}

/** What the ongoing notification should say, or null if there should not be one. */
data class ProcessHold(val hostName: String, val connected: Boolean)

/**
 * Whether a given connection state is worth holding the process for.
 *
 * Pulled out of the service so it can be tested at all: everything else here needs a
 * real Android context, and this is the part with a decision in it. Getting it wrong
 * is not loud — holding through `Offline` leaves an undismissable notification about
 * a connection that gave up hours ago, and dropping the hold during `Reconnecting`
 * lets the process die during precisely the Wi-Fi handoff the grace period exists to
 * ride out.
 */
fun processHoldFor(state: ConnectionState): ProcessHold? = when (state) {
    is ConnectionState.Connected -> ProcessHold(state.host.displayName, connected = true)

    // Held: this is a drop inside the grace period, and the whole point of that
    // period is that it is expected to come back.
    is ConnectionState.Reconnecting -> ProcessHold(state.host.displayName, connected = false)

    // Offline has already exhausted the grace period and Unpaired has nothing to
    // reach. Neither is worth a notification the user cannot dismiss.
    is ConnectionState.Offline, ConnectionState.Unpaired -> null
}
