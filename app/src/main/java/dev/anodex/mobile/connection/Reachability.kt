package dev.anodex.mobile.connection

import java.net.Inet4Address

/**
 * Whether an address is even plausibly reachable from where this phone is standing.
 *
 * Answers the question a failed connection cannot: *was this ever going to work?*
 * "Nothing answered at that address" is true whether the computer is asleep, the
 * port is wrong, the phone is on the wrong Wi-Fi, or the phone is on mobile data
 * with no VPN — four problems with four different remedies and one
 * indistinguishable symptom.
 *
 * A heuristic, and used only to *explain* a failure that already happened — never
 * to refuse an attempt before making it. Routed networks exist, and two devices on
 * one subnet can still be isolated from each other.
 */
object Reachability {

    /** How a target address relates to where this phone is. */
    enum class Verdict {
        /** Same LAN. If it failed, the network is not the reason. */
        SAME_SUBNET,

        /**
         * Both ends are on the same mesh VPN.
         *
         * Reachable regardless of subnet: Tailscale and WireGuard peers route to
         * each other across the whole range, which is the entire point of them.
         */
        SAME_MESH,

        /**
         * The target is a mesh address but this phone is not on the mesh.
         *
         * The most actionable verdict there is, and the one that matters away from
         * home: the computer *can* be reached from anywhere, and the only missing
         * piece is the VPN on this device.
         */
        MESH_AVAILABLE_BUT_OFF,

        /**
         * Off the home network with no mesh address known at all.
         *
         * Mobile data, or someone else's Wi-Fi, and the desktop has never reported
         * a VPN address. Nothing the phone can do will reach it.
         */
        NO_ROUTE,

        /** A private address on a different subnet — the wrong-Wi-Fi case. */
        DIFFERENT_SUBNET,

        /** Nothing useful can be said. */
        UNKNOWN,
    }

    /**
     * @param target the address that was tried.
     * @param knownAddresses every address the desktop has said it can be reached at,
     *   so a phone that failed on the LAN can be told whether a VPN route exists.
     */
    fun verdictFor(
        target: String,
        localAddresses: List<String>,
        knownAddresses: List<String> = listOf(target),
    ): Verdict {
        val localMesh = localAddresses.any(::isMesh)
        val hostHasMesh = knownAddresses.any(::isMesh)

        if (isMesh(target)) {
            return if (localMesh) Verdict.SAME_MESH else Verdict.MESH_AVAILABLE_BUT_OFF
        }

        if (!isPrivateLan(target)) return Verdict.UNKNOWN

        val localLan = localAddresses.filter(::isPrivateLan)
        if (localLan.any { sameSubnet(it, target) }) return Verdict.SAME_SUBNET

        // Off the home network. Whether that is fixable depends entirely on whether
        // the desktop has a VPN address at all.
        return when {
            hostHasMesh && !localMesh -> Verdict.MESH_AVAILABLE_BUT_OFF
            hostHasMesh -> Verdict.SAME_MESH
            localLan.isEmpty() -> Verdict.NO_ROUTE
            else -> Verdict.DIFFERENT_SUBNET
        }
    }

    /**
     * The desktop's addresses, reordered for where this phone is standing right now.
     *
     * The desktop ranks them by how good they are in general — LAN first, then a
     * VPN, then the forwarded public address last, because that one is the slowest
     * and the only one that leaves the house. That ranking is right at home and
     * exactly wrong away from it: a LAN address cannot work from mobile data, and
     * trying it first spends the whole connect timeout discovering that on every
     * single reconnect.
     *
     * So the general ranking is kept as the tie-break, and addresses that cannot
     * work from here are moved behind the ones that can. Nothing is ever dropped —
     * this is a heuristic over a /24 guess, routed networks exist, and being wrong
     * should cost a few seconds rather than the ability to connect at all.
     */
    fun orderByPlausibility(
        addresses: List<String>,
        localAddresses: List<String>,
    ): List<String> =
        addresses.withIndex().sortedWith(
            compareBy(
                { cost(it.value, localAddresses) },
                // The desktop's own order, preserved among equally plausible addresses.
                { it.index },
            )
        ).map { it.value }

    /**
     * How likely one address is to work from here. Lower is tried sooner.
     *
     * Deliberately not built on [verdictFor]. That answers "why did this fail?" and
     * folds in what is known about the *host* — it will call a LAN address
     * `SAME_MESH` when the desktop happens to also have a VPN address, which is a
     * useful thing to tell a user and a wrong basis for deciding what to dial. This
     * judges the one address in front of it and nothing else.
     */
    private fun cost(target: String, localAddresses: List<String>): Int = when {
        // On the mesh at both ends: direct, and does not leave through the router.
        isMesh(target) -> if (localAddresses.any(::isMesh)) 1 else 3

        // A LAN address only works from that LAN. From anywhere else it is the most
        // expensive thing in the list, because it fails by timing out rather than by
        // being refused.
        isPrivateLan(target) ->
            if (localAddresses.filter(::isPrivateLan).any { sameSubnet(it, target) }) 0 else 4

        // A public address. Slower than a local route and it works from everywhere,
        // so it sits between "known good here" and "known not to route from here".
        else -> 2
    }

    /**
     * Whether an address only works from inside the house.
     *
     * True for a LAN address and for a mesh VPN address — both need this phone to
     * already be on the right network. False for a forwarded public address, which
     * is the only kind that works from mobile data with nothing else set up.
     */
    fun isLocalRoute(address: String): Boolean = isPrivateLan(address) || isMesh(address)

    /**
     * Tailscale and friends allocate from 100.64.0.0/10, the CGNAT range.
     *
     * Peers on it reach each other across the whole range, so a /24 comparison is
     * meaningless here — and applying one anyway would report "different network"
     * for the exact configuration that makes working away from home possible.
     */
    private fun isMesh(address: String): Boolean {
        val octets = octetsOf(address) ?: return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    /**
     * A /24 comparison rather than the real netmask.
     *
     * Home networks are /24 essentially without exception, and the phone cannot see
     * the desktop's mask in any case. Being wrong costs a slightly misleading hint
     * on a screen that already says the connection failed.
     */
    private fun sameSubnet(a: String, b: String): Boolean =
        a.substringBeforeLast('.') == b.substringBeforeLast('.')

    private fun isPrivateLan(address: String): Boolean {
        val octets = octetsOf(address) ?: return false
        return when (octets[0]) {
            10 -> true
            192 -> octets[1] == 168
            172 -> octets[1] in 16..31
            else -> false
        }
    }

    private fun octetsOf(address: String): List<Int>? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return if (octets.any { it !in 0..255 }) null else octets
    }
}

/** Every IPv4 address this phone currently holds, for [Reachability.verdictFor]. */
fun localIPv4Addresses(): List<String> = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress ?: "" }
        .filter { it.isNotEmpty() }
        .toList()
}.getOrDefault(emptyList())
