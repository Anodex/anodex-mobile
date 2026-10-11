package dev.anodex.mobile.connection

import android.content.Context
import android.os.Build
import android.util.Log
import dev.anodex.mobile.notify.NotificationKind
import dev.anodex.mobile.notify.Notifications
import dev.anodex.mobile.pairing.PairedHost
import dev.anodex.mobile.pairing.PairedHostStore
import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.ServerFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The phone's link to the computer while no screen of the app is running.
 *
 * ## Why this exists
 *
 * The connection belongs to the app's screens (`AnodexViewModel`), so it lives exactly as long
 * as they do. Measured on two Galaxy S7s against a real desktop: with the app in the background
 * a reminder reached the phone, but closed from the recent-apps screen, or after the phone
 * restarted, it did not — the screens were gone, so was the connection, and the computer fell
 * back to showing it on its own screen to nobody. Those are the two most ordinary things that
 * happen to an app on a phone in a pocket, and they are precisely when a notification matters.
 *
 * So when the screens are gone, [ConnectionService] holds this instead: one quiet connection,
 * made with the saved pairing, that does nothing but turn what the computer says into
 * notifications. It never runs alongside the screens' own connection — the computer sends every
 * notification to every connection a phone has, so two would mean every one twice. The screens
 * say when they arrive and leave ([uiAttached], [uiDetached]), and this steps aside or takes
 * over.
 *
 * Deliberately narrow. Plan approvals and replies typed into a notification need the full app,
 * and tapping a notification opens it, at which point the screens take the connection back.
 */
object BackgroundLink {

    private const val TAG = "BackgroundLink"

    /** What the computer pushes for the phone's notification shade. */
    internal const val CHANNEL_NOTIFICATION = "remote:notification"

    /** Ids well above the app's own (which start at 100), so the two never overwrite each other. */
    private const val FIRST_NOTIFICATION_ID = 50_000

    private val INITIAL_BACKOFF: Duration = 500.milliseconds
    private val MAX_BACKOFF: Duration = 30.seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var screensOpen = false

    @Volatile
    private var loop: Job? = null

    @Volatile
    private var socket: AnodexSocket? = null

    private var nextNotificationId = FIRST_NOTIFICATION_ID

    /** The app's screens are up and are about to connect themselves: step aside first. */
    @Synchronized
    fun uiAttached() {
        screensOpen = true
        stop()
    }

    /** The app's screens are gone. Keep notifications coming if anything is paired. */
    @Synchronized
    fun uiDetached(context: Context) {
        screensOpen = false
        ensureRunning(context)
    }

    /** Start the link if nothing else is holding the connection. Safe to call repeatedly. */
    @Synchronized
    fun ensureRunning(context: Context) {
        if (screensOpen || loop?.isActive == true) return
        val appContext = context.applicationContext
        loop = scope.launch { run(appContext) }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close()
        socket = null
    }

    private suspend fun run(context: Context) {
        try {
            listen(context)
        } finally {
            // Nothing left to wait for — this phone was unpaired, or never was — so the
            // ongoing "waiting for your computer" notification would be waiting for nothing.
            if (!screensOpen) withContext(NonCancellable + Dispatchers.Main) {
                if (loopEndedOnItsOwn) ConnectionService.stop(context)
            }
        }
    }

    /** Set when [listen] returns rather than being cancelled by [stop] or [uiAttached]. */
    @Volatile
    private var loopEndedOnItsOwn = false

    private suspend fun listen(context: Context) {
        loopEndedOnItsOwn = false
        val store = PairedHostStore(context)
        // Channels are made by the app on its first run; made again here because after a
        // reinstall this can be the first code that runs.
        val notifications = Notifications(context).also { it.ensureChannels() }
        var wait = INITIAL_BACKOFF

        while (true) {
            val host = store.paired.first() ?: return ended()
            val outcome = try {
                holdConnection(host, store, notifications)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNoLongerPaired(e)) return ended()
                Outcome.FAILED
            }
            when (outcome) {
                Outcome.UNPAIRED -> return ended()
                // It was up, so the computer is around: come straight back.
                Outcome.DROPPED -> wait = INITIAL_BACKOFF
                Outcome.FAILED -> Unit
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    private fun ended() {
        loopEndedOnItsOwn = true
    }

    private enum class Outcome { DROPPED, FAILED, UNPAIRED }

    /** Connect on the first address that answers and stay until the connection ends. */
    private suspend fun holdConnection(
        host: PairedHost,
        store: PairedHostStore,
        notifications: Notifications,
    ): Outcome {
        val fingerprint = hexToBytes(host.certificateFingerprint)
        var lastError: Exception? = null

        for (address in Reachability.orderByPlausibility(host.addresses, localIPv4Addresses())) {
            val candidate = AnodexSocket(
                address = address,
                port = host.port,
                certificateSha256 = fingerprint,
                deviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim(),
            )
            val dropped = CompletableDeferred<RemoteFarewell?>()
            candidate.onDropped = { farewell -> dropped.complete(farewell) }
            try {
                candidate.connect(AnodexSocket.Credential.DeviceKey(host.secret))
            } catch (e: CancellationException) {
                candidate.close()
                throw e
            } catch (e: Exception) {
                candidate.close()
                if (isNoLongerPaired(e)) return Outcome.UNPAIRED
                lastError = e
                continue
            }

            synchronized(this) { socket = candidate }
            Log.i(TAG, "holding the connection to ${host.identity.displayName} for notifications")
            store.recordSeen(System.currentTimeMillis())
            val listening = scope.launch {
                candidate.events.collect { event -> onEvent(event, notifications) }
            }
            try {
                val farewell = dropped.await()
                return if (farewell == RemoteFarewell.UNPAIRED) Outcome.UNPAIRED else Outcome.DROPPED
            } finally {
                listening.cancel()
                synchronized(this) { if (socket === candidate) socket = null }
                candidate.close()
            }
        }
        if (lastError != null) Log.d(TAG, "no address answered: ${lastError.message}")
        return Outcome.FAILED
    }

    private fun onEvent(event: ServerFrame.Event, notifications: Notifications) {
        if (event.channel != CHANNEL_NOTIFICATION) return
        val fields = event.payload as? JsonObject ?: return
        val title = (fields["title"] as? JsonPrimitive)?.contentOrNull ?: return
        val kind = NotificationKind.parse((fields["kind"] as? JsonPrimitive)?.contentOrNull)
        val body = (fields["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val conversationId = (fields["conversationId"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        val id = if (kind == NotificationKind.NEEDS_APPROVAL) {
            Notifications.ID_APPROVAL
        } else {
            synchronized(this) { nextNotificationId++ }
        }
        notifications.show(id, kind, title, body, conversationId)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
