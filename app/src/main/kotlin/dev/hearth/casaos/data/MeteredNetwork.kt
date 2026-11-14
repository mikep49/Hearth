package dev.hearth.casaos.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What [isMetered] needs to know about one of the phone's networks. */
data class NetworkLink(val vpn: Boolean, val unmetered: Boolean, val working: Boolean)

/**
 * Whether the phone pays by the byte on [current], its default network: mobile data, or a Wi-Fi network Android
 * counts as metered, like a phone's hotspot. Through a VPN such as Tailscale it's the network underneath that
 * counts, since a VPN can call itself metered whatever it runs over; that's any working network among [others]
 * that isn't a VPN. With no network at all, it's taken as metered.
 */
fun isMetered(current: NetworkLink?, others: List<NetworkLink>): Boolean {
    if (current == null) return true
    if (!current.vpn) return !current.unmetered
    val under = others.filter { !it.vpn && it.working }
    // Android sends traffic over Wi-Fi when it can, so one unmetered network underneath is enough.
    return if (under.isEmpty()) !current.unmetered else under.none { it.unmetered }
}

/** Follows whether the phone is on a metered network (see [isMetered]), for thumbnails to go easy on data. */
class MeteredNetwork(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _metered = MutableStateFlow(check())
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    init {
        // One callback for the default network (which may be a VPN), and one for every network that isn't a VPN
        // (what a plain request covers), since the network under a VPN can change while the VPN stays.
        runCatching { cm?.registerDefaultNetworkCallback(Watcher()) }
        runCatching { cm?.registerNetworkCallback(NetworkRequest.Builder().build(), Watcher()) }
    }

    private inner class Watcher : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
        override fun onLost(network: Network) = update()
    }

    private fun update() {
        _metered.value = check()
    }

    @Suppress("DEPRECATION") // allNetworks: the networks under a VPN, which the default network hides
    private fun check(): Boolean {
        val cm = cm ?: return false
        return runCatching {
            val current = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.link()
            isMetered(current, cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it)?.link() })
        }.getOrDefault(true)
    }

    private fun NetworkCapabilities.link() = NetworkLink(
        vpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        unmetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED),
        working = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )
}
