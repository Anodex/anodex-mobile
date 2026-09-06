package dev.anodex.mobile.connection

import dev.anodex.mobile.connection.Reachability.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Explaining a failed connection.
 *
 * "Nothing answered" is true whether the computer is asleep, the port is wrong,
 * the phone is on the wrong Wi-Fi, or the phone is on mobile data with no VPN.
 * Four problems, four different remedies, one indistinguishable symptom — and
 * three of the four are things the phone can work out for itself.
 */
class ReachabilityTest {

    private val homeLan = listOf("10.0.0.87")
    private val cellular = listOf("10.114.22.9")
    private val onTailscale = listOf("100.101.102.103")

    @Test
    fun `a phone on the same LAN is not the reason a connection failed`() {
        assertEquals(
            Verdict.SAME_SUBNET,
            Reachability.verdictFor("10.0.0.153", homeLan),
        )
    }

    @Test
    fun `the two-band case is identified`() {
        // The computer on the 5GHz SSID, the phone on the 2.4GHz one. Everything
        // typed was correct and it still cannot work.
        assertEquals(
            Verdict.DIFFERENT_SUBNET,
            Reachability.verdictFor("10.0.0.153", listOf("192.168.1.44")),
        )
    }

    // --- the mesh cases, which are the whole point of working away from home -----

    @Test
    fun `two Tailscale peers reach each other despite different subnets`() {
        // The bug this replaced: a /24 comparison called 100.90.80.70 and
        // 100.101.102.103 different networks, which is exactly backwards — the CGNAT
        // range routes across itself, and that is what makes it useful.
        assertEquals(
            Verdict.SAME_MESH,
            Reachability.verdictFor("100.90.80.70", onTailscale),
        )
    }

    @Test
    fun `a mesh address with the VPN off is the actionable case`() {
        // The computer *can* be reached from anywhere. The only missing piece is on
        // this device, which makes it the one failure worth a specific instruction.
        assertEquals(
            Verdict.MESH_AVAILABLE_BUT_OFF,
            Reachability.verdictFor("100.90.80.70", cellular),
        )
    }

    @Test
    fun `failing on the LAN away from home points at the VPN when one exists`() {
        // The phone tried the LAN address, failed, and the desktop has also reported
        // a mesh address. Telling the user to switch Wi-Fi would be wrong: they are
        // on mobile data, and the fix is to turn the VPN on.
        assertEquals(
            Verdict.MESH_AVAILABLE_BUT_OFF,
            Reachability.verdictFor(
                target = "10.0.0.153",
                localAddresses = cellular,
                knownAddresses = listOf("10.0.0.153", "100.90.80.70"),
            ),
        )
    }

    @Test
    fun `mobile data with no VPN anywhere has no route at all`() {
        // Nothing the phone can do reaches the computer, and saying "wrong network"
        // would imply switching Wi-Fi would help.
        assertEquals(
            Verdict.NO_ROUTE,
            Reachability.verdictFor(
                target = "10.0.0.153",
                localAddresses = emptyList(),
                knownAddresses = listOf("10.0.0.153"),
            ),
        )
    }

    @Test
    fun `a phone already on the mesh is told the network is not the problem`() {
        assertEquals(
            Verdict.SAME_MESH,
            Reachability.verdictFor(
                target = "10.0.0.153",
                localAddresses = onTailscale,
                knownAddresses = listOf("10.0.0.153", "100.90.80.70"),
            ),
        )
    }

    // --- boundaries and rubbish input --------------------------------------------

    @Test
    fun `the CGNAT range is bounded correctly`() {
        // 100.63 and 100.128 are ordinary public addresses. Treating them as mesh
        // would promise a route that does not exist.
        assertEquals(Verdict.SAME_MESH, Reachability.verdictFor("100.64.0.1", onTailscale))
        assertEquals(Verdict.SAME_MESH, Reachability.verdictFor("100.127.255.254", onTailscale))
        assertEquals(Verdict.UNKNOWN, Reachability.verdictFor("100.63.0.1", onTailscale))
        assertEquals(Verdict.UNKNOWN, Reachability.verdictFor("100.128.0.1", onTailscale))
    }

    @Test
    fun `the 172 private range is bounded correctly`() {
        assertEquals(Verdict.SAME_SUBNET, Reachability.verdictFor("172.16.5.1", listOf("172.16.5.2")))
        assertEquals(Verdict.UNKNOWN, Reachability.verdictFor("172.15.5.1", homeLan))
        assertEquals(Verdict.UNKNOWN, Reachability.verdictFor("172.32.5.1", homeLan))
    }

    @Test
    fun `a phone with several addresses only needs one that matches`() {
        assertEquals(
            Verdict.SAME_SUBNET,
            Reachability.verdictFor("192.168.1.10", listOf("10.8.0.2", "192.168.1.44")),
        )
    }

    @Test
    fun `malformed input never throws`() {
        for (bad in listOf("", "...", "10.0.0", "10.0.0.999", "10.0.0.x", "999.1.1.1")) {
            assertEquals(Verdict.UNKNOWN, Reachability.verdictFor(bad, homeLan))
        }
    }
}
