package com.davideloconte.screenlet

/**
 * Frames put back together from VIDEO bodies, in place: each packet's video
 * bytes are copied once, straight to where they go in the frame. Every packet
 * of a frame but its last carries the same number of bytes (PROTOCOL.md §3.3:
 * 16338 in large packets, 1426 in small ones), so a packet's place is its
 * index times that; a last packet that comes before any other waits aside.
 * A few frames may be on the way at once (Wi-Fi reorders); when one is whole,
 * those older still missing a packet are lost. Plain Kotlin: tested on the JVM.
 *
 * VIDEO body: kind u8 | flags u8 (1: keyframe) | frame u32 | index u16 | count u16 | captured i64 | video
 */
class Frames(private val clock: () -> Long = System::nanoTime) {
    class Frame {
        var number = -1L
        var count = 0
        var have = 0
        var keyframe = false
        var captured = 0L
        var first = 0L     // ns, its first packet
        var complete = 0L  // ns, its last
        var size = 0
        var data = ByteArray(256 * 1024)
        var open = false   // being put together
        internal var unit = 0       // bytes of each packet but the last; 0 until one came
        internal var got = BooleanArray(64)
        internal var last = ByteArray(0)  // the last packet, while unit is not known
        internal var lastSize = -1

        internal fun reset(number: Long, count: Int, keyframe: Boolean, captured: Long, now: Long) {
            this.number = number
            open = true
            this.count = count
            this.keyframe = keyframe
            this.captured = captured
            have = 0
            size = 0
            unit = 0
            lastSize = -1
            first = now
            if (got.size < count) got = BooleanArray(count) else java.util.Arrays.fill(got, 0, count, false)
        }

        internal fun room(bytes: Int) {
            if (data.size < bytes) data = ByteArray(bytes)
        }
    }

    private val slots = Array(SLOTS) { Frame() }
    private var newest = -1L  // the newest frame handed out whole
    /** Frames lost since this was last read and reset. */
    var lost = 0

    /** A VIDEO body (from its kind byte, length bytes): the frame it
     * completed, valid until the next call, or null. */
    fun add(body: ByteArray, length: Int): Frame? {
        if (length < HEADER) return null
        val keyframe = body[1].toInt() and 1 != 0
        val number = Sealed.readInt(body, 2).toLong() and 0xffffffffL
        val index = ((body[6].toInt() and 0xff) shl 8) or (body[7].toInt() and 0xff)
        val count = ((body[8].toInt() and 0xff) shl 8) or (body[9].toInt() and 0xff)
        val bytes = length - HEADER
        if (number <= newest || count == 0 || index >= count || bytes > MAX_PAYLOAD) return null
        val frame = slots[(number % SLOTS).toInt()]
        if (!frame.open || frame.number != number) {
            if (frame.open) lost++  // overtaken by one SLOTS newer
            frame.reset(number, count, keyframe, Sealed.readLong(body, 10), clock())
        }
        if (frame.count != count || frame.got[index]) return null
        val isLast = index == count - 1
        if (!isLast) {
            if (frame.unit == 0) {
                if (bytes == 0) return null
                frame.unit = bytes
                frame.room(count * bytes)
                if (frame.lastSize >= 0) {  // the last one came first: in its place now
                    System.arraycopy(frame.last, 0, frame.data, (count - 1) * bytes, frame.lastSize)
                }
            } else if (bytes != frame.unit) {
                return null
            }
            System.arraycopy(body, HEADER, frame.data, index * frame.unit, bytes)
        } else {
            frame.lastSize = bytes
            if (count == 1 || frame.unit != 0) {
                frame.room((count - 1) * frame.unit + bytes)
                System.arraycopy(body, HEADER, frame.data, (count - 1) * frame.unit, bytes)
            } else {
                if (frame.last.size < bytes) frame.last = ByteArray(MAX_PAYLOAD)
                System.arraycopy(body, HEADER, frame.last, 0, bytes)
            }
        }
        frame.got[index] = true
        if (++frame.have < count) return null
        frame.size = (count - 1) * frame.unit + frame.lastSize
        frame.complete = clock()
        for (other in slots) if (other.open && other.number < number) {
            lost++
            other.open = false
        }
        newest = number
        frame.open = false  // its slot is free again once the caller has the bytes
        return frame
    }

    companion object {
        const val HEADER = 18
        const val MAX_PAYLOAD = 65536 - HEADER
        const val SLOTS = 8
    }
}
