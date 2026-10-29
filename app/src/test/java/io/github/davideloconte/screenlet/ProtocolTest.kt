package io.github.davideloconte.screenlet

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The shared vectors (PROTOCOL.md, vectors.json) and each piece of the hot path. */
class ProtocolTest {
    private val vectors = JSONObject(javaClass.getResource("/vectors.json")!!.readText())
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hexOf(b: ByteArray, n: Int = b.size) = b.take(n).joinToString("") { "%02x".format(it) }

    @Test fun pairingCodeAndCommitment() {
        val p = vectors.getJSONObject("pairing")
        assertEquals(p.getString("code"), Identity.pairingCode(hex(p.getString("cert_b")), hex(p.getString("cert_a")),
            hex(p.getString("pc_nonce")), hex(p.getString("app_nonce"))))
        val c = vectors.getJSONObject("commitment")
        assertEquals(c.getString("commit"), Identity.commitment(hex(c.getString("app_nonce")),
            hex(c.getString("app_cert")), hex(c.getString("pc_cert"))))
    }

    @Test fun opensTheVectorAndRefusesItTwice() {
        val s = vectors.getJSONObject("sealed")
        val sealed = Sealed(hex(s.getString("key")))
        val packet = hex(s.getString("packet"))
        val out = ByteArray(2048)
        val n = sealed.open(packet, packet.size, out)
        assertEquals(s.getString("body"), hexOf(out, n))
        assertEquals(-1, sealed.open(packet, packet.size, out))  // replayed
        packet[20] = (packet[20].toInt() xor 1).toByte()
        assertEquals(-1, Sealed(hex(s.getString("key"))).open(packet, packet.size, out))  // tampered
    }

