// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns

/** Avoid serial IPv6 failures on TV networks while retaining IPv6-only and fallback routes. */
class Ipv4FirstDns(private val resolver: Dns = Dns.SYSTEM): Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        resolver.lookup(hostname).sortedBy { if(it is Inet4Address) 0 else 1 }
}
