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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.anodex.mobile.MainActivity
import dev.anodex.mobile.R
import dev.anodex.mobile.pairing.PairedHostStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
        // No intent means Android restarted this after killing the process, and no host
        // name means the phone has just booted: either way the pairing on disk knows it.
        val hostName = intent?.getStringExtra(EXTRA_HOST) ?: savedHostName() ?: "your computer"
        val connected = intent?.getBooleanExtra(EXTRA_CONNECTED, false) ?: false
        val working = intent?.getBooleanExtra(EXTRA_WORKING, false) ?: false
        val detail = intent?.getStringExtra(EXTRA_DETAIL).orEmpty()

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
                buildStatus(this, hostName, connected, working, detail),
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
            hold.reachedForeground()
            stopSelf()
            return START_NOT_STICKY
        }

        // Asked to stop while this start was still on its way. Stopping then would have
        // crashed the app, so it waited until now, when it is safe.
        if (hold.reachedForeground()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Holds the connection itself whenever the app's screens are not: see BackgroundLink.
        BackgroundLink.ensureRunning(this)

        // Restarted if the system kills it. This used to be NOT_STICKY on the grounds that
        // the app would start it again on reconnecting — which never happens once the app
        // is gone, and "gone" (closed from recent apps, the phone restarted) is exactly when
        // a notification has nowhere else to arrive. The host name now comes from disk.
        return START_STICKY
    }

    override fun onDestroy() {
        BackgroundLink.stop()
        super.onDestroy()
    }

    /** The paired computer's name, read from disk for a start that did not carry one. */
    private fun savedHostName(): String? = runCatching {
        runBlocking { withTimeoutOrNull(500) { PairedHostStore(this@ConnectionService).paired.first() } }
            ?.identity?.displayName
    }.getOrNull()

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

        /**
         * Change what the ongoing notification says, without starting anything.
         *
         * For BackgroundLink, which holds the connection from inside the running service:
         * the screens set this line as they leave, and without an update it went on saying
         * "Reconnecting" over a connection that was up. Re-posting under the service's own
         * id replaces the foreground notification rather than adding one.
         */
        fun showStatus(context: Context, hostName: String, connected: Boolean, detail: String) {
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(NOTIFICATION_ID, buildStatus(context, hostName, connected, false, detail))
            }
        }

        fun buildStatus(
            context: Context,
            hostName: String,
            connected: Boolean,
            working: Boolean,
            detail: String,
        ): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                // The title carries the state and the body carries the substance. While
                // a turn is running that is worth saying in the title itself — it is the
                // one thing somebody glancing at a locked screen actually wants.
                .setContentTitle(
                    when {
                        !connected -> "Reconnecting to $hostName"
                        working -> "$hostName is working"
                        else -> "Connected to $hostName"
                    }
                )
                .setContentText(detail)
                .setContentIntent(open)
                // Silent and unrankable: this is a status line, not news. Every actual
                // event the user needs gets its own notification on its own channel.
                .setSilent(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
        private const val EXTRA_HOST = "host"
        private const val EXTRA_CONNECTED = "connected"
        private const val EXTRA_WORKING = "working"
        private const val EXTRA_DETAIL = "detail"

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
        fun start(
            context: Context,
            hostName: String,
            connected: Boolean,
            working: Boolean = false,
            detail: String = "",
        ) {
            val intent = Intent(context, ConnectionService::class.java)
                .putExtra(EXTRA_HOST, hostName)
                .putExtra(EXTRA_CONNECTED, connected)
                .putExtra(EXTRA_WORKING, working)
                .putExtra(EXTRA_DETAIL, detail)

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onSuccess { hold.startRequested() }
                .onFailure { Log.w(TAG, "could not start the connection service", it) }
            // Swallowed on purpose. A refused start means the app is backgrounded and
            // the process may be killed later — which is the situation this improves,
            // not one it is required for. Crashing the app over it would be worse
            // than the problem.
        }

        /**
         * Stop the service, or have it stop itself once a start still on its way lands.
         *
         * Stopping a service that was started with `startForegroundService` before it has
         * called `startForeground` crashes the app: Android kills the process with
         * `ForegroundServiceDidNotStartInTimeException`. The emulator did exactly that,
         * 8 ms after a start, when the app relaunched and its connection state settled.
         */
        fun stop(context: Context) {
            if (hold.stopRequested()) context.stopService(Intent(context, ConnectionService::class.java))
        }

        /** Touched only on the main thread: the view model's collector and `onStartCommand`. */
        private val hold = ForegroundHold()
    }
}

/**
 * Tracks starts of a foreground service that have not reached the foreground yet, so a
 * stop never lands in between. Pure, so the rule can be tested without Android.
 */
