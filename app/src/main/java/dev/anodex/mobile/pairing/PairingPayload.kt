package dev.anodex.mobile.pairing

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * What the desktop's pairing QR contains, once parsed and validated.
 *
 * This is untrusted input. It arrives through a camera, from a code that is *supposed* to be on the
 * user's own screen, and every field is used for something consequential — one of them decides which
 * certificate the phone will trust for the life of the pairing. So parsing is strict and total:
 * anything malformed, oversized, expired or unrecognised is rejected with a reason, and there is no
 * partial success.
 *
 * Wire form is a URI, because a QR scanner hands back a string and a URI keeps it compact:
 *
 * ```
 * anodex://pair?v=1&h=<hostId>&n=<name>&a=<address>&p=<port>
 *              &f=<sha256 of the cert DER, base64url>&s=<secret, base64url>&e=<expiry, epoch sec>
 * ```
 */
data class PairingPayload(
    /** Stable identity of the desktop. Pairing binds to this, never to [address] (§10.1). */
    val hostId: String,

    /** The machine's own name, shown to the user. Sanitised — see [MAX_NAME_LENGTH]. */
    val displayName: String,

    /** Where to reach it *right now*. Expected to change between Wi-Fi and Tailscale. */
    val address: String,
    val port: Int,

    /**
     * SHA-256 of the desktop's self-signed certificate, DER-encoded. 32 bytes.
     *
     * This is the security-critical field. No CA can vouch for a LAN address, so the phone pins
     * this and refuses anything else for the life of the pairing.
     */
    val certificateSha256: ByteArray,

    /** One-time secret, exchanged for a long-lived device key and then burned. */
    val secret: ByteArray,

    /** When the desktop stops honouring [secret]. Seconds since epoch. */
    val expiresAtEpochSec: Long,
) {
    /**
     * The certificate fingerprint in the form shown on both screens for the user to compare.
     *
     * Truncated to [FINGERPRINT_BYTES] because a 64-character hex string is not something anyone
     * actually checks — an unreadable fingerprint gets glanced at and approved, which is worse than
     * a shorter one that gets read. 80 bits is far beyond what an attacker could brute-force into
     * matching during a pairing that lasts under a minute.
     *
     * **Only for display.** [certificateSha256] is what gets pinned.
     */
    fun humanFingerprint(): String = humanFingerprintOf(certificateSha256)

    // ByteArray fields make the generated equals/hashCode reference-based, which is a silent
    // correctness trap in a data class. Compared structurally instead.

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingPayload) return false
        return hostId == other.hostId &&
            displayName == other.displayName &&
            address == other.address &&
            port == other.port &&
            certificateSha256.contentEquals(other.certificateSha256) &&
            secret.contentEquals(other.secret) &&
            expiresAtEpochSec == other.expiresAtEpochSec
    }

    override fun hashCode(): Int {
        var result = hostId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + port
        result = 31 * result + certificateSha256.contentHashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + expiresAtEpochSec.hashCode()
        return result
    }

    /** Never let a secret reach a log, a crash report or a diagnostic bundle. */
    override fun toString(): String =
        "PairingPayload(hostId=$hostId, displayName=$displayName, address=$address, port=$port, " +
            "fingerprint=${humanFingerprint()}, secret=<redacted>, expiresAt=$expiresAtEpochSec)"

    companion object {
        const val SCHEME = "anodex"
        const val HOST = "pair"

        /** Only version this build understands. An unknown version is refused, never guessed at. */
        const val VERSION = 1

        const val CERT_SHA256_BYTES = 32

        /** Below this, the secret is not worth calling one. */
        const val MIN_SECRET_BYTES = 32

        /** Bytes of the fingerprint shown to the user: 80 bits. */
        const val FINGERPRINT_BYTES = 10

        /**
         * A machine name is attacker-influenced text that gets rendered on screen. Capped so a
         * hostile QR cannot push the rest of the pairing UI off the display, and stripped of
         * control characters so it cannot forge line breaks into a confirmation prompt.
         */
        const val MAX_NAME_LENGTH = 64

        /**
         * The furthest ahead a pairing code may claim to expire.
         *
         * A one-time secret good for a week is not one-time in any useful sense. A desktop
         * offering that is either broken or hostile, and either way the phone should decline.
         */
        const val MAX_VALIDITY_SEC = 10 * 60L
    }
}

/** Why a scanned code was rejected. Each maps to something specific the user can be told. */
sealed interface PairingParseError {
    /** Not an Anodex pairing code at all — an ordinary QR someone happened to scan. */
    data object NotAPairingCode : PairingParseError

    /** A pairing code, but from a newer (or older) Anodex than this app speaks. */
    data class UnsupportedVersion(val found: String?) : PairingParseError

    /** Structurally wrong: a missing or unusable field. */
    data class Malformed(val field: String, val reason: String) : PairingParseError

    /** Well-formed but stale — the desktop has already stopped honouring it. */
    data object Expired : PairingParseError
}

/**
 * Parse and validate a scanned QR.
 *
 * @param nowEpochSec injected so expiry is testable without waiting.
 */
