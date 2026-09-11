package dev.anodex.mobile.connection

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which failure to explain, when every address failed differently.
 *
 * The phone tries each known address in turn and used to keep whichever happened to
 * fail **last**. So the explanation depended on the order of the list rather than on
 * what the failures actually said.
 *
 * What that cost, on a real phone: `10.0.0.153` answered with the wrong certificate
 * — conclusive, and the app has exactly the right sentence for it, naming the
 * reinstall and telling the user to pair again. Then `172.23.226.1`, a Hyper-V
 * adapter the desktop itself labels "usually not reachable from a phone", timed out.
 * The timeout was last, so the timeout won, and the user was told to check that
 * their computer was awake and their firewall was not blocking — while the machine
 * sat awake and reachable, simply no longer the one the phone had paired with.
 *
 * It matters most away from home. With a forwarded port the external address is the
 * only route that can work, and a timeout from a LAN address that was never going to
 * work sends somebody to check Wi-Fi they are not even on.
 */
class MostTellingFailureTest {

    private fun attempt(address: String, error: Exception) = AttemptFailure(address, error)

    @Test
    fun `a certificate mismatch beats a timeout, whatever the order`() {
        // The exact shape of the observed bug, and its mirror. Neither ordering may
        // decide the answer.
        val certificate = attempt("10.0.0.153", SSLPeerUnverifiedException("pin mismatch"))
        val timeout = attempt("172.23.226.1", SocketTimeoutException("timed out"))

        assertEquals(certificate, mostTellingFailure(listOf(certificate, timeout)))
        assertEquals(certificate, mostTellingFailure(listOf(timeout, certificate)))
    }

    @Test
    fun `a timeout never wins against anything that says something`() {
        // A timeout is the one failure that settles nothing: asleep, filtered and
        // wrong-network are identical from here.
        val timeout = attempt("172.23.226.1", SocketTimeoutException("timed out"))

        for (definite in listOf(
            SSLPeerUnverifiedException("pin mismatch"),
            ConnectException("refused"),
            EOFException("cut"),
            NoRouteToHostException("no route"),
            UnknownHostException("nope"),
        )) {
            val telling = mostTellingFailure(listOf(timeout, attempt("10.0.0.153", definite)))
            assertEquals(definite::class, telling?.error!!::class)
        }
    }

    @Test
    fun `a wrapped cause still counts`() {
        // OkHttp wraps the cause of a failed TLS handshake, and the outer exception
        // is a generic one whose message says nothing — which is exactly why
        // `diagnoseConnectionFailure` walks the chain, and why ranking must too.
        val wrapped = attempt("10.0.0.153", Exception("failed", SSLPeerUnverifiedException("pin")))
        val timeout = attempt("172.23.226.1", SocketTimeoutException("timed out"))

        assertEquals(wrapped, mostTellingFailure(listOf(timeout, wrapped)))
    }

    @Test
    fun `the address is carried with the failure it belongs to`() {
        // The explanation names the address that was tried. Reporting a certificate
        // mismatch against the address that merely timed out would send somebody to
        // look at the wrong machine.
        val telling = mostTellingFailure(
            listOf(
                attempt("172.23.226.1", SocketTimeoutException("timed out")),
                attempt("76.120.41.78", SSLPeerUnverifiedException("pin mismatch")),
            )
        )

        assertEquals("76.120.41.78", telling?.address)
    }

    @Test
    fun `an external address away from home is not outranked by a LAN timeout`() {
        // The port-forwarded case. On mobile data the LAN addresses cannot work and
        // their timeouts mean nothing, but they were the ones being explained.
        val telling = mostTellingFailure(
            listOf(
                attempt("10.0.0.153", SocketTimeoutException("timed out")),
                attempt("76.120.41.78", ConnectException("refused")),
                attempt("172.23.226.1", SocketTimeoutException("timed out")),
            )
        )

        assertEquals("76.120.41.78", telling?.address)
    }

    @Test
    fun `a refusal beats a routing failure`() {
        // Something answered on the port versus nothing being there to answer. The
        // first is about the computer and the second is about the network, and only
        // one of them tells the user which to go and look at.
        val refused = attempt("76.120.41.78", ConnectException("refused"))
        val noRoute = attempt("10.0.0.153", NoRouteToHostException("no route"))

        assertEquals(refused, mostTellingFailure(listOf(noRoute, refused)))
    }

    @Test
    fun `nothing tried means nothing to explain`() {
        assertNull(mostTellingFailure(emptyList()))
    }

    @Test
    fun `one failure is its own answer`() {
        val only = attempt("10.0.0.153", SocketTimeoutException("timed out"))

        assertEquals(only, mostTellingFailure(listOf(only)))
    }

    @Test
    fun `an unrecognised failure still beats a timeout`() {
        // A cause the ranking has never seen is not evidence of nothing. It outranks
        // the one failure that is known to settle nothing, and loses to the rest.
        val strange = attempt("10.0.0.153", IllegalStateException("something else"))
        val timeout = attempt("172.23.226.1", SocketTimeoutException("timed out"))
        val refused = attempt("76.120.41.78", ConnectException("refused"))

        assertEquals(strange, mostTellingFailure(listOf(timeout, strange)))
        assertEquals(refused, mostTellingFailure(listOf(strange, refused)))
    }
}
