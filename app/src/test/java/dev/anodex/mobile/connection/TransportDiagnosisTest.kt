package dev.anodex.mobile.connection

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a failed connection into the one sentence that fixes it.
 *
 * "Couldn't connect" covers at least four unrelated problems with four unrelated
 * remedies. Getting this wrong is not a cosmetic failure: sending somebody to check
 * their Wi-Fi when the computer actually presented the wrong certificate costs them
 * an evening looking at something that was never involved.
 *
 * The rule these encode is that **evidence outranks inference**. An exception that
 * names its cause is a fact; the subnet heuristic is a guess, and it only gets to
 * speak when the exception says nothing useful.
 */
class TransportDiagnosisTest {

    private val address = "10.0.0.153"
    private val port = 47800

    private fun diagnose(error: Throwable?) = diagnoseConnectionFailure(error, address, port)

    @Test
    fun `a certificate mismatch says so, and says to pair again`() {
        // The case the network heuristic would most badly misdiagnose: the computer
        // answered, so by every network measure things are fine.
        val message = diagnose(CertificateException("fingerprint does not match"))

        assertTrue(message!!.contains("not the one this phone paired with"))
        assertTrue(message.contains("Pair again"))
    }

    @Test
    fun `a certificate failure buried under a TLS exception is still found`() {
        // OkHttp wraps it. Reading only the outer exception gets a generic message
        // that explains nothing, which is how this ends up misdiagnosed as a network
        // problem.
        val wrapped = SSLHandshakeException("handshake failed").apply {
            initCause(CertificateException("fingerprint does not match"))
        }

        assertTrue(diagnose(wrapped)!!.contains("different security certificate"))
    }

    @Test
    fun `a refused connection blames the port, not the network`() {
        // Something answered and said no, so the phone can reach the machine. The
        // network is provably fine and the port or the setting is the question.
        val message = diagnose(ConnectException("Connection refused"))

        assertTrue(message!!.contains("Nothing is listening"))
        assertTrue(message.contains("$address:$port"))
    }

    @Test
    fun `an unknown host suggests using the numeric address`() {
        assertTrue(diagnose(UnknownHostException("merlin-pc"))!!.contains("could not be found"))
    }

    @Test
    fun `no route says the phone is on a different network`() {
        assertTrue(diagnose(NoRouteToHostException())!!.contains("different network"))
    }

    @Test
    fun `a stream cut mid-handshake points at a firewall`() {
        // Accepted then dropped is what something in between does; a closed port
        // refuses instead, and an absent machine times out.
        assertTrue(diagnose(EOFException())!!.contains("firewall"))
    }

    @Test
    fun `a timeout defers rather than guessing`() {
        // Asleep, firewalled, wrong port and wrong network are indistinguishable from
        // a timeout. Returning null hands it to the network heuristic, which at least
        // knows which subnet the phone is on.
        assertNull(diagnose(SocketTimeoutException("timeout")))
    }

    @Test
    fun `an unrecognised failure defers rather than inventing a cause`() {
        // A confident wrong answer is worse than no answer. Anything not understood
        // falls through to the heuristic.
        assertNull(diagnose(IOException("unexpected end of stream")))
        assertNull(diagnose(null))
    }

    @Test
    fun `every message names what was actually dialled`() {
        // Not knowing which address and port the app tried is most of what makes a
        // failure impossible to report.
        for (error in listOf(ConnectException(), NoRouteToHostException(), EOFException())) {
            assertTrue(
                "${error::class.simpleName} did not name the address",
                diagnose(error)!!.contains("$address:$port"),
            )
        }
    }

    @Test
    fun `a looping cause chain does not hang`() {
        // Two exceptions naming each other as their cause. Walking that without a
        // bound turns a bad error message into a frozen app, on the screen the user
        // reached *because* something was already wrong.
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)

        assertNull(diagnose(first))
    }
}
