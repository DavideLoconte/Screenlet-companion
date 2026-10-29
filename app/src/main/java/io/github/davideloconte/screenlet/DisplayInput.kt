package io.github.davideloconte.screenlet

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Fingers and the pen on the PC's desktop. Each packet says what is on the
 * screen now (the fingers down, the pen's samples since the last packet),
 * sent at every event as it happens (unbuffered dispatch: the digitizer's
 * rate, not the display's) and again every REPEAT ms while anything touches:
 * a packet lost costs a moment, never a finger stuck down on the PC.
 * Straight from the main thread into one buffer kept for it: no allocation
 * and no thread hop per event.
 */
class DisplayInput(private val view: View, private val session: () -> DisplaySession?) {
    private val packet = InputPacket()
    private val ids = IntArray(InputPacket.MAX_FINGERS)
    private val xs = FloatArray(InputPacket.MAX_FINGERS)
    private val ys = FloatArray(InputPacket.MAX_FINGERS)
    private var fingers = 0
    private var penDown = false
    private var penSeenAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val repeat = object : Runnable {
        override fun run() {
            if (fingers == 0 && !penDown) return
            begin()
            flush()
            handler.postDelayed(this, REPEAT)
        }
    }

    init {
        view.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
        view.setOnTouchListener { _, e -> touch(e); true }
        view.setOnGenericMotionListener { _, e -> hover(e) }
    }

    private fun touch(e: MotionEvent) {
        val action = e.actionMasked
        val ending = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        fingers = 0
        for (i in 0 until e.pointerCount) {
            val lifted = ending || (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex)
            if (isPen(e, i)) continue
            if (!lifted && !palm() && fingers < InputPacket.MAX_FINGERS) {
                ids[fingers] = e.getPointerId(i)
                xs[fingers] = e.getX(i)
                ys[fingers] = e.getY(i)
                fingers++
            }
        }
        begin()
        for (i in 0 until e.pointerCount) if (isPen(e, i)) {
            penSeenAt = SystemClock.uptimeMillis()
            val lifted = ending || (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex)
            for (h in 0 until e.historySize) pen(e, i, h, tip = true, inRange = true)
            pen(e, i, -1, tip = !lifted, inRange = true)
            penDown = !lifted
        }
        flush()
        if (ending || action == MotionEvent.ACTION_POINTER_UP) { begin(); flush() }  // the last word, twice
        handler.removeCallbacks(repeat)
        if (fingers > 0 || penDown) handler.postDelayed(repeat, REPEAT)
    }

    private fun hover(e: MotionEvent): Boolean {
        if (!isPen(e, 0)) return false
        penSeenAt = SystemClock.uptimeMillis()
        val leaving = e.actionMasked == MotionEvent.ACTION_HOVER_EXIT
        fingers = 0  // a pen near: the hand on the screen is a palm
        begin()
        for (h in 0 until e.historySize) pen(e, 0, h, tip = false, inRange = true)
        pen(e, 0, -1, tip = false, inRange = !leaving)
        flush()
        if (leaving) { begin(); pen(e, 0, -1, tip = false, inRange = false); flush() }
        return true
    }

    private fun begin() {
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        packet.begin()
        for (f in 0 until fingers) packet.finger(ids[f], xs[f] / w, ys[f] / h)
    }

    private fun flush() {
        val session = session() ?: return
        packet.end()
        session.send(packet.bytes, packet.length)
    }

    /** Fingers while the pen is near, or was a moment ago, are the hand holding it. */
    private fun palm() = SystemClock.uptimeMillis() - penSeenAt < PALM_WINDOW

    private fun isPen(e: MotionEvent, i: Int) = e.getToolType(i).let {
        it == MotionEvent.TOOL_TYPE_STYLUS || it == MotionEvent.TOOL_TYPE_ERASER
    }

    /** Pointer i at history h (-1: now) as a pen sample; a full packet goes first. */
    private fun pen(e: MotionEvent, i: Int, h: Int, tip: Boolean, inRange: Boolean) {
        fun axis(a: Int) = if (h < 0) e.getAxisValue(a, i) else e.getHistoricalAxisValue(a, i, h)
        val x = if (h < 0) e.getX(i) else e.getHistoricalX(i, h)
        val y = if (h < 0) e.getY(i) else e.getHistoricalY(i, h)
        val pressure = if (h < 0) e.getPressure(i) else e.getHistoricalPressure(i, h)
        var bits = 0
        if (inRange) {
            bits = bits or InputPacket.IN_RANGE
            if (tip) bits = bits or InputPacket.TIP
            if (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) bits = bits or InputPacket.BARREL
            if (e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) bits = bits or InputPacket.ERASER
        }
        if (packet.full()) { flush(); begin() }
        packet.pen(bits, x / view.width.coerceAtLeast(1), y / view.height.coerceAtLeast(1), pressure,
            axis(MotionEvent.AXIS_TILT), axis(MotionEvent.AXIS_ORIENTATION))
    }

    companion object {
        const val REPEAT = 100L        // ms, while a finger or the pen touches
        const val PALM_WINDOW = 400L   // ms after the pen was near
    }
}

/**
 * One INPUT body, built in place: kind | fingers u8 | per finger id u8,
 * x u16, y u16 (0..65535 of the screen) | samples u8 | per sample the S Pen's
 * report after its ID, little-endian: bits u8 (tip 1, barrel 2, eraser 8,
 * in range 16), x u16, y u16 (0..32767), pressure u16 (0..4096), tilt x i8,
 * tilt y i8 (degrees). Plain Kotlin: tested on the JVM.
 */
class InputPacket {
    val bytes = ByteArray(3 + MAX_FINGERS * 5 + MAX_SAMPLES * PEN_SAMPLE)
    var length = 0
        private set
    private var fingers = 0
    private var samplesAt = 0
    private var samples = 0

    fun begin() {
        bytes[0] = DisplaySession.INPUT
        fingers = 0
        samples = 0
        length = 2
    }

    /** A finger at x, y in 0..1 of the screen; before any pen sample. */
    fun finger(id: Int, x: Float, y: Float) {
        bytes[length] = id.toByte()
        u16be(length + 1, scale(x, TOUCH_MAX))
        u16be(length + 3, scale(y, TOUCH_MAX))
        length += 5
        fingers++
    }

    fun full() = samples == MAX_SAMPLES

    /** tilt: radians from upright; towards: radians, 0 up, pi/2 right (Android's). */
    fun pen(bits: Int, x: Float, y: Float, pressure: Float, tilt: Float, towards: Float) {
        if (samples == 0) { samplesAt = length; length++ }
        val tiltX = Math.toDegrees(atan(tan(tilt) * sin(towards)).toDouble())
        val tiltY = Math.toDegrees(atan(-tan(tilt) * cos(towards)).toDouble())
        bytes[length] = bits.toByte()
        u16le(length + 1, scale(x, PEN_MAX))
        u16le(length + 3, scale(y, PEN_MAX))
        u16le(length + 5, (pressure.coerceIn(0f, 1f) * PRESSURE_MAX).toInt())
        bytes[length + 7] = tiltX.coerceIn(-90.0, 90.0).toInt().toByte()
        bytes[length + 8] = tiltY.coerceIn(-90.0, 90.0).toInt().toByte()
        length += PEN_SAMPLE
        samples++
    }

    fun end() {
        bytes[1] = fingers.toByte()
        if (samples == 0) bytes[length++] = 0 else bytes[samplesAt] = samples.toByte()
    }

    private fun scale(v: Float, max: Int) = (v * max).toInt().coerceIn(0, max)

    private fun u16be(at: Int, v: Int) { bytes[at] = (v ushr 8).toByte(); bytes[at + 1] = v.toByte() }
    private fun u16le(at: Int, v: Int) { bytes[at] = v.toByte(); bytes[at + 1] = (v ushr 8).toByte() }

    companion object {
        const val MAX_FINGERS = 10
        const val MAX_SAMPLES = 64
        const val PEN_SAMPLE = 9
        const val TOUCH_MAX = 65535
        const val PEN_MAX = 32767
        const val PRESSURE_MAX = 4096
        const val TIP = 0x01
        const val BARREL = 0x02
        const val ERASER = 0x08
        const val IN_RANGE = 0x10
    }
}
