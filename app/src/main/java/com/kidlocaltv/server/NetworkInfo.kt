package com.kidlocaltv.server

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInfo {
    /** Best guess at the TV's LAN IPv4 address (Ethernet or Wi-Fi), or null if offline. */
    fun localIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { iface ->
                when {
                    iface.name.startsWith("eth") -> 0
                    iface.name.startsWith("wlan") -> 1
                    else -> 2
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
