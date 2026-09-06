package dev.anodex.mobile.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order the phone dials the desktop's addresses in.
 *
 * This decides how long it takes to connect, and the cost of getting it wrong is
 * paid on every single reconnect rather than once: each address that cannot work
 * from where the phone is standing burns the full connect timeout before the next
 * one is tried. Away from home that is the difference between a chat that opens
 * and one that looks broken.
 *
 * The desktop's own ranking — LAN, then VPN, then the forwarded public address —
 * is right at home and exactly backwards on mobile data, which is what these pin.
 */
class AddressOrderTest {

    private val lan = "192.168.1.40"
    private val mesh = "100.101.102.103"
    private val public = "203.0.113.7"

    /** The desktop always reports them best-in-general first. */
    private val reported = listOf(lan, mesh, public)

    @Test
    fun `at home the LAN address is tried first`() {
        val order = Reachability.orderByPlausibility(reported, localAddresses = listOf("192.168.1.77"))

        assertEquals(lan, order.first())
    }

    @Test
    fun `on mobile data the LAN address is not tried first`() {
        // The phone has only a carrier address. Dialling 192.168.1.40 from there
        // cannot work, and doing it first is the whole connect timeout wasted before
        // the address that would have worked is even attempted.
        val order = Reachability.orderByPlausibility(reported, localAddresses = listOf("10.212.44.9"))

        assertEquals(public, order.first())
    }

    @Test
    fun `on someone else's wifi the public address wins over the home LAN`() {
        val order = Reachability.orderByPlausibility(reported, localAddresses = listOf("192.168.4.22"))

        assertEquals(public, order.first())
    }

    @Test
    fun `a VPN that is actually on beats the public address`() {
        // Both work from here; the mesh route is direct and does not go out to the
        // router and back.
        val order = Reachability.orderByPlausibility(
            reported,
            localAddresses = listOf("10.212.44.9", "100.64.7.7"),
        )

        assertEquals(mesh, order.first())
    }

    @Test
    fun `nothing is ever dropped`() {
        // These are heuristics over a /24 guess. Refusing to try an address because
        // it looks unreachable would turn a slightly-slow connection into no
        // connection at all on any routed network.
        val order = Reachability.orderByPlausibility(reported, localAddresses = listOf("10.212.44.9"))

        assertEquals(reported.toSet(), order.toSet())
        assertEquals(reported.size, order.size)
    }

    @Test
    fun `the desktop's order is kept between equally plausible addresses`() {
        // Two LAN addresses on this subnet: nothing here knows better than the
        // desktop which of them to prefer, so its ranking must survive.
        val two = listOf("192.168.1.40", "192.168.1.41")

        val order = Reachability.orderByPlausibility(two, localAddresses = listOf("192.168.1.77"))

        assertEquals(two, order)
    }

    @Test
    fun `an empty list stays empty rather than throwing`() {
        assertEquals(emptyList<String>(), Reachability.orderByPlausibility(emptyList(), listOf("192.168.1.77")))
    }

    @Test
    fun `with no local address readable the public one is still tried first`() {
        // An interface list the phone could not read. Nothing local is known to be
        // on either the home LAN or the mesh, so the address that works from
        // everywhere is the only defensible first guess.
        val order = Reachability.orderByPlausibility(reported, localAddresses = emptyList())

        assertEquals(public, order.first())
    }
}