class ForegroundHold {
    private var pendingStarts = 0
    private var stopWhenStarted = false

    /** A start was accepted by the system and its `onStartCommand` is on its way. */
    fun startRequested() {
        pendingStarts++
        stopWhenStarted = false
    }

    /**
     * A start reached the foreground (or gave up trying). True when a stop asked for
     * in the meantime should happen now.
     */
    fun reachedForeground(): Boolean {
        if (pendingStarts > 0) pendingStarts--
        if (pendingStarts > 0 || !stopWhenStarted) return false
        stopWhenStarted = false
        return true
    }

    /** True when the service can be stopped right away; otherwise it stops itself later. */
    fun stopRequested(): Boolean {
        if (pendingStarts == 0) return true
        stopWhenStarted = true
        return false
    }
}

/** What the ongoing notification should say, or null if there should not be one. */
data class ProcessHold(val hostName: String, val connected: Boolean)

/**
 * Whether a given connection state is worth holding the process for.
 *
 * Every state with something paired, since notifications became the point: an app that
 * lets go while the computer is away is not there when it comes back, and what the
 * computer says on waking — a run finished overnight, an approval it is stuck on — goes
 * nowhere. The cost is a quiet ongoing notification, on a low-importance channel, that
 * says what it is waiting for.
 *
 * Pulled out of the service so it can be tested at all: everything else here needs a
 * real Android context, and this is the part with a decision in it. Getting it wrong
 * is not loud — holding through `Offline` leaves an undismissable notification about
 * a connection that gave up hours ago, and dropping the hold during `Reconnecting`
 * lets the process die during precisely the Wi-Fi handoff the grace period exists to
 * ride out.
 */
/**
 * What the ongoing notification should say, under the host's name.
 *
 * This line used to read "Anodex can reach your computer." — every minute of every
 * day, whatever the computer was doing. Which is the one thing the user already
 * knows, because the notification's existence says it.
 *
 * A phone in a pocket is exactly where "what is it doing right now" is worth
 * knowing, and it is the only place this app can say it without being opened. So
 * the line carries the answer instead: the turn in flight, or the model and how full
 * its context is, or plainly that nothing is loaded.
 *
 * Pure, and tested, for the same reason `processHoldFor` is — everything else in the
 * service needs a real Android context, and this is the part with the decisions in it.
 */
fun connectionDetail(
    connected: Boolean,
    working: Boolean,
    modelName: String?,
    usedTokens: Int?,
    contextSize: Int?,
): String {
    // Said as what the hold is for, now that it stays up while the computer is away:
    // the user keeps an ongoing notification so that the next real one reaches them.
    if (!connected) return "Waiting for your computer. Notifications arrive as soon as it's back."

    // The most useful thing it can say, and the reason to glance at all: a run is
    // going on over there while this phone is in a pocket.
    if (working) return "Working on a reply."

    // Nothing loaded is a fact worth carrying, not an absence to paper over: a
    // reachable computer that cannot answer looks identical to a working one until
    // you ask it something.
    val model = modelName?.takeIf { it.isNotBlank() } ?: return "No model loaded."

    // Only when both halves are known. A percentage derived from a count nobody took
    // is the defect this app has already had once, in the context ring.
    val fullness = percentFull(usedTokens, contextSize) ?: return model
    return "$model · $fullness% of ${compactTokens(contextSize!!)}"
}

/** Whole percent, or null when there is no honest figure. */
private fun percentFull(usedTokens: Int?, contextSize: Int?): Int? {
    if (usedTokens == null || contextSize == null || contextSize <= 0) return null
    return ((usedTokens.toFloat() / contextSize) * 100).toInt().coerceIn(0, 100)
}

/** "32K", "8K", "900" — the window, short enough for one line of a notification. */
private fun compactTokens(tokens: Int): String =
    if (tokens < 1_000) tokens.toString() else "${tokens / 1_000}K"

fun processHoldFor(state: ConnectionState): ProcessHold? = when (state) {
    is ConnectionState.Connected -> ProcessHold(state.host.displayName, connected = true)

    // Held: this is a drop inside the grace period, and the whole point of that
    // period is that it is expected to come back.
    is ConnectionState.Reconnecting -> ProcessHold(state.host.displayName, connected = false)

    // Held too, so the phone is still there when the computer comes back. Releasing it
    // here let Android reclaim the app while the computer slept, and nothing was left
    // to reconnect or to show what the computer said once it woke.
    is ConnectionState.Offline -> ProcessHold(state.host.displayName, connected = false)

    // Nothing paired, nothing to reach.
    ConnectionState.Unpaired -> null
}
