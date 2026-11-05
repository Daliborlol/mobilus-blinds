package dev.dalibor.blinds

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.dalibor.blinds.gateway.GatewayScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/**
 * Looks for gateways on the phone's Wi-Fi or Ethernet networks. It scans those networks directly
 * rather than the default one, so an active VPN doesn't send the search to the wrong place.
 */
suspend fun findGateways(context: Context): List<String> = withContext(Dispatchers.IO) {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    // Deprecated in favour of network callbacks, which are far more machinery for a one-off lookup.
    @Suppress("DEPRECATION")
    manager.allNetworks.flatMap { network ->
        val capabilities = manager.getNetworkCapabilities(network)
        val isLocal = capabilities != null &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        if (!isLocal) return@flatMap emptyList()
        val addresses = manager.getLinkProperties(network)?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
        val hosts = addresses.flatMap { GatewayScanner.hostsInSubnet(it.address.address, it.prefixLength) }
        GatewayScanner.scan(hosts, network.socketFactory)
    }.distinct()
}
