package com.bouncepay

import com.bouncepay.model.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The envelope changes in flight (hops are appended) while the signed payload
 * inside it must not change by a single byte.
 */
class PacketEnvelopeTest {

    // Deliberately written with an unusual key order: JSONObject would not
    // reproduce it, so any accidental re-serialisation shows up as a mismatch.
    private val payload =
        """{"v":1,"txId":"7c1e2f3a-0000-4000-8000-000000000001","nonce":"a1b2c3d4e5f60718293a4b5c",""" +
            """"amountPaise":10000,"payerId":"bp_0011223344556677","payeeId":"campus-stationery","createdAt":1790000000000}"""

    private val packet = Packet(
        payload = payload,
        sig = "MEUCIQ==",
        payerPubKey = "MFkwEw==",
        hops = listOf("bp_0011223344556677"),
    )

    @Test
    fun `payload survives a round trip byte for byte`() {
        val relayed = Packet.fromBytes(packet.withHop("bp_relay00000001").toBytes())
        assertEquals(payload, relayed.payload)
        assertEquals(listOf("bp_0011223344556677", "bp_relay00000001"), relayed.hops)
    }

    @Test
    fun `adding a hop twice is a no-op`() {
        val once = packet.withHop("bp_relay00000001")
        assertSame(once, once.withHop("bp_relay00000001"))
    }

    @Test
    fun `fields are read from the signed payload`() {
        with(packet.fields) {
            assertEquals(1, v)
            assertEquals("7c1e2f3a-0000-4000-8000-000000000001", txId)
            assertEquals(10000, amountPaise)
            assertEquals("campus-stationery", payeeId)
            assertEquals(1790000000000L, createdAt)
        }
    }

    @Test
    fun `a packet with no hops field still parses`() {
        val bare = Packet.fromJson("""{"payload":${org.json.JSONObject.quote(payload)},"sig":"s","payerPubKey":"k"}""")
        assertEquals(emptyList<String>(), bare.hops)
    }
}
