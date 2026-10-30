package com.davideloconte.screenlet

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The PC's desktop on this screen. The PC sends each encoded frame as soon
 * as it has it, cut into UDP packets (or cable datagrams) sealed with AES-GCM
 * under a key it handed over the paired TLS link. A frame goes to MediaCodec
 * the moment it is whole and is shown the moment it is decoded: no jitter
 * buffer. A frame with a packet missing is dropped and a keyframe asked for.
 *
 * The hot path (receive, open, put together, into the decoder) runs on one
 * thread at display priority with buffers it keeps: no allocation, no lock
 * shared with slow work (attach() and detach() take only codecLock).
 *
 * Times are each side's monotonic clock in ns; PING and PONG tell the
 * offset, so each stage is measured, not guessed (see stats()).
 */
class DisplaySession(key: ByteArray, private val pc: InetAddress, private var pcPort: Int,
                     private val mime: String = MediaFormat.MIMETYPE_VIDEO_AVC) {
    val socket = DatagramSocket(0).apply {
        receiveBufferSize = 4 shl 20
        trafficClass = 0xB8  // DSCP EF: Wi-Fi sends the input in its voice queue (WMM), ahead of the rest
    }
    private val sealed = Sealed(key)
    @Volatile private var running = true
    @Volatile private var tunnel: ((ByteArray, Int) -> Unit)? = null  // the cable's, once a packet came through it

    private val frames = Frames()
    private val plain = ByteArray(65536)    // the receiving thread's opened body (a large datagram's too)

    // MediaCodec in asynchronous mode, its callbacks on a thread of their own.
    private val callbacks = HandlerThread("screenlet-codec", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(callbacks.looper)
    private val main = Handler(android.os.Looper.getMainLooper())
    private val codecLock = Any()
    @Volatile private var codec: MediaCodec? = null
    private var surface: Surface? = null  // attach()'s, for a decoder made again after an error
    @Volatile private var restarts = 0
    private val free = ConcurrentLinkedQueue<Int>()
    // Whole frames that found no input buffer free (the decoder busy with a
    // keyframe for a moment): copied aside, fed by the decoder's callback.
    private val pending = ArrayDeque<Waiting>()
    private val spare = ArrayDeque<Waiting>()
    private class Waiting(var data: ByteArray, var size: Int = 0, var number: Long = 0, var keyframe: Boolean = false)
    @Volatile private var needKeyframe = true
    private var askedAt = 0L

    var onEnd: (() -> Unit)? = null
    fun end() = onEnd?.invoke()

    @Volatile var visible = true
        private set
    var onVisible: ((Boolean) -> Unit)? = null
    fun show(visible: Boolean) {
        this.visible = visible
        ping()
        onVisible?.invoke(visible)
    }

    val timing = Timing()

    private val receiver = Thread(::receive, "screenlet-video-in")
    private val ticker = Thread(::tick, "screenlet-tick")

    fun start() {
        receiver.start()
        ticker.start()
    }

    fun stop() {
        running = false
        socket.close()
        detach()
        callbacks.quitSafely()
        ticker.interrupt()
    }

    /** The screen to show it on (DisplayActivity's surface). */
    fun attach(surface: Surface) { synchronized(codecLock) {
        detach()
        this.surface = surface
        val decoder = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            Log.w(CompanionService.TAG, "display: no decoder: $e")
            return
        }
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                free.add(index)
                feedPending()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                timing.decoded(info.presentationTimeUs, System.nanoTime())
                restarts = 0
                // Due now: of frames decoded within one refresh the screen
                // takes the newest, so a burst never holds the decoder's buffers.
                try { codec.releaseOutputBuffer(index, System.nanoTime()) } catch (e: IllegalStateException) {}
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.w(CompanionService.TAG, "display: decoder: $e (transient: ${e.isTransient})")
                needKeyframe = true
                if (!e.isTransient) main.post { restart(codec) }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(CompanionService.TAG, "display: decoding $format")
            }
        }
        // The fastest the decoder takes: low latency, real-time priority, as
        // fast as it can run, and the vendors' own low-latency switches (a
        // decoder ignores keys it does not know). Exynos refuses an operating
        // rate: then without it.
        val configured = listOf(true, false).any { fast ->
            try {
                decoder.setCallback(callback, handler)
                decoder.configure(format(fast), surface, null, 0)
                true
            } catch (e: Exception) {
                Log.i(CompanionService.TAG, "display: decoder refused ${if (fast) "the fast format" else "the format"}: $e")
                runCatching { decoder.reset() }
                false
            }
        }
        if (!configured) {
            decoder.release()
            return
        }
        try {
            decoder.start()
        } catch (e: Exception) {
            Log.w(CompanionService.TAG, "display: no decoder: $e")
            decoder.release()
            return
        }
        Log.i(CompanionService.TAG, "display: decoder ${decoder.name}")
        synchronized(pending) { while (pending.isNotEmpty()) spare.addLast(pending.removeFirst()) }
        codec = decoder
        needKeyframe = true
        askedAt = 0L
    } }

    private fun format(fast: Boolean) = MediaFormat.createVideoFormat(mime, MAX_WIDTH, MAX_HEIGHT).apply {
        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        setInteger(MediaFormat.KEY_PRIORITY, 0)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 shl 20)
        if (fast) {
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
            setInteger("vendor.low-latency.enable", 1)
        }
    }

    private fun restart(failed: MediaCodec) { synchronized(codecLock) {
        val surface = surface
        if (codec !== failed || surface == null || !running) return
        if (++restarts > MAX_RESTARTS) {
            Log.w(CompanionService.TAG, "display: the decoder keeps failing: none")
            detach()
            return
        }
        attach(surface)
    } }

    fun detach() { synchronized(codecLock) {
        surface = null
        val decoder = codec ?: return
        codec = null
        free.clear()
        try { decoder.stop() } catch (e: IllegalStateException) {}
        decoder.release()
    } }

    private fun receive() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val buffer = ByteArray(65536)  // a whole UDP datagram, the PC's large ones too
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            try {
                packet.length = buffer.size
                socket.receive(packet)
            } catch (e: Exception) {
                if (running) Log.w(CompanionService.TAG, "display: $e")
                break
            }
            if (packet.address != pc) continue
            if (opened(buffer, packet.length)) pcPort = packet.port  // replies go where the PC sends from
        }
    }

    /** A datagram the cable brought (the cable's thread, at display
     * priority): the replies go back through it. */
    fun fromTunnel(packet: ByteArray, length: Int, tunnel: (ByteArray, Int) -> Unit) {
        if (opened(packet, length)) this.tunnel = tunnel
    }

    @Synchronized  // the socket's thread and the cable's: one at a time
    private fun opened(packet: ByteArray, length: Int): Boolean {
        val n = sealed.open(packet, length, plain)
        if (n < 0) return false
        when (plain[0]) {
            VIDEO -> video(n)
            PONG -> if (n >= 17) timing.pong(Sealed.readLong(plain, 1), Sealed.readLong(plain, 9))
        }
        return true
    }

    private fun video(n: Int) {
        val frame = frames.add(plain, n)
        val lost = frames.lost
        if (lost > 0) {
            frames.lost = 0
            timing.lost(lost)
            needKeyframe = true
        }
        frame ?: return
        if (codec == null) {
            needKeyframe = true  // nothing to decode into: attach() starts from a keyframe
            return
        }
        if (needKeyframe && !frame.keyframe) return askKeyframe()
        if (frame.keyframe) needKeyframe = false
        timing.complete(frame.number, frame.captured, frame.first, frame.complete)
        // Straight into a free input buffer: the one copy left.
        val index = synchronized(pending) { if (pending.isEmpty()) free.poll() else null }
        if (index != null) {
            if (!queue(index, frame.data, frame.size, frame.number)) dropAll()
            return
        }
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) {
                // The decoder fell behind for good: what waits goes.
                timing.lost(pending.size)
                while (pending.isNotEmpty()) spare.addLast(pending.removeFirst())
                if (!frame.keyframe) {
                    timing.lost(1)
                    needKeyframe = true
                    return askKeyframe()
                }
            }
            val waiting = spare.removeFirstOrNull() ?: Waiting(ByteArray(frame.size))
            if (waiting.data.size < frame.size) waiting.data = ByteArray(frame.size)
            System.arraycopy(frame.data, 0, waiting.data, 0, frame.size)
            waiting.size = frame.size
            waiting.number = frame.number
            waiting.keyframe = frame.keyframe
            pending.addLast(waiting)
        }
        feedPending()
    }

    private fun feedPending() {
        while (true) {
            val (waiting, index) = synchronized(pending) {
                if (pending.isEmpty()) return
                val index = free.poll() ?: return
                pending.removeFirst() to index
            }
            val ok = queue(index, waiting.data, waiting.size, waiting.number)
            synchronized(pending) { spare.addLast(waiting) }
            if (!ok) return dropAll()
        }
    }

    private fun dropAll() {
        synchronized(pending) { while (pending.isNotEmpty()) spare.addLast(pending.removeFirst()) }
        timing.lost(1)
        needKeyframe = true
        askKeyframe()
    }

    private fun queue(index: Int, data: ByteArray, size: Int, number: Long): Boolean {
        val decoder = codec ?: return false
        return try {
            val input = decoder.getInputBuffer(index) ?: return false
            input.clear()
            input.put(data, 0, size)
            timing.queued(number, System.nanoTime())
            decoder.queueInputBuffer(index, 0, size, number, 0)
            true
        } catch (e: Exception) {
            Log.w(CompanionService.TAG, "display: $e")
            false
        }
    }

    private fun askKeyframe() {
        val now = System.nanoTime()
        if (now - askedAt < KEYFRAME_AGAIN) return
        askedAt = now
        send(byteArrayOf(KEYFRAME), 1)
    }

    private fun ping() {
        val b = ByteArray(10)
        b[0] = PING
        Sealed.writeLong(b, 1, System.nanoTime())
        b[9] = if (visible) 1 else 0
        send(b, b.size)
    }

    /** Every second a ping for the clocks and the measures; hidden, now and then a ping. */
    private fun tick() {
        var ticks = 0
        while (running) {
            if (visible) {
                ping()
                val stats = timing.stats()
                send(stats, stats.size)
            } else if (ticks % HIDDEN_PING_EVERY == 0) {
                ping()
            }
            ticks++
            try { Thread.sleep(1000) } catch (e: InterruptedException) { return }
        }
    }

    private val sending = ByteArray(2048)
    private val outgoing = DatagramPacket(sending, 0)

    /** body to the PC, sealed, from any thread: input from the main one,
     * which may use the network for it (DisplayActivity): a UDP send does
     * not wait, and a hop to another thread would cost more than it. */
    @Synchronized
    fun send(body: ByteArray, length: Int) {
        if (!running) return
        try {
            val n = sealed.seal(body, length, sending)
            val tunnel = tunnel
            if (tunnel != null) return tunnel(sending, n)
            outgoing.setData(sending, 0, n)
            outgoing.address = pc
            outgoing.port = pcPort
            socket.send(outgoing)
        } catch (e: Exception) {
            if (running) Log.w(CompanionService.TAG, "display: cannot send: $e")
        }
    }

    companion object {
        const val VIDEO: Byte = 1
        const val KEYFRAME: Byte = 2
        const val PING: Byte = 3
        const val PONG: Byte = 4
        const val STATS: Byte = 5
        const val INPUT: Byte = 6
        const val MAX_WIDTH = 3840
        const val MAX_HEIGHT = 2400
        const val KEYFRAME_AGAIN = 250_000_000L  // ns
        const val MAX_RESTARTS = 3
        const val HIDDEN_PING_EVERY = 10        // ticks (seconds)
        const val MAX_PENDING = 3               // whole frames waiting for the decoder: 50 ms at 60 fps

        /** Whether a decoder (a hardware one, if hardware) takes mime at 1080p. */
        fun decodes(mime: String, hardware: Boolean) = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && (!hardware || info.isHardwareAccelerated) && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching { info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(1920, 1080) == true }.getOrDefault(false)
        }

        /** This tablet's hardware decoders (PROTOCOL.md §3): codec name -> the
         * frames per second each sustains at width x height, as the device
         * measured it where it did, as its maker declares otherwise. */
        fun profile(width: Int, height: Int) = org.json.JSONObject().apply {
            for ((name, mime) in CompanionService.CODECS) {
                val fps = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { !it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                    .mapNotNull { info ->
                        runCatching {
                            val video = info.getCapabilitiesForType(mime).videoCapabilities!!
                            (video.getAchievableFrameRatesFor(width, height) ?: video.getSupportedFrameRatesFor(width, height)).upper
                        }.getOrNull()
                    }.maxOrNull() ?: continue
                put(name, fps.toInt())
            }
        }

        @Volatile var current: DisplaySession? = null
    }
}

/** The display notification's Stop. */
class DisplayStopReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        DisplaySession.current?.end()
    }
}
