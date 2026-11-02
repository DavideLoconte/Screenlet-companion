package com.davideloconte.screenlet

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Frames and transmits length-delimited JSON control messages across a stream socket.
 */
class Wire(private val socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    /** Serializes and transmits a JSON message header guarded by a watchdog write timeout. */
    fun send(header: JSONObject) = synchronized(output) {
        val bytes = header.toString().toByteArray()
        val guard = WATCHDOG.schedule({ socket.close() }, WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
        try {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        } finally {
            guard.cancel(false)
        }
    }

    /** Reads and parses the next length-prefixed JSON message header from the stream. */
    fun receive(maxHeader: Int = MAX_HEADER): JSONObject {
        val length = input.readInt()
        require(length in 1..maxHeader) { "bad header length $length" }
        return JSONObject(String(ByteArray(length).also { input.readFully(it) }))
    }

    companion object {
        const val MAX_HEADER = 16 * 1024 * 1024       // PROTOCOL.md: after pairing
        const val UNPAIRED_HEADER = 4 * 1024          // hello and pairing: small
        const val WRITE_TIMEOUT = 30_000L             // ms for a message to go out

        /** Scheduled executor enforcing timeout deadlines on stream socket operations. */
        val WATCHDOG = Executors.newSingleThreadScheduledExecutor { Thread(it, "screenlet-watchdog").apply { isDaemon = true } }
    }
}
