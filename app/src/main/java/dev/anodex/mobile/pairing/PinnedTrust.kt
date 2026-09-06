package dev.anodex.mobile.pairing

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts exactly one certificate: the desktop's, as pinned at pairing.
 *
 * No certificate authority can vouch for `192.168.1.42`, so the usual chain validation has nothing
 * to work with. Pinning replaces it, and it is a *stronger* guarantee than the public PKI gives —
 * not a weaker substitute for it. After pairing, the only certificate this phone will speak TLS to
 * is the exact one the user confirmed, byte for byte.
 *
 * What that buys, concretely: something else answering on the same address and port — same network
 * name, same everything — is refused without a prompt. There is no "continue anyway", because a
 * mismatch is indistinguishable from the attack this exists to stop, and a user cannot be expected
 * to tell them apart at the moment they are trying to get something done.
 *
 * The consequence, which must be surfaced honestly rather than as a mystery: reinstalling Anodex on
 * the desktop regenerates its certificate, the pin stops matching, and the user has to re-pair.
 * That is correct, and the message should say so.
 */
class PinnedTrustManager(private val pinnedSha256: ByteArray) : X509TrustManager {

    init {
        require(pinnedSha256.size == SHA256_BYTES) {
            "a pinned fingerprint must be a $SHA256_BYTES-byte SHA-256"
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("the server presented no certificate")

        val presented = sha256Of(leaf)
        if (!MessageDigest.isEqual(presented, pinnedSha256)) {
            // Deliberately does not include the expected fingerprint. The presented one is what a
            // user needs to compare against their desktop; printing the pinned value next to it
            // just invites someone to conclude the two "look close enough".
            throw CertificateException(
                "certificate does not match the one pinned at pairing " +
                    "(presented ${shortHex(presented)})",
            )
        }
    }

    /**
     * Never called: this app is a client and presents no certificate of its own. It authenticates
     * with the paired secret over an already-pinned channel.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Anodex Mobile does not act as a TLS server")
    }

    /**
     * Empty on purpose, and this is the important half.
     *
     * Returning the system CAs here would let the platform fall back to normal chain validation,
     * which would happily accept any certificate a public CA signed for this address — exactly
     * what pinning exists to prevent. An empty issuer set says "nothing is trusted by default".
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        const val SHA256_BYTES = 32

        fun sha256Of(certificate: X509Certificate): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

        private fun shortHex(bytes: ByteArray) =
            bytes.take(PairingPayload.FINGERPRINT_BYTES).joinToString(" ") { "%02X".format(it) }
    }
}

/**
 * A socket factory that will only complete a handshake with the pinned certificate.
 *
 * Built per connection rather than cached, because the pin is per-pairing and a stale factory
 * outliving a re-pair is the kind of bug that fails open.
 */
fun pinnedSocketFactory(pinnedSha256: ByteArray): SSLSocketFactory {
    val trustManager = PinnedTrustManager(pinnedSha256)
    val context = SSLContext.getInstance("TLSv1.3")
    context.init(null, arrayOf(trustManager), null)
    return context.socketFactory
}

/**
 * Render a fingerprint the way both screens show it, so the user is comparing like with like.
 *
 * Truncated to 80 bits deliberately: a 64-character hex string does not get read, it gets glanced
 * at and approved, and a fingerprint nobody checks is not a security control. 80 bits is far beyond
 * what could be brute-forced into matching during a pairing window measured in seconds.
 */
fun humanFingerprintOf(sha256: ByteArray): String = sha256
    .take(PairingPayload.FINGERPRINT_BYTES)
    // Group the bytes, not the rendered string. Chunking the string splits mid-byte and puts a
    // leading space inside a group, which reads as a different fingerprint to anyone comparing
    // two screens character by character — which is the entire job of this string.
    .chunked(BYTES_PER_GROUP)
    .joinToString("  ") { group -> group.joinToString(" ") { "%02X".format(it) } }

/** Bytes per visual group in a displayed fingerprint. */
private const val BYTES_PER_GROUP = 5
