package com.davideloconte.screenlet

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts datagram packets using AES-128-GCM authentication.
 *
 * Each encrypted packet includes a 12-byte nonce containing stream direction and sequence number,
 * followed by ciphertext and a 16-byte authentication tag. Maintains replay protection state
 * via a 64-packet sliding window filter.
 */
class Sealed(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private val opener = Cipher.getInstance("AES/GCM/NoPadding")
    private val sealer = Cipher.getInstance("AES/GCM/NoPadding")
    private var sent = 0L
    private val replay = Replay()

    /** Authenticates and decrypts an incoming packet buffer, verifying against the replay filter. */
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

    /** Encrypts and authenticates an outgoing payload into the destination buffer. */
    @Synchronized
    fun seal(body: ByteArray, length: Int, out: ByteArray): Int {
        writeInt(out, 0, FROM_APP)
        writeLong(out, 4, sent++)
        sealer.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, out, 0, NONCE))
        return NONCE + sealer.doFinal(body, 0, length, out, NONCE)
    }

    /** Sliding-window replay protection filter tracking sequence numbers across a 64-packet history. */
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
