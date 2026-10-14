package com.portal.calendar

/** What Kotlin hands back to the Rust server for one request. Read by name from JNI. */
class NativeResponse(
    @JvmField val status: Int,
    @JvmField val contentType: String,
    @JvmField val body: ByteArray
)

/** Called by the Rust server, on its own worker threads, for each request that passed the gate. */
interface NativeHandler {
    fun handle(method: String, path: String, query: String, body: ByteArray): NativeResponse
}

object PortalCore {
    init { System.loadLibrary("portal_core") }

    /** Throws IllegalStateException if the port is taken or the token is too short. */
    @JvmStatic external fun start(addr: String, token: String, handler: NativeHandler): Long
    @JvmStatic external fun stop(handle: Long)

    /** Parses an .ics feed natively; returns JSON (see IcsEvents). */
    @JvmStatic external fun parseIcs(text: String, windowStartMs: Long, windowEndMs: Long, deviceTz: String): String
}
