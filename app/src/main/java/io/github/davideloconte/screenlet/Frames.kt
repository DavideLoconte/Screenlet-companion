package io.github.davideloconte.screenlet

/**
 * Frames put back together from VIDEO bodies, in place: each packet's video
 * bytes are copied once, straight to where they go in the frame (every
 * packet but a frame's last carries exactly PAYLOAD bytes). A few frames may
 * be on the way at once (Wi-Fi reorders); when one is whole, those older
 * still missing a packet are lost. Plain Kotlin: tested on the JVM.
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
        var got = BooleanArray(256)
        var open = false   // being put together

        internal fun reset(number: Long, count: Int, keyframe: Boolean, captured: Long, now: Long) {
            this.number = number
            open = true
            this.count = count
            this.keyframe = keyframe
            this.captured = captured
            have = 0
            size = 0
            first = now
            if (got.size < count) got = BooleanArray(count) else java.util.Arrays.fill(got, 0, count, false)
            if (data.size < count * PAYLOAD) data = ByteArray(count * PAYLOAD)
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
        if (number <= newest || count == 0 || index >= count || bytes > PAYLOAD) return null
        if (index < count - 1 && bytes != PAYLOAD) return null
        val frame = slots[(number % SLOTS).toInt()]
        if (!frame.open || frame.number != number) {
            if (frame.open) lost++  // overtaken by one SLOTS newer
            frame.reset(number, count, keyframe, Sealed.readLong(body, 10), clock())
        }
        if (frame.count != count || frame.got[index]) return null
        frame.got[index] = true
        System.arraycopy(body, HEADER, frame.data, index * PAYLOAD, bytes)
        if (index == count - 1) frame.size = index * PAYLOAD + bytes
        if (++frame.have < count) return null
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
        const val PAYLOAD = 1426
        const val SLOTS = 8
    }
}
