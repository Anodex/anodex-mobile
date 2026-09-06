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
