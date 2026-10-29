package io.github.davideloconte.screenlet

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Messages between the PC and the app: a 4-byte big-endian length, then a
 * JSON object of that length.
 */
class Wire(private val socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    /** Sends header; a peer that stops reading gets the connection closed
     * (a socket write has no timeout of its own, and it would hold this
     * Wire, and whoever waits to answer on it, for ever). */
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

    /** The next message, at most maxHeader bytes. */
    fun receive(maxHeader: Int = MAX_HEADER): JSONObject {
        val length = input.readInt()
        require(length in 1..maxHeader) { "bad header length $length" }
        return JSONObject(String(ByteArray(length).also { input.readFully(it) }))
    }

    companion object {
        const val MAX_HEADER = 64 * 1024
        const val UNPAIRED_HEADER = 4 * 1024          // hello and pairing: small
        const val WRITE_TIMEOUT = 30_000L             // ms for a message to go out

        /** Closes sockets whose time is up (writes, handshakes). */
        val WATCHDOG = Executors.newSingleThreadScheduledExecutor { Thread(it, "screenlet-watchdog").apply { isDaemon = true } }
    }
}
