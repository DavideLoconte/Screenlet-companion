package com.davideloconte.screenlet

/**
 * Measures per-frame latency metrics across individual transport and decoding stages.
 *
 * Synchronizes client and host monotonic clocks via round-trip ping-pong exchanges,
 * records interval timings across five discrete rendering pipeline phases, and generates
 * periodic statistical telemetry containing quantile distributions.
 */
class Timing {
    private class Record(var number: Long = -1, var captured: Long = 0, var first: Long = 0,
                         var complete: Long = 0, var queued: Long = 0)

    private val records = Array(RECORDS) { Record() }
    private val samples = Array(STAGES) { IntArray(MAX_SAMPLES) }
    private var count = 0
    private var shown = 0
    private var lost = 0
    private var offset = 0L
    private var bestRtt = Long.MAX_VALUE
    private var offsetAt = 0L

    @Synchronized
    fun complete(number: Long, captured: Long, first: Long, complete: Long) {
        val r = records[(number % RECORDS).toInt()]
        r.number = number; r.captured = captured; r.first = first; r.complete = complete; r.queued = 0
    }

    @Synchronized
    fun queued(number: Long, now: Long) {
        val r = records[(number % RECORDS).toInt()]
        if (r.number == number) r.queued = now
    }

    @Synchronized
    fun decoded(number: Long, now: Long) {
        val r = records[(number % RECORDS).toInt()]
        if (r.number != number || r.queued == 0L) return
        r.number = -1
        shown++
        if (count == MAX_SAMPLES) return
        val aligned = offsetAt != 0L
        put(0, if (aligned) now + offset - r.captured else -1)
        put(1, if (aligned) r.first + offset - r.captured else -1)
        put(2, r.complete - r.first)
        put(3, r.queued - r.complete)
        put(4, now - r.queued)
        count++
    }

    private fun put(stage: Int, nanos: Long) {
        samples[stage][count] = if (nanos < 0) -1 else (nanos / 100_000).coerceAtMost(0xffff).toInt()
    }

    @Synchronized
    fun lost(n: Int) {
        lost += n
    }

    @Synchronized
    fun pong(ours: Long, theirs: Long, now: Long = System.nanoTime()) {
        val rtt = now - ours
        // The ping that came back soonest tells the offset best; it ages out.
        if (rtt < bestRtt || now - offsetAt > OFFSET_AGE) {
            bestRtt = rtt
            offset = theirs - (ours + now) / 2
            offsetAt = now
        }
    }

    /** Compiles a binary statistical telemetry payload covering the preceding measurement interval. */
    @Synchronized
    fun stats(): ByteArray {
        val b = ByteArray(SIZE)
        b[0] = DisplaySession.STATS
        Sealed.writeInt(b, 1, shown)
        Sealed.writeInt(b, 5, lost)
        var at = 9
        for (stage in samples) {
            val sorted = stage.copyOf(count).filter { it >= 0 }.sorted()
            for (q in QUANTILES) {
                val v = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * q).toInt()]
                b[at++] = (v ushr 8).toByte()
                b[at++] = v.toByte()
            }
        }
        shown = 0
        lost = 0
        count = 0
        return b
    }

    companion object {
        const val STAGES = 5
        const val SIZE = 1 + 8 + STAGES * 6
        const val RECORDS = 32
        const val MAX_SAMPLES = 256
        const val OFFSET_AGE = 10_000_000_000L
        private val QUANTILES = doubleArrayOf(0.5, 0.9, 1.0)
    }
}
