package dev.anodex.mobile.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The pairing QR parser.
 *
 * This is the app's only untrusted input, it arrives through a camera, and one of its fields
 * decides which TLS certificate the phone will trust for the life of the pairing. So the cases
 * that matter are hostile ones, not happy-path ones — a parser that accepts a well-formed code is
 * the easy half.
 */
class PairingPayloadTest {

    private val now = 1_757_000_000L

    private fun b64(bytes: ByteArray) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private val cert = ByteArray(32) { it.toByte() }
    private val secret = ByteArray(32) { (it + 100).toByte() }

    private fun code(
        version: String = "1",
        hostId: String = "host-abc",
        name: String = "STUDIO-PC",
        address: String = "192.168.1.42",
        port: String = "8765",
        fingerprint: String = b64(cert),
        secretParam: String = b64(secret),
        expiry: String = (now + 60).toString(),
        extra: String = "",
    ) = "anodex://pair?v=$version&h=$hostId&n=$name&a=$address&p=$port" +
        "&f=$fingerprint&s=$secretParam&e=$expiry$extra"

    private fun parse(raw: String, at: Long = now) = parsePairingPayload(raw, at)

    private fun errorOf(raw: String, at: Long = now): PairingParseError =
        (parse(raw, at).exceptionOrNull() as PairingParseException).error

    // --- the happy path, briefly ---------------------------------------------------------------

    @Test
    fun `a well-formed code parses`() {
        val payload = parse(code()).getOrThrow()

        assertEquals("host-abc", payload.hostId)
        assertEquals("STUDIO-PC", payload.displayName)
        assertEquals("192.168.1.42", payload.address)
        assertEquals(8765, payload.port)
        assertTrue(payload.certificateSha256.contentEquals(cert))
        assertTrue(payload.secret.contentEquals(secret))
    }

    // --- not our code --------------------------------------------------------------------------

    @Test
    fun `an unrelated QR is not a pairing code`() {
        assertEquals(PairingParseError.NotAPairingCode, errorOf("https://example.com/"))
        assertEquals(PairingParseError.NotAPairingCode, errorOf("just some text"))
        assertEquals(PairingParseError.NotAPairingCode, errorOf("anodex://something-else?v=1"))
    }

    @Test
    fun `a future protocol version is refused rather than guessed at`() {
        // Half-understanding a newer code is how a client ends up trusting a field it has
        // misinterpreted. Refuse, and let the user be told to update.
        assertEquals(PairingParseError.UnsupportedVersion("2"), errorOf(code(version = "2")))
        assertEquals(PairingParseError.UnsupportedVersion(null), errorOf(code(version = "")))
    }

    // --- the fingerprint, which is the field that matters ---------------------------------------

    @Test
    fun `a fingerprint that is not a full SHA-256 is refused`() {
        // A short fingerprint would pin fewer bits than intended — the kind of weakening that
        // still "works" in testing and quietly costs most of the guarantee.
        val short = errorOf(code(fingerprint = b64(ByteArray(16))))
        assertTrue(short is PairingParseError.Malformed && short.field == "f")

        val long = errorOf(code(fingerprint = b64(ByteArray(48))))
        assertTrue(long is PairingParseError.Malformed && long.field == "f")
    }

    @Test
    fun `a fingerprint with a stray character fails instead of decoding short`() {
        val error = errorOf(code(fingerprint = "not*valid*base64"))
        assertTrue(error is PairingParseError.Malformed && error.field == "f")
    }

    @Test
    fun `a repeated field cannot override the one a reviewer read`() {
        // Last-wins parsing would let a crafted code append a second f= that silently replaces the
        // fingerprint shown in the first. First-wins makes the visible value the effective one.
        val attacker = ByteArray(32) { 0xEE.toByte() }
        val payload = parse(code(extra = "&f=${b64(attacker)}")).getOrThrow()

        assertTrue("the first fingerprint wins", payload.certificateSha256.contentEquals(cert))
        assertNotEquals(b64(attacker), b64(payload.certificateSha256))
    }

    // --- the secret ----------------------------------------------------------------------------

    @Test
    fun `a secret with too little entropy is refused`() {
        val error = errorOf(code(secretParam = b64(ByteArray(8))))
        assertTrue(error is PairingParseError.Malformed && error.field == "s")
    }

    @Test
    fun `the secret never appears in toString`() {
        // toString reaches logs, crash reports and diagnostic bundles by default.
        val rendered = parse(code()).getOrThrow().toString()

        assertTrue("secret is redacted", rendered.contains("<redacted>"))
        assertTrue("no base64 of the secret leaked", !rendered.contains(b64(secret)))
    }

    // --- expiry --------------------------------------------------------------------------------

    @Test
    fun `an expired code is refused`() {
        assertEquals(PairingParseError.Expired, errorOf(code(expiry = (now - 1).toString())))
        assertEquals(PairingParseError.Expired, errorOf(code(expiry = now.toString())))
    }

    @Test
    fun `a code claiming to live for a week is refused`() {
        // A one-time secret valid for days is not one-time in any useful sense. A desktop offering
        // it is broken or hostile, and the phone declines either way.
        val week = now + 7 * 24 * 60 * 60
        val error = errorOf(code(expiry = week.toString()))
        assertTrue(error is PairingParseError.Malformed && error.field == "e")
    }

    // --- the display name is attacker-influenced text -------------------------------------------

    @Test
    fun `control characters are stripped from the machine name`() {
        // A name carrying newlines could forge extra lines into the confirmation prompt around it.
        val payload = parse(code(name = "MERLIN%0APC%0D%00")).getOrThrow()

        assertEquals("MERLINPC", payload.displayName)
    }

    @Test
    fun `bidi overrides are stripped from the machine name`() {
        // The trojan-source trick: an override reverses the text after it, so a name can be made
        // to render as something other than what it is.
        val payload = parse(code(name = "MERLIN%E2%80%AEPC")).getOrThrow()

        assertEquals("MERLINPC", payload.displayName)
    }

    @Test
    fun `an over-long machine name is capped`() {
        val payload = parse(code(name = "A".repeat(500))).getOrThrow()

        assertEquals(PairingPayload.MAX_NAME_LENGTH, payload.displayName.length)
    }

    @Test
    fun `a name that sanitises away to nothing is refused`() {
        val error = errorOf(code(name = "%00%01%02"))
        assertTrue(error is PairingParseError.Malformed && error.field == "n")
    }

    // --- the rest ------------------------------------------------------------------------------

    @Test
    fun `an out-of-range port is refused`() {
        for (port in listOf("0", "65536", "-1", "notanumber")) {
            val error = errorOf(code(port = port))
            assertTrue("port $port refused", error is PairingParseError.Malformed)
        }
    }

    @Test
    fun `every required field is actually required`() {
        val required = listOf("h" to "h=host-abc", "a" to "a=192.168.1.42", "p" to "p=8765")
        for ((field, param) in required) {
            val without = code().replace("&$param", "")
            val error = errorOf(without)
            assertTrue(
                "missing $field is refused, got $error",
                error is PairingParseError.Malformed && error.field == field,
            )
        }
    }

    @Test
    fun `the displayed fingerprint is stable and short enough to actually compare`() {
        val payload = parse(code()).getOrThrow()
        val shown = payload.humanFingerprint()

        assertEquals("00 01 02 03 04  05 06 07 08 09", shown)
        assertEquals(
            "same derivation as the trust manager reports",
            shown,
            humanFingerprintOf(cert),
        )
    }
}
