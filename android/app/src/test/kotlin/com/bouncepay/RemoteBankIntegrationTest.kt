package com.bouncepay

import com.bouncepay.bank.RemoteBank
import com.bouncepay.bank.Settlement
import com.bouncepay.crypto.DeviceKey
import com.bouncepay.model.Packet
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * The phone's bank client against the real mock bank, over real HTTP.
 *
 * Starts `node mock-bank/src/server.js` on a free port and drives it with
 * [RemoteBank] — the exact code a bridge phone runs — so field names, status
 * codes and the receipt proof are checked end to end, not just on each side.
 * Skipped if Node is not installed.
 */
class RemoteBankIntegrationTest {

    companion object {
        private var server: Process? = null
        private lateinit var url: String

        @BeforeClass @JvmStatic
        fun startBank() {
            val hasNode = runCatching {
                ProcessBuilder("node", "--version").start().waitFor() == 0
            }.getOrDefault(false)
            assumeTrue("node is not installed; skipping bank integration", hasNode)

            val port = ServerSocket(0).use { it.localPort }
            url = "http://127.0.0.1:$port"
            val scratch = File(System.getProperty("java.io.tmpdir"), "bouncepay-it-${UUID.randomUUID()}").apply { mkdirs() }

            server = ProcessBuilder("node", "src/server.js")
                .directory(File("../../mock-bank"))
                .redirectErrorStream(true)
                .redirectOutput(File(scratch, "bank.log"))
                .apply {
                    environment()["PORT"] = port.toString()
                    // Keep the test's signing key out of the repo.
                    environment()["BANK_KEY"] = File(scratch, "bank-key.pem").absolutePath
                }
                .start()

            val ready = runBlocking {
                repeat(50) {
                    if (RemoteBank(url).health() != null) return@runBlocking true
                    Thread.sleep(200)
                }
                false
            }
            check(ready) { "mock bank did not start: " + File(scratch, "bank.log").readText() }
        }

        @AfterClass @JvmStatic
        fun stopBank() {
            server?.destroy()
        }
    }

    private val bank get() = RemoteBank(url)

    private fun newKey(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    private fun spki(key: KeyPair) = Base64.getEncoder().encodeToString(key.public.encoded)

    /** Signs the way Packet.create does. */
    private fun packet(key: KeyPair, amountPaise: Int, hops: List<String> = emptyList()): Packet {
        val payerId = DeviceKey.accountIdFor(spki(key))
        val payload = """{"v":1,"txId":"${UUID.randomUUID()}","nonce":"${UUID.randomUUID().toString().replace("-", "").take(24)}",""" +
            """"amountPaise":$amountPaise,"payerId":"$payerId","payeeId":"campus-stationery","createdAt":${System.currentTimeMillis()}}"""
        val sig = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(payload.toByteArray()); sign()
        })
        return Packet(payload, sig, spki(key), listOf(payerId) + hops)
    }

    @Test
    fun `health hands out the bank key and enrolment agrees with it`() = runBlocking {
        val bankKey = bank.health()
        assertTrue(!bankKey.isNullOrBlank())

        val key = newKey()
        val enrolment = bank.enroll(1_500_00, payerPubKey = spki(key), label = "JVM test").getOrThrow()
        assertEquals(DeviceKey.accountIdFor(spki(key)), enrolment.accountId)
        assertEquals(1_500_00, enrolment.balancePaise)
        assertEquals(bankKey, enrolment.bankPubKey)
    }

    @Test
    fun `a bridge settles, gets a proof it can verify, and a repeat is a duplicate`() = runBlocking {
        val bankKey = bank.health()!!
        val key = newKey()
        bank.enroll(2_000_00, payerPubKey = spki(key), label = "payer").getOrThrow()

        val p = packet(key, 250_00, hops = listOf("bp_relay00000001", "bp_bridge0000001"))
        val first = bank.settle(p)
        assertTrue("expected Settled, got $first", first is Settlement.Settled)
        first as Settlement.Settled
        assertFalse(first.duplicate)
        assertEquals(p.txId, first.txId)

        val proof = first.proof
        assertNotNull("settle responses carry a signed proof", proof)
        proof!!
        assertTrue("the proof verifies with the bank's key", proof.verifiedBy(bankKey))
        assertEquals(p.txId, proof.txId)
        assertEquals(250_00, proof.fields.amountPaise)

        val again = bank.settle(p) as Settlement.Settled
        assertTrue(again.duplicate)
        assertEquals(first.payeeBalancePaise, again.payeeBalancePaise)
    }

    @Test
    fun `refusals come back with the bank's codes`() = runBlocking {
        val key = newKey()
        bank.enroll(100_00, payerPubKey = spki(key), label = "small").getOrThrow()

        val tampered = packet(key, 50_00).let { it.copy(payload = it.payload.replace("5000", "9000")) }
        assertEquals("BAD_SIGNATURE", (bank.settle(tampered) as Settlement.Rejected).code)

        assertEquals("INSUFFICIENT_FUNDS", (bank.settle(packet(key, 150_00)) as Settlement.Rejected).code)

        val stranger = newKey()   // never enrolled
        assertEquals("UNKNOWN_ACCOUNT", (bank.settle(packet(stranger, 10_00)) as Settlement.Rejected).code)
    }

    @Test
    fun `a bank that is not there is unreachable, not a refusal`() = runBlocking {
        val deadPort = ServerSocket(0).use { it.localPort }
        val nowhere = RemoteBank("http://127.0.0.1:$deadPort")
        assertNull(nowhere.health())
        assertTrue(nowhere.settle(packet(newKey(), 10_00)) is Settlement.Unreachable)
    }
}
