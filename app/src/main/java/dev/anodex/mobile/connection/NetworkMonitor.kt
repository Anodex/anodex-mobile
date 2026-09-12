package dev.anodex.mobile.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Answers one question: is the phone on the network it paired on?
 *
 * That question explains most unreachable desktops. Anodex only connects over the local network,
 * so a user who has walked onto mobile data or a guest SSID has a specific, fixable problem — and
 * saying so turns a generic "can't connect" into something they can act on.
 *
 * **What this deliberately does not do is name the network.** Reading the current SSID on Android
 * requires `ACCESS_FINE_LOCATION`; without it `getSSID()` returns `<unknown ssid>`. That is a
 * location prompt on an app that otherwise asks for nothing, which users find alarming and a store
 * reviewer will ask about. Knowing *that* the network changed is what sends someone to check their
 * Wi-Fi, and that is nearly all of the value at none of the cost (handoff §6.1).
 *
 * The identity used instead is derived from the link's own routing properties, which need no
 * permission. It is only ever compared for equality — never displayed, never sent anywhere.
 */
class NetworkMonitor(context: Context) {

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /**
     * How the current network relates to [pairedNetworkId].
     *
     * Emits on every change, so an offline screen updates under the user when they walk back onto
     * the right Wi-Fi rather than waiting for the next failed attempt to notice.
     */
    fun relationTo(pairedNetworkId: String?): Flow<NetworkRelation> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(classify(network, pairedNetworkId))
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                // A network can change identity without going down — a DHCP lease renewal onto a
                // different subnet, or a VPN coming up — so re-classify rather than assume the
                // answer from onAvailable still holds.
                trySend(classify(network, pairedNetworkId))
            }

            override fun onLost(network: Network) {
                trySend(NetworkRelation.NONE)
            }
        }

        trySend(currentRelation(pairedNetworkId))
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** A one-shot read, for the moment a connection attempt fails and needs explaining. */
    fun currentRelation(pairedNetworkId: String?): NetworkRelation {
        val active = connectivity.activeNetwork ?: return NetworkRelation.NONE
        return classify(active, pairedNetworkId)
    }

    /**
     * A stable identifier for the network currently in use, to be stored at pairing.
     *
     * Null when there is nothing usable to identify, in which case the app simply declines to
     * offer a network explanation later — better than a confident wrong one.
     */
    /**
     * Whether the connection in use charges by the byte.
     *
     * Android's own answer rather than "is it cellular": a tethered laptop and a
     * metered home broadband plan both report metered while being Wi-Fi, and an
     * unmetered corporate SIM reports the reverse. Asking the capability gets the
     * question right in all four cases; asking the transport gets it right in two.
     *
     * Unknown networks count as metered. Being wrong in that direction costs someone
     * a slightly less live screen; being wrong in the other costs them money.
     */
    fun onMeteredNetwork(): Boolean {
        val capabilities = connectivity.activeNetwork
            ?.let(connectivity::getNetworkCapabilities)
            ?: return true

        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Emits whenever that answer changes — walking out of the house, and back in. */
    fun meteredChanges(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(onMeteredNetwork())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                trySend(onMeteredNetwork())
            }

            override fun onLost(network: Network) {
                trySend(onMeteredNetwork())
            }
        }

        trySend(onMeteredNetwork())
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    fun currentNetworkId(): String? =
        connectivity.activeNetwork?.let(::identify)

    private fun classify(network: Network, pairedNetworkId: String?): NetworkRelation {
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return NetworkRelation.NONE

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkRelation.NONE
        }

        // Cellular can never reach a LAN-only desktop, so it is a changed network by definition —
        // no identity comparison needed, and this is the single most common real cause.
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return if (pairedNetworkId == null) NetworkRelation.UNKNOWN else NetworkRelation.CHANGED
        }

        val current = identify(network) ?: return NetworkRelation.UNKNOWN
        if (pairedNetworkId == null) return NetworkRelation.UNKNOWN

        return if (current == pairedNetworkId) NetworkRelation.SAME else NetworkRelation.CHANGED
    }

    /**
     * Identify a network by its routing shape rather than its name.
     *
     * The gateway and the interface together are a good proxy for "the same LAN": two different
     * Wi-Fi networks essentially never share both. It can be fooled — two routers each handing out
     * 192.168.1.1 on `wlan0` look alike — but the cost of that is only a missing explanation on the
     * offline screen, never a wrong connection, because the certificate pin (§7.2) is what actually
     * decides who we are willing to talk to.
     */
    private fun identify(network: Network): String? {
        val properties = connectivity.getLinkProperties(network) ?: return null
        val gateway = properties.routes
            .firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress
            ?: return null

        return "${properties.interfaceName.orEmpty()}@$gateway"
    }
}