fun parsePairingPayload(
    scanned: String,
    nowEpochSec: Long,
): Result<PairingPayload> {
    val uri = runCatching { URI(scanned.trim()) }.getOrNull()
        ?: return Result.failure(PairingParseException(PairingParseError.NotAPairingCode))

    if (!uri.scheme.equals(PairingPayload.SCHEME, ignoreCase = true) ||
        !uri.host.equals(PairingPayload.HOST, ignoreCase = true)
    ) {
        return Result.failure(PairingParseException(PairingParseError.NotAPairingCode))
    }

    val query = parseQuery(uri.rawQuery)
    // An empty `v=` is an absent version, not a version named "".
    val version = query["v"]?.takeIf { it.isNotBlank() }
    if (version != PairingPayload.VERSION.toString()) {
        return Result.failure(
            PairingParseException(PairingParseError.UnsupportedVersion(version)),
        )
    }

    fun malformed(field: String, reason: String) =
        Result.failure<PairingPayload>(
            PairingParseException(PairingParseError.Malformed(field, reason)),
        )

    val hostId = query["h"]?.takeIf { it.isNotBlank() }
        ?: return malformed("h", "missing host id")

    val rawName = query["n"] ?: return malformed("n", "missing host name")
    val displayName = sanitizeDisplayName(rawName)
    if (displayName.isEmpty()) return malformed("n", "host name is empty after sanitising")

    val address = query["a"]?.takeIf { it.isNotBlank() }
        ?: return malformed("a", "missing address")

    val port = query["p"]?.toIntOrNull()
        ?: return malformed("p", "port is not a number")
    if (port !in 1..65535) return malformed("p", "port out of range")

    val certificate = decodeBase64Url(query["f"])
        ?: return malformed("f", "certificate fingerprint is not valid base64url")
    if (certificate.size != PairingPayload.CERT_SHA256_BYTES) {
        return malformed("f", "expected a ${PairingPayload.CERT_SHA256_BYTES}-byte SHA-256")
    }

    val secret = decodeBase64Url(query["s"])
        ?: return malformed("s", "secret is not valid base64url")
    if (secret.size < PairingPayload.MIN_SECRET_BYTES) {
        return malformed("s", "secret is shorter than ${PairingPayload.MIN_SECRET_BYTES} bytes")
    }

    val expiresAt = query["e"]?.toLongOrNull()
        ?: return malformed("e", "expiry is not a number")

    if (expiresAt <= nowEpochSec) {
        return Result.failure(PairingParseException(PairingParseError.Expired))
    }
    if (expiresAt - nowEpochSec > PairingPayload.MAX_VALIDITY_SEC) {
        return malformed("e", "pairing code is valid for far too long to be single-use")
    }

    return Result.success(
        PairingPayload(
            hostId = hostId,
            displayName = displayName,
            address = address,
            port = port,
            certificateSha256 = certificate,
            secret = secret,
            expiresAtEpochSec = expiresAt,
        ),
    )
}

/** Carries a [PairingParseError] through Kotlin's [Result]. */
class PairingParseException(val error: PairingParseError) : Exception(error.toString())

/**
 * Make a machine name safe to render.
 *
 * Control characters are stripped rather than escaped — a name is a name, and one containing a
 * newline or a bidi override is not trying to be readable, it is trying to reshape the confirmation
 * prompt around it.
 */
private fun sanitizeDisplayName(raw: String): String = raw
    .filter { it.code >= 0x20 && it.code != 0x7F && !it.isBidiControl() }
    .trim()
    .take(PairingPayload.MAX_NAME_LENGTH)

/**
 * Unicode bidirectional overrides, which can visually reverse text after them — the trick behind
 * "trojan source". A hostname has no business containing one.
 */
private fun Char.isBidiControl(): Boolean = code in 0x202A..0x202E || code in 0x2066..0x2069

private fun decodeBase64Url(value: String?): ByteArray? {
    if (value.isNullOrBlank()) return null
    // Strict: the decoder rejects anything outside the base64url alphabet rather than skipping it,
    // so a fingerprint with a stray character fails loudly instead of silently decoding short.
    return runCatching { Base64.getUrlDecoder().decode(value.trimEnd('=')) }.getOrNull()
}

/**
 * Split a raw query string into decoded key/value pairs.
 *
 * Written out rather than using `Uri.getQueryParameter`, whose Android implementation is a stub in
 * a JVM unit test — and this parser handles untrusted input that decides which certificate the
 * phone will trust, so it is not somewhere to settle for "the build compiled".
 *
 * A repeated key keeps the **first** occurrence. Last-wins would let a crafted code append a second
 * `f=` that overrides the fingerprint a reviewer read in the first one.
 */
private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (pair in rawQuery.split('&')) {
        if (pair.isEmpty()) continue
        val separator = pair.indexOf('=')
        if (separator <= 0) continue
        val key = decodeComponent(pair.substring(0, separator))
        val value = decodeComponent(pair.substring(separator + 1))
        if (key != null && value != null) result.putIfAbsent(key, value)
    }
    return result
}

private fun decodeComponent(value: String): String? =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()
