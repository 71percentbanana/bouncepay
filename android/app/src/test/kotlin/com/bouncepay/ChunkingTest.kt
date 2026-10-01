package com.bouncepay

import com.bouncepay.ble.Chunking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The chunk framing is hand-rolled, so it is the part of the BLE path most
 * likely to be quietly wrong — and a mis-assembled packet does not look like a
 * transport bug, it looks like a failed signature. These run on the JVM, so
 * they catch that without needing two phones.
 */
class ChunkingTest {

    private fun roundTrip(data: ByteArray, mtu: Int, shuffle: Boolean = false): ByteArray? {
        val chunks = Chunking.split(data, mtu).let { if (shuffle) it.shuffled(Random(7)) else it }
        val assembler = Chunking.Assembler()
        var result: ByteArray? = null
        for (chunk in chunks) {
            assembler.accept(chunk)?.let { result = it }
        }
        return result
    }

    @Test
    fun `a packet survives the round trip at the default MTU`() {
        val data = Random(1).nextBytes(800)
        assertArrayEquals(data, roundTrip(data, mtu = 23))
    }

    @Test
    fun `a packet survives the round trip at a negotiated MTU`() {
        val data = Random(2).nextBytes(800)
        assertArrayEquals(data, roundTrip(data, mtu = 517))
    }

    @Test
    fun `chunks arriving out of order still assemble`() {
        // A GATT stack may reorder writes; the index in the header is what
        // makes that survivable.
        val data = Random(3).nextBytes(1200)
        assertArrayEquals(data, roundTrip(data, mtu = 100, shuffle = true))
    }

    @Test
    fun `a packet smaller than one chunk still produces one frame`() {
        val data = Random(4).nextBytes(10)
        assertEquals(1, Chunking.split(data, 517).size)
        assertArrayEquals(data, roundTrip(data, mtu = 517))
    }

    @Test
    fun `an incomplete transfer yields nothing`() {
        val data = Random(5).nextBytes(500)
        val chunks = Chunking.split(data, 23)
        val assembler = Chunking.Assembler()
        // Feed everything except the last chunk.
        chunks.dropLast(1).forEach { assertNull(assembler.accept(it)) }
    }

    @Test
    fun `a new transfer replaces an abandoned one`() {
        val abandoned = Chunking.split(Random(6).nextBytes(900), 50)
        val wanted = Random(7).nextBytes(300)
        val assembler = Chunking.Assembler()

        // Half of one packet, then a completely different packet arrives.
        abandoned.take(3).forEach { assembler.accept(it) }

        var result: ByteArray? = null
        Chunking.split(wanted, 50).forEach { assembler.accept(it)?.let { r -> result = r } }
        assertArrayEquals(wanted, result)
    }

    @Test
    fun `a truncated frame is ignored rather than crashing`() {
        val assembler = Chunking.Assembler()
        assertNull(assembler.accept(ByteArray(2)))
        assertNull(assembler.accept(ByteArray(0)))
    }

    @Test
    fun `every frame fits in a single ATT write at any MTU`() {
        // Found against a second device on the emulator's virtual radio: at
        // MTU 517 frames were 514 bytes, past GATT's 512-byte attribute cap,
        // so Android refused every hand-off; at MTU 23 they were 24 bytes,
        // past the 20 one write carries.
        val data = Random(9).nextBytes(900)
        for (mtu in listOf(23, 24, 50, 185, 247, 512, 515, 516, 517)) {
            val frames = Chunking.split(data, mtu)
            val limit = minOf(mtu - 3, 512)
            frames.forEach { assertTrue("MTU $mtu: ${it.size}-byte frame, limit $limit", it.size <= limit) }
            assertArrayEquals(data, roundTrip(data, mtu))
        }
    }

    @Test
    fun `a negotiated MTU still uses big frames`() {
        assertEquals(512, Chunking.split(Random(10).nextBytes(2000), 517).first().size)
        assertEquals(20, Chunking.split(Random(11).nextBytes(100), 23).first().size)
    }

    @Test
    fun `chunk count is honest about the payload size`() {
        val mtu = 100
        val perChunk = Chunking.payloadPerChunk(mtu)
        val data = Random(8).nextBytes(perChunk * 3)
        assertEquals(3, Chunking.split(data, mtu).size)
    }
}
