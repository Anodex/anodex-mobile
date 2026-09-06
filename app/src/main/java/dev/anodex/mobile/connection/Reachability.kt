package dev.anodex.mobile.connection

import java.net.Inet4Address

/**
 * Whether an address is even plausibly reachable from where this phone is standing.
 *
 * Answers the question a failed connection cannot: *was this ever going to work?*
 * "Nothing answered at that address" is true whether the desktop is asleep, the
 * port is wrong, or the phone is simply on a different network — three problems
 * with three different remedies and one indistinguishable symptom.
 *
 * The case this exists for is mundane and common: a router publishing its 2.4GHz
 * and 5GHz bands as separate SSIDs, on separate subnets. The computer is on one,
 * the phone joins the other, and every field the user typed is correct.
 *
 * A same-subnet check is a heuristic, not a guarantee — routed networks exist, and
 * two devices on one subnet can still be isolated from each other. So this is only
 * ever used to *explain* a failure that already happened, never to refuse an
 * attempt before making it.
 */
object Reachability {

    /** How a target address relates to the network this phone is on. */
    enum class Verdict {
        /** Same /24. If it failed, the network is not the reason. */
        SAME_SUBNET,

        /** A private address on a different subnet — the likely cause of a failure. */
        DIFFERENT_SUBNET,

        /** Nothing useful can be said: no local address, or the target is not private. */
        UNKNOWN,
    }

    fun verdictFor(target: String, localAddresses: List<String>): Verdict {
        if (!isPrivateIPv4(target)) return Verdict.UNKNOWN
        val usable = localAddresses.filter { isPrivateIPv4(it) }
        if (usable.isEmpty()) return Verdict.UNKNOWN

        return if (usable.any { sameSubnet(it, target) }) {
            Verdict.SAME_SUBNET
        } else {
            Verdict.DIFFERENT_SUBNET
        }
    }

    /**
     * A /24 comparison rather than the real netmask.
     *
     * Home networks are /24 essentially without exception, and the phone cannot
     * see the *desktop's* mask in any case. Being wrong costs a slightly
     * misleading hint on a screen that already says the connection failed.
     */
    private fun sameSubnet(a: String, b: String): Boolean =
        a.substringBeforeLast('.') == b.substringBeforeLast('.')

    private fun isPrivateIPv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false

        return when (octets[0]) {
            10 -> true
            192 -> octets[1] == 168
            172 -> octets[1] in 16..31
            else -> false
        }
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
