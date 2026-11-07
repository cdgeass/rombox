package com.cdgeass.rombox.core.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** 局域网 IPv4，优先 wlan 网卡 */
    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .sortedByDescending { it.name.startsWith("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /** 供 UI 展示的访问地址 */
    fun urls(port: Int): List<String> {
        val ip = localIpv4() ?: return listOf("http://127.0.0.1:$port")
        return listOf("http://$ip:$port")
    }
}
