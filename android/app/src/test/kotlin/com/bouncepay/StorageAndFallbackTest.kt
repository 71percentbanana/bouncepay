package com.bouncepay

import com.bouncepay.bank.EmbeddedBank
import com.bouncepay.bank.Settlement
import com.bouncepay.crypto.DeviceKey
import com.bouncepay.model.Packet
import com.bouncepay.model.PacketState
import com.bouncepay.store.PacketStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * The two things on the phone that must not lose or invent money: the
 * store-and-forward queue, and the on-device fallback bank.
 */
class StorageAndFallbackTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_790_000_000_000L
    private val key: KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    private val spki = Base64.getEncoder().encodeToString(key.public.encoded)
    private val payerId = DeviceKey.accountIdFor(spki)

    private fun packet(
        amountPaise: Int = 100_00,
        createdAt: Long = now,
        nonce: String = UUID.randomUUID().toString().replace("-", "").take(24),
        txId: String = UUID.randomUUID().toString(),
        payer: String = payerId,
    ): Packet {
        val payload = """{"v":1,"txId":"$txId","nonce":"$nonce","amountPaise":$amountPaise,""" +
            """"payerId":"$payer","payeeId":"campus-stationery","createdAt":$createdAt}"""
        val sig = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(payload.toByteArray()); sign()
        })
        return Packet(payload, sig, spki, listOf(payer))
    }

    // ---- PacketStore ----------------------------------------------------------

    @Test
    fun `the queue survives a restart`() {
        val file = tmp.newFile("queue.json").apply { delete() }
        val p = packet()
        PacketStore(file).apply {
            assertTrue(offer(p))
            recordHandoff(p.txId, "bp_relay00000001")
        }

        val reopened = PacketStore(file).find(p.txId)!!
        assertEquals(PacketState.FORWARDED, reopened.state)
        assertEquals(listOf(payerId, "bp_relay00000001"), reopened.packet.hops)
        assertEquals("the signed bytes are untouched", p.payload, reopened.packet.payload)
    }

    @Test
    fun `a packet arriving again merges its route instead of duplicating`() {
        val store = PacketStore(tmp.newFile("q.json").apply { delete() })
        val p = packet()
        assertTrue(store.offer(p))
        assertFalse(store.offer(p.withHop("bp_other0000000001")))
        assertEquals(1, store.packets.value.size)
        assertEquals(listOf(payerId, "bp_other0000000001"), store.find(p.txId)!!.packet.hops)
    }

    @Test
    fun `one corrupt entry does not lose the rest of the queue`() {
        val file = tmp.newFile("q.json").apply { delete() }
        val good = packet()
        PacketStore(file).offer(good)

        // Damage the file the way a bad write or a bad edit might.
        val text = file.readText()
        file.writeText(text.dropLast(1) + """,{"packet":{"payload":"not json"},"state":"HELD"}]""")

        val reopened = PacketStore(file)
        assertEquals(1, reopened.packets.value.size)
        assertEquals(good.txId, reopened.packets.value.single().packet.txId)
    }

    @Test
    fun `finished packets can be cleared, pending ones cannot`() {
        val store = PacketStore(tmp.newFile("q.json").apply { delete() })
        val done = packet(); val refused = packet(); val waiting = packet()
        listOf(done, refused, waiting).forEach { store.offer(it) }
        store.markSettled(done.txId, "ok")
        store.markRejected(refused.txId, "INSUFFICIENT_FUNDS")

        store.clearTerminal()

        assertEquals(listOf(waiting.txId), store.packets.value.map { it.packet.txId })
    }

    // ---- EmbeddedBank: the same rules as the real one ------------------------

    private fun bank() = EmbeddedBank(tmp.newFile("bank.json").apply { delete() }, clock = { now })

    private fun code(result: Settlement) = (result as? Settlement.Rejected)?.code

    @Test
    fun `the fallback settles a good packet once and answers repeats`() = runBlocking {
        val b = bank()
        val p = packet(amountPaise = 300_00)
        val first = b.settle(p) as Settlement.Settled
        assertFalse(first.duplicate)
        assertEquals(2_000_00 + 300_00, first.payeeBalancePaise)
        assertEquals("fallback settlements are labelled as such", "on-device fallback", first.source)
        assertEquals("and carry no bank proof", null, first.proof)

        val again = b.settle(p) as Settlement.Settled
        assertTrue(again.duplicate)
        assertEquals(first.payeeBalancePaise, again.payeeBalancePaise)
    }

    @Test
    fun `the fallback refuses what the bank refuses, with the bank's codes`() = runBlocking {
        val b = bank()
        assertEquals("BAD_SIGNATURE", code(b.settle(packet().let { it.copy(payload = it.payload.replace("10000", "90000")) })))
        assertEquals("KEY_MISMATCH", code(b.settle(packet(payer = "bp_someoneelse0000"))))
        assertEquals("EXPIRED", code(b.settle(packet(createdAt = now - 8L * 24 * 3600 * 1000))))
        assertEquals("FUTURE_DATED", code(b.settle(packet(createdAt = now + 10 * 60 * 1000))))
        assertEquals("AMOUNT_EXCEEDS_LIMIT", code(b.settle(packet(amountPaise = 2_000_01))))
        assertEquals("INVALID_AMOUNT", code(b.settle(packet(amountPaise = 0))))

        val nonce = "a1b2c3d4e5f60718293a4b5c"
        assertTrue(b.settle(packet(nonce = nonce)) is Settlement.Settled)
        assertEquals("REPLAYED_NONCE", code(b.settle(packet(nonce = nonce))))
    }

    @Test
    fun `the fallback remembers what it settled across a restart`() = runBlocking {
        val file = tmp.newFile("bank.json").apply { delete() }
        val p = packet()
        EmbeddedBank(file, clock = { now }).settle(p)

        val reopened = EmbeddedBank(file, clock = { now })
        assertTrue((reopened.settle(p) as Settlement.Settled).duplicate)
        assertEquals("REPLAYED_NONCE", code(reopened.settle(packet(nonce = p.fields.nonce))))
    }
}
