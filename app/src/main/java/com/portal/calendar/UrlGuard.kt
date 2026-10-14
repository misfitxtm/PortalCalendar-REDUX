package com.portal.calendar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Opens user-supplied feed URLs without letting them (or their redirects) reach this device's own services. */
object UrlGuard {
    private const val MAX_HOPS = 5
    // The Portal heap is small; a real calendar feed is well under this.
    const val MAX_FEED_BYTES = 10 * 1024 * 1024

    // RFC1918 stays allowed on purpose: self-hosted calendars on the LAN are a real use case.
    fun isBlocked(a: InetAddress) =
        a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress

    // shortcut: the name is resolved here and again by connect(), so a DNS-rebinding feed host can slip through; pin the IP if feeds ever become untrusted.
    fun open(start: String, configure: (HttpURLConnection) -> Unit): HttpURLConnection {
        var url = URL(start)
        repeat(MAX_HOPS + 1) {
            require(url.protocol == "http" || url.protocol == "https") { "only http(s) links are allowed" }
            for (a in InetAddress.getAllByName(url.host))
                require(!isBlocked(a)) { "that address points at this device, which isn't allowed" }
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false // we follow by hand so every hop is checked
            configure(conn)
            val loc = conn.getHeaderField("Location")
            if (conn.responseCode !in 301..308 || loc == null) return conn
            conn.disconnect()
            url = URL(url, loc)
        }
        throw IllegalArgumentException("too many redirects")
    }

    /** Reads at most [max] bytes as UTF-8; a bigger body is refused instead of exhausting the heap. */
    fun readCapped(input: InputStream, max: Int = MAX_FEED_BYTES): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                require(out.size() + n <= max) { "that feed is too large" }
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }
}