    @Test fun sealsForThePc() {
        val key = ByteArray(32) { it.toByte() }
        val out = ByteArray(64)
        val n = Sealed(key).seal(byteArrayOf(2), 1, out)
        assertEquals(12 + 1 + 16, n)
        assertEquals(2, Sealed.readInt(out, 0))
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, out, 0, 12))
            doFinal(out, 12, n - 12)
        }
        assertArrayEquals(byteArrayOf(2), plain)
    }

    @Test fun replayWindowTakesLateOnesOnce() {
        val r = Sealed.Replay()
        for (c in listOf(0L, 2L, 1L, 70L)) { assertTrue(r.fresh(c)); r.accept(c) }
        assertTrue(!r.fresh(1) && !r.fresh(70) && !r.fresh(6))  // seen, seen, too old
        assertTrue(r.fresh(69) && r.fresh(71))
    }

    private fun packets(number: Int, data: ByteArray, keyframe: Boolean = false, captured: Long = 7): List<ByteArray> {
        val count = (data.size + Frames.PAYLOAD - 1) / Frames.PAYLOAD
        return (0 until count).map { i ->
            val part = data.copyOfRange(i * Frames.PAYLOAD, minOf(data.size, (i + 1) * Frames.PAYLOAD))
            val b = ByteArray(Frames.HEADER + part.size)
            b[0] = DisplaySession.VIDEO
            b[1] = if (keyframe) 1 else 0
            Sealed.writeInt(b, 2, number)
            b[6] = (i ushr 8).toByte(); b[7] = i.toByte()
            b[8] = (count ushr 8).toByte(); b[9] = count.toByte()
            Sealed.writeLong(b, 10, captured)
            System.arraycopy(part, 0, b, Frames.HEADER, part.size)
            b
        }
    }

    @Test fun framesWholeInAnyOrder() {
        val data = ByteArray(10_000) { (it * 7).toByte() }
        val frames = Frames()
        val parts = packets(5, data, keyframe = true).shuffled(java.util.Random(1))
        var whole: Frames.Frame? = null
        for (p in parts) whole = frames.add(p, p.size) ?: whole
        assertNotNull(whole)
        assertEquals(data.size, whole!!.size)
        assertArrayEquals(data, whole.data.copyOf(whole.size))
        assertTrue(whole.keyframe)
        assertEquals(7L, whole.captured)
        assertEquals(0, frames.lost)
    }

    @Test fun anOlderFrameMissingAPacketIsLost() {
        val frames = Frames()
        val a = packets(1, ByteArray(3000))
        frames.add(a[0], a[0].size)  // a[1], a[2] never come
        val b = packets(2, ByteArray(100))
        assertNotNull(frames.add(b[0], b[0].size))
        assertEquals(1, frames.lost)
        assertNull(frames.add(a[1], a[1].size))  // too late now
    }

    @Test fun aShortPacketThatIsNotTheLastIsRefused() {
        val p = packets(1, ByteArray(3000))[0]
        assertNull(Frames().add(p, p.size - 1))
    }

    @Test fun inputMatchesTheVector() {
        val packet = InputPacket()
        packet.begin()
        packet.finger(3, 0.5f, 0.25f)
        packet.pen(InputPacket.TIP or InputPacket.IN_RANGE, 0.5f, 0.5f, 0.5f, 0f, 0f)
        packet.end()
        assertEquals(vectors.getJSONObject("input").getString("body").replace(" ", ""), hexOf(packet.bytes, packet.length))
    }

    @Test fun anEmptyInputSaysNothingTouches() {
        val packet = InputPacket()
        packet.begin()
        packet.end()
        assertEquals("060000", hexOf(packet.bytes, packet.length))
    }

    @Test fun statsStageByStage() {
        val t = Timing()
        t.pong(ours = 1_000, theirs = 5_000_000, now = 3_000)  // PC clock = ours + 4_998_000
        for (i in 0 until 10L) {
            val first = 10_000_000 * i + 2_000_000
            t.complete(i, captured = 10_000_000 * i + 4_998_000, first = first, complete = first + 1_000_000)
            t.queued(i, first + 1_500_000)
            t.decoded(i, first + 4_500_000)
        }
        t.lost(2)
        val b = t.stats()
        assertEquals(Timing.SIZE, b.size)
        assertEquals(10, Sealed.readInt(b, 1))
        assertEquals(2, Sealed.readInt(b, 5))
        fun stage(s: Int) = (0 until 3).map { q -> ((b[9 + s * 6 + q * 2].toInt() and 0xff) shl 8) or (b[10 + s * 6 + q * 2].toInt() and 0xff) }
        assertEquals(listOf(65, 65, 65), stage(0))  // 6.5 ms capture -> decoded
        assertEquals(listOf(20, 20, 20), stage(1))  // 2 ms capture -> first packet
        assertEquals(listOf(10, 10, 10), stage(2))  // 1 ms over the network
        assertEquals(listOf(5, 5, 5), stage(3))     // 0.5 ms for a buffer
        assertEquals(listOf(30, 30, 30), stage(4))  // 3 ms decoding
        assertEquals(0, Sealed.readInt(t.stats(), 1))  // counts start again
    }

    /** The receiving thread's cost per packet (open, put together), printed and bounded. */
    @Test fun hotPathCostPerPacket() {
        val key = ByteArray(32)
        val pc = Cipher.getInstance("AES/GCM/NoPadding")
        val frames = Frames()
        val out = ByteArray(2048)
        var count = 0L
        val sealed = (0 until 600).flatMap { f -> packets(f, ByteArray(20 * Frames.PAYLOAD)) }.map { body ->
            val nonce = ByteArray(12).also { Sealed.writeInt(it, 0, 1); Sealed.writeLong(it, 4, count++) }
            pc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            nonce + pc.doFinal(body)
        }
        val opener = Sealed(key)
        val start = System.nanoTime()
        var whole = 0
        for (p in sealed) {
            val n = opener.open(p, p.size, out)
            if (frames.add(out, n) != null) whole++
        }
        val perPacket = (System.nanoTime() - start) / sealed.size
        println("hot path: $perPacket ns per 1.4 KB packet (${perPacket * 20 / 1000} µs per 28 KB frame)")
        assertEquals(600, whole)
        assertTrue("$perPacket ns per packet", perPacket < 50_000)
    }
}
