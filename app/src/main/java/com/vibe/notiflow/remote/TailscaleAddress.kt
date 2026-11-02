package com.vibe.notiflow.remote

import java.net.Inet4Address
import java.net.NetworkInterface

object TailscaleAddress {
    fun isTailnetIpv4(bytes: ByteArray): Boolean = bytes.size == 4 &&
        (bytes[0].toInt() and 255) == 100 && (bytes[1].toInt() and 255) in 64..127

    fun find(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && (it.name.startsWith("tun") || it.name.startsWith("tailscale")) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { isTailnetIpv4(it.address) }?.hostAddress
    }.getOrNull()
}
