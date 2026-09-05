package com.slukhayka.audiobooks.data.privacy

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BrowserDnsProxyTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun readHeader(socket: Socket): String {
        val result = StringBuilder()
        while (!result.endsWith("\r\n\r\n")) {
            val byte = socket.getInputStream().read()
            check(byte >= 0)
            result.append(byte.toChar())
        }
        return result.toString()
    }

    @Test
    fun `CONNECT resolves with app DNS and preserves opaque bytes`() {
        ServerSocket(0, 1, loopback).use { origin ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> {
                    origin.accept().use { remote ->
                        remote.soTimeout = 3_000
                        val bytes = remote.getInputStream().readNBytes(4)
                        remote.getOutputStream().write("pong".toByteArray())
                        String(bytes)
                    }
                }
                val lookedUp = mutableListOf<String>()
                BrowserDnsProxy(resolve = { lookedUp += it; listOf(loopback) }, directAllowed = { true }).use { proxy ->
                    Socket(loopback, URI(proxy.proxyRule).port).use { client ->
                        client.soTimeout = 3_000
                        client.getOutputStream().write("CONNECT audio.invalid:${origin.localPort} HTTP/1.1\r\nHost: audio.invalid\r\n\r\n".toByteArray())
                        assertTrue(readHeader(client).startsWith("HTTP/1.1 200"))
                        client.getOutputStream().write("ping".toByteArray())
                        assertEquals("pong", String(client.getInputStream().readNBytes(4)))
                    }
                }
                assertEquals("ping", received.get(3, TimeUnit.SECONDS))
                assertEquals(listOf("audio.invalid"), lookedUp)
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun `explicit non-direct route refuses before DNS or connection`() {
        BrowserDnsProxy(resolve = { error("must not resolve outside the chosen route") }, directAllowed = { false }).use { proxy ->
            Socket(loopback, URI(proxy.proxyRule).port).use { client ->
                client.soTimeout = 3_000
                client.getOutputStream().write("CONNECT audio.invalid:443 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(readHeader(client).startsWith("HTTP/1.1 502"))
            }
        }
    }

    @Test
    fun `failed DNS returns failure rather than claiming a tunnel`() {
        BrowserDnsProxy(resolve = { throw UnknownHostException(it) }, directAllowed = { true }).use { proxy ->
            Socket(loopback, URI(proxy.proxyRule).port).use { client ->
                client.soTimeout = 3_000
                client.getOutputStream().write("CONNECT audio.invalid:443 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(readHeader(client).startsWith("HTTP/1.1 502"))
            }
        }
    }

    @Test
    fun `plain HTTP keeps path query and host through app DNS`() {
        ServerSocket(0, 1, loopback).use { origin ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<String> {
                    origin.accept().use { remote ->
                        remote.soTimeout = 3_000
                        val header = readHeader(remote)
                        remote.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        header
                    }
                }
                BrowserDnsProxy(resolve = { listOf(loopback) }, directAllowed = { true }).use { proxy ->
                    Socket(loopback, URI(proxy.proxyRule).port).use { client ->
                        client.soTimeout = 3_000
                        client.getOutputStream().write("GET http://page.invalid:${origin.localPort}/book?a=1 HTTP/1.1\r\nHost: page.invalid:${origin.localPort}\r\n\r\n".toByteArray())
                        assertTrue(readHeader(client).startsWith("HTTP/1.1 200"))
                    }
                }
                val header = received.get(3, TimeUnit.SECONDS)
                assertTrue(header.startsWith("GET /book?a=1 HTTP/1.1\r\n"))
                assertTrue(header.contains("Host: page.invalid:${origin.localPort}\r\n"))
            } finally { executor.shutdownNow() }
        }
    }
}
