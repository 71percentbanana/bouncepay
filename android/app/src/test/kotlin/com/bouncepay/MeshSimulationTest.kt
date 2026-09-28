package com.bouncepay

import com.bouncepay.bank.Bank
import com.bouncepay.bank.Settlement
import com.bouncepay.ble.Exchange
import com.bouncepay.crypto.DeviceKey
import com.bouncepay.mesh.MeshRouter
import com.bouncepay.mesh.ReceiptBook
import com.bouncepay.model.Packet
import com.bouncepay.model.PacketState
import com.bouncepay.model.SignedReceipt
import com.bouncepay.store.IncomingLedger
import com.bouncepay.store.PacketStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * The mesh, without the radio.
 *
 * Each simulated phone is a real [MeshRouter] over a real on-disk
 * [PacketStore], exactly as on a handset; only Bluetooth is replaced, by
 * handing each neighbour what the router says it needs — the same thing
 * MeshCentral writes over GATT. Keys are real P-256 and the bank really
 * verifies and signs.
 *
 * This is what can be known about routing before two phones are in a room.
 */
class MeshSimulationTest {

    @get:Rule val tmp = TemporaryFolder()

    // ---- the world -----------------------------------------------------------

    private fun newKey(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    private fun spki(key: KeyPair) = Base64.getEncoder().encodeToString(key.public.encoded)

    private fun sign(key: KeyPair, payload: String): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(payload.toByteArray(Charsets.UTF_8))
            sign()
        })

    /** Mirrors mock-bank: same checks, same refusal codes, signed receipts. */
    inner class FakeBank : Bank {
        override val label = "fake bank"
        val key = newKey()
        val pubKey = spki(key)
        var reachable = true
        val balances = HashMap<String, Int>()
        val settled = HashMap<String, List<String>>()   // txId -> hops it arrived with
        var settleCalls = 0

        override suspend fun settle(packet: Packet): Settlement {
            settleCalls++
            if (!reachable) return Settlement.Unreachable("bank is down")
            val f = packet.fields
            if (!DeviceKey.verify(packet.payload, packet.sig, packet.payerPubKey)) {
                return Settlement.Rejected("BAD_SIGNATURE", "signature does not verify")
            }
            if (f.payerId != DeviceKey.accountIdFor(packet.payerPubKey)) {
                return Settlement.Rejected("KEY_MISMATCH", "payer does not own the key")
            }
            if (f.txId !in settled) {
                val balance = balances[f.payerId] ?: return Settlement.Rejected("UNKNOWN_ACCOUNT", "no account")
                if (balance < f.amountPaise) return Settlement.Rejected("INSUFFICIENT_FUNDS", "short")
                balances[f.payerId] = balance - f.amountPaise
                balances[f.payeeId] = (balances[f.payeeId] ?: 0) + f.amountPaise
                settled[f.txId] = packet.hops
                return Settlement.Settled(f.txId, balances.getValue(f.payeeId), false, "bank", prove(f.txId, packet))
            }
            return Settlement.Settled(f.txId, balances.getValue(f.payeeId), true, "bank", prove(f.txId, packet))
        }

        fun prove(txId: String, packet: Packet): SignedReceipt {
            val f = packet.fields
            val payload = """{"v":1,"kind":"receipt","txId":"$txId","amountPaise":${f.amountPaise},""" +
                """"payerId":"${f.payerId}","payeeId":"${f.payeeId}","settledAt":1790000000000}"""
            return SignedReceipt(payload, sign(key, payload))
        }
    }

    inner class Phone(val name: String, pinnedKey: String?) {
        val key = newKey()
        val id: String = DeviceKey.accountIdFor(spki(key))
        var pinned: String? = pinnedKey
        var refunded = 0
        val store = PacketStore(tmp.newFile("$name.json").also { it.delete() })
        val book = ReceiptBook()
        val incoming = IncomingLedger(tmp.newFile("$name-in.json").also { it.delete() })
        var credited = 0
        val router = MeshRouter(
            id, store, book, bankKey = { pinned }, refund = { refunded += it },
            // What MeshNode does: credit once per receipt, however often it arrives.
            onPaid = { if (incoming.record(it)) credited += it.fields.amountPaise },
        )

        /** Signs exactly the way Packet.create does, with this phone's key. */
        fun pay(amountPaise: Int, payee: String = "campus-stationery"): Packet {
            val payload = """{"v":1,"txId":"${UUID.randomUUID()}","nonce":"${UUID.randomUUID().toString().take(24)}",""" +
                """"amountPaise":$amountPaise,"payerId":"$id","payeeId":"$payee","createdAt":${System.currentTimeMillis()}}"""
            val packet = Packet(payload, sign(key, payload), spki(key), listOf(id))
            store.offer(packet)
            return packet
        }

        fun state(txId: String) = store.find(txId)?.state
    }

    /** Phones, who can hear whom, and which of them can reach the bank. */
    inner class Mesh(val bank: FakeBank) {
        val phones = LinkedHashMap<String, Phone>()
        private val links = HashSet<Pair<String, String>>()
        val bridges = HashSet<String>()
        var packetTransfers = 0

        fun phone(name: String, pinned: Boolean = true) =
            Phone(name, if (pinned) bank.pubKey else null).also {
                phones[name] = it
                bank.balances[it.id] = 2_000_00
            }

        fun link(a: Phone, b: Phone) {
            links += a.name to b.name
            links += b.name to a.name
        }

        private fun neighbours(p: Phone) = phones.values.filter { (p.name to it.name) in links }

        /** One pump cycle on every phone, in turn. */
        fun round() = runBlocking {
            for (phone in phones.values) {
                val isBridge = phone.name in bridges
                if (isBridge) phone.router.settleAll(bank)

                val packets = phone.router.toForward(isBridge)
                val share = phone.book.fresh()
                for (peer in neighbours(phone)) {
                    val out = phone.router.needs(peer.id, packets, share)
                    if (out.isEmpty) continue
                    // What GattSession writes: each packet with the sender added.
                    out.packets.forEach { peer.router.onPacket(it.withHop(phone.id)) }
                    if (out.receipts.isNotEmpty()) peer.router.onReceipts(out.receipts)
                    packetTransfers += out.packets.size
                    phone.router.record(
                        Exchange(peer.id, packetsSent = out.packets.map { it.txId }, receiptsSent = out.receipts.map { it.txId }),
                        peerAddress = "addr-${peer.name}",
                    )
                }
            }
        }

        fun runUntil(maxRounds: Int = 20, done: () -> Boolean): Int {
            repeat(maxRounds) { i -> if (done()) return i; round() }
            return if (done()) maxRounds else -1
        }
    }

    // ---- behaviour ------------------------------------------------------------

    @Test
    fun `a payment crosses two relays, settles once, and the payer hears back`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A"); val b = mesh.phone("B"); val c = mesh.phone("C"); val d = mesh.phone("D")
        mesh.link(a, b); mesh.link(b, c); mesh.link(c, d)
        mesh.bridges += "D"

        val tx = a.pay(120_00).txId
        val rounds = mesh.runUntil { a.state(tx) == PacketState.SETTLED }

        assertTrue("payer learned the outcome while offline (took $rounds rounds)", rounds >= 0)
        assertEquals(listOf(a.id, b.id, c.id, d.id), mesh.bank.settled[tx])
        assertEquals(2_000_00 - 120_00, mesh.bank.balances[a.id])
        assertNotNull("the payer holds the bank's signed receipt", a.store.find(tx)?.receipt)
        for (relay in listOf(b, c)) assertEquals(PacketState.SETTLED, relay.state(tx))
        assertEquals(0, a.refunded)
    }

    @Test
    fun `copies that reach two bridges settle once`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A"); val b = mesh.phone("B"); val c = mesh.phone("C")
        mesh.link(a, b); mesh.link(a, c)
        mesh.bridges += listOf("B", "C")

        val tx = a.pay(300_00).txId
        mesh.runUntil { a.state(tx) == PacketState.SETTLED }

        assertEquals("both bridges asked the bank", 2, mesh.bank.settleCalls)
        assertEquals("but the money moved once", 300_00, mesh.bank.balances["campus-stationery"])
        assertEquals(PacketState.SETTLED, b.state(tx))
        assertEquals(PacketState.SETTLED, c.state(tx))
    }

    @Test
    fun `two phones out of reach of any bridge do not bounce a packet`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A"); val b = mesh.phone("B")
        mesh.link(a, b)

        val tx = a.pay(50_00).txId
        repeat(25) { mesh.round() }

        assertEquals("A hands it to B once; B never hands it back", 1, mesh.packetTransfers)
        assertEquals(PacketState.FORWARDED, a.state(tx))
        assertEquals(PacketState.HELD, b.state(tx))
    }

    @Test
    fun `a relay cannot tell the payer its payment settled`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A"); val b = mesh.phone("B")
        mesh.link(a, b)
        val packet = a.pay(500_00)

        // B mints a receipt with its own key instead of the bank's.
        val payload = """{"v":1,"kind":"receipt","txId":"${packet.txId}","amountPaise":50000,""" +
            """"payerId":"${a.id}","payeeId":"campus-stationery","settledAt":1790000000000}"""
        val forged = SignedReceipt(payload, sign(b.key, payload))

        val intake = a.router.onReceipts(listOf(forged))

        assertEquals(1, intake.dropped)
        assertEquals(0, intake.settledHere)
        assertEquals(PacketState.HELD, a.state(packet.txId))
    }

    @Test
    fun `a relay that never met the bank passes receipts on without believing them`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A")
        val b = mesh.phone("B", pinned = false)
        val c = mesh.phone("C")
        mesh.link(a, b); mesh.link(b, c)
        mesh.bridges += "C"

        val tx = a.pay(75_00).txId
        val rounds = mesh.runUntil { a.state(tx) == PacketState.SETTLED }

        assertTrue("the receipt still reached the payer", rounds >= 0)
        assertEquals("B could not check it, so B did not act on it", PacketState.FORWARDED, b.state(tx))
    }

    @Test
    fun `a refused payment returns to the payer's wallet once the payer hears it`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A")
        mesh.bank.balances[a.id] = 10_00                     // the bank knows better than the phone

        val tx = a.pay(500_00).txId
        mesh.bridges += "A"                                  // A comes back online
        mesh.round()

        assertEquals(PacketState.REJECTED, a.state(tx))
        assertEquals(500_00, a.refunded)
    }

    @Test
    fun `when the bank is down nothing is lost`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A"); val b = mesh.phone("B")
        mesh.link(a, b)
        mesh.bridges += "B"
        mesh.bank.reachable = false

        val tx = a.pay(40_00).txId
        repeat(5) { mesh.round() }
        assertEquals("held, not dropped", PacketState.HELD, b.state(tx))

        mesh.bank.reachable = true
        mesh.runUntil { a.state(tx) == PacketState.SETTLED }
        assertEquals(PacketState.SETTLED, a.state(tx))
        assertEquals(40_00, mesh.bank.balances["campus-stationery"])
    }

    @Test
    fun `a phone paid by its neighbour sees it at once and is credited once the bank confirms`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A"); val p = mesh.phone("P"); val b = mesh.phone("B"); val c = mesh.phone("C")
        // P is next to A; both offline. Two routes lead to the bridge, so the
        // receipt reaches P more than once.
        mesh.link(a, p); mesh.link(p, b); mesh.link(a, c); mesh.link(c, b)
        mesh.bridges += "B"

        val tx = a.pay(150_00, payee = p.id).txId
        mesh.round()
        assertTrue("P holds the payment before any bank has seen it", p.store.find(tx) != null)

        mesh.runUntil { p.credited > 0 && a.state(tx) == PacketState.SETTLED }
        repeat(5) { mesh.round() }                           // let every copy of the receipt arrive

        assertEquals("credited exactly once", 150_00, p.credited)
        assertEquals(1, p.incoming.received.value.size)
        assertEquals("P's account holds its float plus the payment", 2_000_00 + 150_00, mesh.bank.balances[p.id])
        assertEquals(0, a.credited)
    }

    @Test
    fun `a forged receipt naming the payee credits nothing`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A"); val p = mesh.phone("P")
        val payload = """{"v":1,"kind":"receipt","txId":"${UUID.randomUUID()}","amountPaise":199900,""" +
            """"payerId":"${a.id}","payeeId":"${p.id}","settledAt":1790000000000}"""
        p.router.onReceipts(listOf(SignedReceipt(payload, sign(a.key, payload))))
        assertEquals(0, p.credited)
    }

    @Test
    fun `a payment made and settled on the same phone is not gossiped`() {
        val mesh = Mesh(FakeBank())
        val a = mesh.phone("A"); val b = mesh.phone("B")
        mesh.link(a, b)
        mesh.bridges += "A"                                  // online: pays and settles itself

        val tx = a.pay(100_00).txId
        mesh.round()

        assertEquals(PacketState.SETTLED, a.state(tx))
        assertTrue("nobody else is waiting, so no receipt to pass on", a.book.fresh().isEmpty())

        // Paying a phone is different: the payee is waiting for it.
        val toB = a.pay(50_00, payee = b.id).txId
        mesh.round()
        assertEquals(PacketState.SETTLED, a.state(toB))
        mesh.round()
        assertEquals(50_00, b.credited)
    }

    @Test
    fun `a crowd of phones gets one payment through exactly once`() {
        val mesh = Mesh(FakeBank())
        // A 4x4 grid: each phone hears its horizontal and vertical neighbours.
        val grid = List(4) { r -> List(4) { c -> mesh.phone("P$r$c") } }
        for (r in 0 until 4) for (c in 0 until 4) {
            if (c < 3) mesh.link(grid[r][c], grid[r][c + 1])
            if (r < 3) mesh.link(grid[r][c], grid[r + 1][c])
        }
        mesh.bridges += "P33"                                 // far corner from the payer

        val payer = grid[0][0]
        val tx = payer.pay(200_00).txId
        val rounds = mesh.runUntil(maxRounds = 30) { payer.state(tx) == PacketState.SETTLED }

        assertTrue("settled and confirmed across the grid (took $rounds rounds)", rounds >= 0)
        assertEquals(200_00, mesh.bank.balances["campus-stationery"])
        // Flooding is bounded: each phone passes the packet to each neighbour at most once.
        val directedLinks = 4 * 3 * 2 * 2
        assertTrue("transfers ${mesh.packetTransfers} ≤ links $directedLinks", mesh.packetTransfers <= directedLinks)
    }
}
