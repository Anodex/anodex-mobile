package dev.anodex.mobile.connection

import dev.anodex.mobile.connection.Reachability.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Explaining a failed connection.
 *
 * The case that prompted this: a router publishing its 2.4GHz and 5GHz bands as
 * separate SSIDs on separate subnets. The computer sits on one, the phone joins
 * the other, and every field the user typed is correct — so the app must be able
 * to say *that* rather than repeating that something did not answer.
 */
class ReachabilityTest {

    @Test
    fun `a phone on the same subnet is not the reason a connection failed`() {
        assertEquals(
            Verdict.SAME_SUBNET,
            Reachability.verdictFor("10.0.0.153", listOf("10.0.0.87")),
        )
    }

    @Test
    fun `the two-band case is identified`() {
        // The computer on the 5GHz SSID, the phone on the 2.4GHz one, different
        // subnets. Everything typed was correct and it still cannot work.
        assertEquals(
            Verdict.DIFFERENT_SUBNET,
            Reachability.verdictFor("10.0.0.153", listOf("192.168.1.44")),
        )
    }

    @Test
    fun `a phone with several addresses only needs one that matches`() {
        // Wi-Fi plus a VPN, or Wi-Fi plus a tethering interface.
        assertEquals(
            Verdict.SAME_SUBNET,
            Reachability.verdictFor("192.168.1.10", listOf("10.8.0.2", "192.168.1.44")),
        )
    }

    @Test
    fun `mobile data alone is a different network`() {
        assertEquals(
            Verdict.DIFFERENT_SUBNET,
            Reachability.verdictFor("192.168.1.10", listOf("10.114.22.9")),
        )
    }

    @Test
    fun `nothing is claimed when nothing can be known`() {
        // No usable local address, or a target that is not a private LAN address —
        // a Tailscale or public one, where a subnet comparison means nothing.
        assertEquals(Verdict.UNKNOWN, Reachability.verdictFor("10.0.0.153", emptyList()))
        assertEquals(Verdict.UNKNOWN, Reachability.verdictFor("100.64.1.2", listOf("10.0.0.5")))
        assertEquals(Verdict.UNKNOWN, Reachability.verdictFor("anodex.local", listOf("10.0.0.5")))
    }

    @Test
    fun `malformed input never throws`() {
        // The address comes from a text field, so it can be anything at all.
        for (bad in listOf("", "...", "10.0.0", "10.0.0.999", "10.0.0.x", "999.1.1.1")) {
            assertEquals(Verdict.UNKNOWN, Reachability.verdictFor(bad, listOf("10.0.0.5")))
        }
    }

    @Test
    fun `the 172 private range is bounded correctly`() {
        // 172.16 to 172.31 is private; 172.15 and 172.32 are ordinary internet
        // addresses, and treating them as LAN would produce a confident wrong hint.
        assertEquals(
            Verdict.SAME_SUBNET,
            Reachability.verdictFor("172.16.5.1", listOf("172.16.5.2")),
        )
        assertEquals(Verdict.UNKNOWN, Reachability.verdictFor("172.15.5.1", listOf("10.0.0.5")))
        assertEquals(Verdict.UNKNOWN, Reachability.verdictFor("172.32.5.1", listOf("10.0.0.5")))
    }
}
