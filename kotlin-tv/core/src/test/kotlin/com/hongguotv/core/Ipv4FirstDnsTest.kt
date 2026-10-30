package com.hongguotv.core

import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test

class Ipv4FirstDnsTest {
    private class LocalServer(address: InetAddress): Closeable {
        private val listener=ServerSocket(0,1,address).apply { soTimeout=3000 }
        val port=listener.localPort
        private val failure=AtomicReference<Throwable?>()
        private val worker=Thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout=2000
                    val input=socket.getInputStream().bufferedReader()
                    while(true) { val line=input.readLine() ?: break; if(line.isEmpty()) break }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                }
            } catch(problem: Throwable) { failure.set(problem) }
        }.apply { isDaemon=true; start() }
        override fun close() { listener.close(); worker.join(4000) }
        fun checkServed() { worker.join(4000); assertFalse(worker.isAlive); failure.get()?.let { throw AssertionError(it) } }
    }

    private val ipv4=InetAddress.getByName("127.0.0.1")
    private val ipv6=InetAddress.getByName("::1")
    private fun ipv6Server(): LocalServer = try { LocalServer(ipv6) }
        catch(problem: Exception) { assumeNoException("IPv6 loopback is unavailable",problem); throw problem }

    private fun fetch(server: LocalServer,addresses: List<InetAddress>): List<InetAddress> {
        val attempts=mutableListOf<InetAddress>()
        val dns=Ipv4FirstDns(object: Dns {
            override fun lookup(hostname: String): List<InetAddress> { assertEquals("playback.test",hostname); return addresses }
        })
        val http=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(dns)
            .connectTimeout(1,TimeUnit.SECONDS).readTimeout(2,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS)
            .eventListener(object: EventListener() {
                override fun connectStart(call: Call,inetSocketAddress: InetSocketAddress,proxy: Proxy) { attempts+=inetSocketAddress.address }
            }).build()
        try {
            http.newCall(Request.Builder().url("http://playback.test:${server.port}/video").build()).execute().use { response ->
                assertEquals(200,response.code)
                assertEquals("ok",response.body!!.string())
            }
            server.checkServed()
            return attempts
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }
    }

    @Test fun dualStackStartsWithWorkingIpv4WithoutTryingBrokenIpv6() {
        LocalServer(ipv4).use { server ->
            val attempts=fetch(server,listOf(ipv6,ipv4))
            assertEquals(1,attempts.size)
            assertTrue(attempts.single() is Inet4Address)
        }
    }

    @Test fun refusedIpv4StillFallsBackToWorkingIpv6() {
        ipv6Server().use { server ->
            val attempts=fetch(server,listOf(ipv6,ipv4))
            assertEquals(listOf(ipv4,ipv6),attempts)
        }
    }

    @Test fun ipv6OnlyResolutionStillConnects() {
        ipv6Server().use { server -> assertEquals(listOf(ipv6),fetch(server,listOf(ipv6))) }
    }

    @Test fun resolverFailureIsPreservedWithoutSubstitutingAnotherDnsService() {
        val failure=UnknownHostException("unavailable")
        val dns=Ipv4FirstDns(object: Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw failure
        })
        assertSame(failure,assertThrows(UnknownHostException::class.java) { dns.lookup("playback.test") })
    }
}
