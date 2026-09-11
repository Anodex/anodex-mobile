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
 * One address's failure, kept so the most telling of several can be chosen.
 */
data class AttemptFailure(val address: String, val error: Exception)

/**
 * The failure worth explaining, out of every address that was tried.
 *
 * **Not the last one.** That is what this replaces, and the difference is the whole
 * point: the phone tries each known address in turn and used to keep whichever
 * happened to fail last, so a definite answer from one address was overwritten by a
 * vague timeout from another.
 *
 * Observed, on a real phone. `10.0.0.153` answered with the wrong certificate —
 * conclusive, and the app has exactly the right sentence for it. Then
 * `172.23.226.1`, a Hyper-V adapter the desktop itself labels "usually not
 * reachable from a phone", timed out, and that timeout became the explanation. The
 * user was told to check that their computer was awake and their firewall was not
 * blocking, when the machine was awake, reachable, and simply no longer the one
 * this phone had paired with.
 *
 * It matters most away from home. With a forwarded port the external address is the
 * only viable route, and a timeout from a LAN address that was never going to work
 * sends somebody to check Wi-Fi they are not on.
 *
 * So: rank by how much the failure actually settles, and keep the best. A timeout
 * settles nothing and always loses.
 */
fun mostTellingFailure(failures: List<AttemptFailure>): AttemptFailure? =
    failures.maxByOrNull { tellsUs(it.error) }

/**
 * How much a failure settles, higher being more definite.
 *
 * The order is the same judgement `diagnoseConnectionFailure` already makes about
 * which causes are worth speaking up about — kept beside it deliberately, because
 * two rankings of the same evidence would drift.
 */
private fun tellsUs(error: Throwable): Int {
    for (cause in causeChain(error)) {
        when (cause) {
            // Something answered and proved it was not the paired computer. Nothing
            // else is this conclusive, and it is true of the machine rather than of
            // the route, so one address establishing it settles all of them.
            is CertificateException, is SSLException -> return 5

            // Reached the host; the port is the question.
            is ConnectException -> return 4

            // Opened and then cut, which is what something in the middle looks like.
            is EOFException -> return 3

            // About the address itself rather than the computer.
            is UnknownHostException, is NoRouteToHostException -> return 2

            // Settles nothing: asleep, filtered and wrong-network are identical here.
            is SocketTimeoutException -> return 0
        }
    }

    return 1
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
