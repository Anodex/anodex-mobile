package dev.anodex.mobile.connection

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * What the failure to connect actually was.
 *
 * "Couldn't connect" covers at least four unrelated problems with four unrelated
 * remedies, and the phone was collapsing them into one line plus a guess based on
 * which subnet it happened to be on. That guess is useful when nothing better is
 * available and actively misleading when something is: telling somebody to check
 * their Wi-Fi when the real answer is that the computer presented the wrong
 * certificate sends them to look at something that was never the problem.
 *
 * So this reads the exception, which is *evidence*, and [Reachability] stays as the
 * fallback for the one case where the exception says nothing useful — a timeout,
 * which looks identical whether the computer is asleep, the port is closed, or the
 * phone is on the wrong network.
 *
 * Every message names the address and port that were tried. Not knowing what the app
 * actually dialled is most of what makes this hard to report.
 */
fun diagnoseConnectionFailure(error: Throwable?, address: String, port: Int): String? {
    if (error == null) return null
    val where = "$address:$port"

    // Unwrapped because OkHttp wraps the cause of a failed TLS handshake, and the
    // outer exception is a generic one whose message says nothing.
    for (cause in causeChain(error)) {
        when (cause) {
            // The strongest signal available, and the one the network heuristic would
            // most badly misdiagnose. Something answered and proved it was not the
            // computer this phone paired with.
            is CertificateException, is SSLException -> return (
                "The computer at $where is not the one this phone paired with — it presented a " +
                    "different security certificate. That happens if Anodex was reinstalled or " +
                    "remote access was reset. Pair again from the computer."
                )

            // Something is there and refused. The machine is reachable, so the network
            // is fine and the port is the question.
            is ConnectException -> return (
                "Nothing is listening on $where. Check that remote access is switched on in " +
                    "Anodex on the computer, and that the port matches the one shown there."
                )

            is UnknownHostException -> return (
                "\"$address\" could not be found. If you typed a name, use the numeric address " +
                    "Anodex shows on the computer instead."
                )

            is NoRouteToHostException -> return (
                "There is no route to $where from this phone. It is on a different network than " +
                    "the computer."
                )

            // The connection opened and then died mid-handshake, which is what a
            // router or firewall cutting the stream looks like from here.
            is EOFException -> return (
                "The computer at $where accepted the connection and then dropped it. A firewall " +
                    "between the two is the usual cause."
                )

            // Nothing came back at all. This is the genuinely ambiguous one — asleep,
            // filtered and wrong-network are indistinguishable — so it is handed back
            // for the network heuristic to explain.
            is SocketTimeoutException -> return null
        }
    }

    return null
}

/**
 * The exception and everything underneath it.
 *
 * Capped, because a cause chain that loops would otherwise hang the thread that is
 * trying to explain a failure — which turns a bad error message into a frozen app.
 */
private fun causeChain(error: Throwable): Sequence<Throwable> = sequence {
    var current: Throwable? = error
    var depth = 0
    val seen = mutableSetOf<Throwable>()

    while (current != null && depth < 10 && seen.add(current)) {
        yield(current)
        current = current.cause
        depth++
    }
}
