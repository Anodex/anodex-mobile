package dev.anodex.mobile.transport

import dev.anodex.mobile.pairing.PinnedTrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One connection to a paired desktop.
 *
 * TLS is enforced by [PinnedTrustManager]: the only certificate this will complete
 * a handshake with is the one confirmed at pairing. Hostname verification is
 * bypassed *deliberately* — the certificate asserts no name, because the desktop's
 * address changes between Wi-Fi and Tailscale, and pairing binds to the machine's
 * identity rather than to an address. The pin is a stronger check than a name
 * match, not a weaker substitute for one.
 *
 * The socket owns no policy. It connects, authenticates, sends calls, and surfaces
 * events; whether to reconnect, and when to give up, belongs to
 * `ConnectionController`.
 */
class AnodexSocket(
    private val address: String,
    private val port: Int,
    private val certificateSha256: ByteArray,
    private val deviceName: String,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<JsonElement?>>>()
    private var socket: WebSocket? = null

    private val _events = MutableSharedFlow<ServerFrame.Event>(
        replay = 0,
        extraBufferCapacity = 256,
        // Local generation outruns a phone on cellular. Dropping the oldest keeps
        // the stream live rather than stalling the socket behind a slow collector;
        // the conversation on disk stays authoritative either way.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Pushed events: streamed tokens, tool activity, approval requests. */
    val events: Flow<ServerFrame.Event> = _events.asSharedFlow()

    private val handshake = CompletableDeferred<Result<Handshake>>()

    /** What the desktop said when the connection was accepted. */
    data class Handshake(
        val deviceId: String,
        val issuedDeviceKey: String?,
        /** Where else this machine can be reached. Stored so a later reconnect can try them. */
        val addresses: List<String> = emptyList(),
    )

    /**
     * Open the socket and complete the handshake.
     *
     * @param credential either an existing device key, or a one-time pairing secret.
     */
    suspend fun connect(credential: Credential, timeout: Duration = 15.seconds): Handshake {
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .sslSocketFactory(pinnedContext().socketFactory, PinnedTrustManager(certificateSha256))
            // See the class comment: the certificate asserts no hostname, so a name
            // check has nothing meaningful to compare and the pin is what decides.
            .hostnameVerifier { _, _ -> true }
            .build()

        val request = Request.Builder().url("wss://$address:$port").build()
        socket = client.newWebSocket(request, Listener())

        val opening = when (credential) {
            is Credential.DeviceKey -> ClientFrames.hello(credential.value, deviceName)
            is Credential.PairingSecret -> ClientFrames.pair(credential.value, deviceName)
        }
        socket?.send(opening)

        return withTimeout(timeout) { handshake.await() }.getOrThrow()
    }

    /** Invoke a channel and await its result. */
    suspend fun invoke(
        channel: String,
        args: List<JsonElement> = emptyList(),
        timeout: Duration = 60.seconds,
    ): JsonElement? {
        val socket = socket ?: error("not connected")
        val id = newCallId()
        val deferred = CompletableDeferred<Result<JsonElement?>>()
        pending[id] = deferred

        return try {
            socket.send(ClientFrames.invoke(id, channel, args))
            withTimeout(timeout) { deferred.await() }.getOrThrow()
        } finally {
            // Removed on every path, including timeout and cancellation: a map that
            // only cleans up on success leaks one entry per dropped call, and the
            // leak is invisible until a long session runs out of memory.
            pending.remove(id)
        }
    }

    fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        failPending(IllegalStateException("The connection closed."))
    }

    private fun pinnedContext(): SSLContext =
        SSLContext.getInstance("TLSv1.2").apply {
            init(null, arrayOf(PinnedTrustManager(certificateSha256)), SecureRandom())
        }

    private fun failPending(cause: Throwable) {
        for ((id, deferred) in pending) {
            deferred.complete(Result.failure(cause))
            pending.remove(id)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val frame = parseServerFrame(text)) {
                is ServerFrame.Welcome -> completeHandshake(frame.protocolVersion) {
                    Handshake(frame.deviceId, null, frame.addresses)
                }

                is ServerFrame.Paired -> completeHandshake(frame.protocolVersion) {
                    Handshake(frame.deviceId, frame.deviceKey, frame.addresses)
                }

                is ServerFrame.CallResult -> {
                    val deferred = pending.remove(frame.id) ?: return
                    deferred.complete(
                        if (frame.ok) Result.success(frame.result)
                        else Result.failure(
                            RemoteCallException(
                                frame.error?.code ?: "unknown",
                                frame.error?.message ?: "The computer refused that.",
                            ),
                        ),
                    )
                }

                is ServerFrame.Event -> _events.tryEmit(frame)

                is ServerFrame.Refused -> {
                    val failure = RemoteCallException(frame.code, frame.message)
                    if (!handshake.isCompleted) handshake.complete(Result.failure(failure))
                    failPending(failure)
                }

                ServerFrame.Pong, null -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!handshake.isCompleted) handshake.complete(Result.failure(t))
            failPending(t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            failPending(IllegalStateException("The connection closed: $reason"))
        }
    }

    private fun completeHandshake(serverVersion: String, build: () -> Handshake) {
        if (handshake.isCompleted) return
        if (!versionsCompatible(serverVersion, PROTOCOL_VERSION)) {
            handshake.complete(
                Result.failure(
                    RemoteCallException(
                        "protocol-mismatch",
                        "That computer speaks protocol $serverVersion; this app speaks " +
                            "$PROTOCOL_VERSION. Update the app.",
                    ),
                ),
            )
            return
        }
        handshake.complete(Result.success(build()))
    }

    /** How this connection authenticates. */
    sealed interface Credential {
        /** An established pairing. */
        @JvmInline value class DeviceKey(val value: String) : Credential

        /** A one-time code scanned from the desktop's QR. */
        @JvmInline value class PairingSecret(val value: String) : Credential
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        private val random = SecureRandom()

        fun newCallId(): String = java.math.BigInteger(64, random).toString(36)
    }
}

/** The desktop declined a call, with the reason it gave. */
class RemoteCallException(val code: String, override val message: String) : Exception(message)
