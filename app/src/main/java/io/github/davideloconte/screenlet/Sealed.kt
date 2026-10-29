package io.github.davideloconte.screenlet

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Packets sealed with AES-GCM under a session's key, which the PC handed over
 * the paired TLS link: nonce (12 bytes: direction u32, count u64) | sealed body.
 * Nothing is allocated per packet but the GCM parameters: both directions
 * write into buffers the caller keeps.
 */
class Sealed(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private val opener = Cipher.getInstance("AES/GCM/NoPadding")
    private val sealer = Cipher.getInstance("AES/GCM/NoPadding")
    private var sent = 0L
    private val replay = Replay()

    /** Opens packet[0, length) from the PC into out: the body's length, or
     * -1 if it is not one sealed with this key, or a replay. Only ever from
     * one thread at a time (the socket's or the cable's). */
    @Synchronized
    fun open(packet: ByteArray, length: Int, out: ByteArray): Int {
        if (length < NONCE + TAG + 1 || length - NONCE - TAG > out.size || readInt(packet, 0) != FROM_PC) return -1
        val count = readLong(packet, 4)
        if (!replay.fresh(count)) return -1
        return try {
            opener.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, packet, 0, NONCE))
            opener.doFinal(packet, NONCE, length - NONCE, out, 0).also { replay.accept(count) }
        } catch (e: Exception) {
            -1
        }
    }

    /** body[0, length) sealed into out: the packet's length. */
    @Synchronized
    fun seal(body: ByteArray, length: Int, out: ByteArray): Int {
        writeInt(out, 0, FROM_APP)
        writeLong(out, 4, sent++)
        sealer.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, out, 0, NONCE))
        return NONCE + sealer.doFinal(body, 0, length, out, NONCE)
    }

    /** The counts accepted: the highest, and a window of 64 below it, so a
     * packet Wi-Fi delivered a little late is taken, one heard before never. */
    class Replay {
        private var highest = -1L
        private var window = 0L  // bit i: highest - i was accepted

        fun fresh(count: Long): Boolean = when {
            count > highest -> true
            highest - count >= 64 -> false
            else -> window and (1L shl (highest - count).toInt()) == 0L
        }

        fun accept(count: Long) {
            if (count > highest) {
                val shift = count - highest
                window = if (shift >= 64) 1L else (window shl shift.toInt()) or 1L
                highest = count
            } else {
                window = window or (1L shl (highest - count).toInt())
            }
        }
    }

    companion object {
        const val FROM_PC = 1
        const val FROM_APP = 2
        const val NONCE = 12
        const val TAG = 16

        fun readInt(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

        fun readLong(b: ByteArray, at: Int) = (readInt(b, at).toLong() shl 32) or (readInt(b, at + 4).toLong() and 0xffffffffL)

        fun writeInt(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
        }

        fun writeLong(b: ByteArray, at: Int, v: Long) {
            writeInt(b, at, (v ushr 32).toInt())
            writeInt(b, at + 4, v.toInt())
        }
    }
}
