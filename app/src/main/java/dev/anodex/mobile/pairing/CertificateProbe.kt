package dev.anodex.mobile.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Fetch the certificate a machine is presenting, without trusting it.
 *
 * Only for the **typed** pairing path. A scanned QR carries the fingerprint, so
 * the phone can pin before it says anything; a typed code carries an address and
 * nothing else, so there is nothing yet to pin against.
 *
 * The answer is to look, then ask. This opens a handshake that accepts whatever
 * is presented, reads the leaf certificate's fingerprint, and closes without
 * sending a single byte of application data — no pairing code, no device name.
 * The user then compares that fingerprint with the one on their computer, and
 * only a confirmed match proceeds to pair against a now-pinned certificate.
 *
 * That comparison is the entire security of the typed path, which is why the UI
 * that shows it is blunt about what a mismatch means. Scanning is better and
 * should stay the default: it makes the pin automatic rather than a decision
 * someone can wave through.
 */
object CertificateProbe {

    /** How long to wait for a handshake before giving up. */
    private const val TIMEOUT_MS = 8_000

    /**
     * @return the SHA-256 of the leaf certificate the host presented.
     * @throws Exception if nothing answered, or the handshake failed.
     */
    suspend fun fingerprintOf(address: String, port: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val context = SSLContext.getInstance("TLSv1.2")
            context.init(null, arrayOf(AcceptAndRecord()), java.security.SecureRandom())

            val socket = context.socketFactory.createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                socket.startHandshake()

                val leaf = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: error("That computer presented no certificate.")

                PinnedTrustManager.sha256Of(leaf)
            } finally {
                runCatching { socket.close() }
            }
        }

    /**
     * Accepts any certificate, on purpose and in exactly one place.
     *
     * This is not a weakened trust manager that leaked into general use — it is
     * the mechanism by which the phone can *look at* a certificate it has no way
     * to verify yet. Nothing is sent over a socket built from it, and the
     * fingerprint it yields is shown to a human before anything is trusted.
     */
    private class AcceptAndRecord : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
