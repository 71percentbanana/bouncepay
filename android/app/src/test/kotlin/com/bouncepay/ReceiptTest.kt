package com.bouncepay

import com.bouncepay.mesh.ReceiptBook
import com.bouncepay.model.SignedReceipt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Receipts travel back through phones nobody trusts, so the only thing that
 * makes one believable is the bank's signature.
 *
 * The fixture was signed by the mock bank's own code (mock-bank/src/receipts.js)
 * and is shared with the Node suite, which checks the bank still produces
 * that exact format. Here the phone's code has to accept it — and nothing else.
 */
class ReceiptTest {

    private val fixture = JSONObject(File("../../mock-bank/test/fixtures/receipt.json").readText())
    private val bankKey = fixture.getString("bankPubKey")
    private val receipt = SignedReceipt.fromJsonObject(fixture.getJSONObject("proof"))

    @Test
    fun `a receipt signed by the bank verifies on the phone`() {
        assertTrue(receipt.verifiedBy(bankKey))
        assertEquals("7c1e2f3a-0000-4000-8000-000000000001", receipt.txId)
        assertEquals(10000, receipt.fields.amountPaise)
    }

    @Test
    fun `a relay cannot alter a receipt`() {
        val forged = receipt.copy(payload = receipt.payload.replace("10000", "90000"))
        assertFalse(forged.verifiedBy(bankKey))
    }

    @Test
    fun `a receipt signed by anyone else is not believed`() {
        // A genuine P-256 key, so this fails on the signature, not on parsing.
        val other = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val otherKey = Base64.getEncoder().encodeToString(other.public.encoded)
        assertFalse(receipt.verifiedBy(otherKey))
        assertFalse(receipt.verifiedBy("not a key"))
    }

    @Test
    fun `a batch survives the trip over BLE`() {
        val batch = SignedReceipt.decodeBatch(SignedReceipt.encodeBatch(listOf(receipt, receipt)))
        assertEquals(2, batch.size)
        assertEquals(receipt.payload, batch[0].payload)
        assertTrue(batch[1].verifiedBy(bankKey))
    }

    @Test
    fun `each receipt goes to each phone once, and only while fresh`() {
        var now = 0L
        val book = ReceiptBook(ttlMs = 1_000, clock = { now })

        assertTrue(book.add(receipt))
        assertFalse("seeing it again is not news", book.add(receipt))

        book.markSent(receipt.txId, "bp_peer")
        assertTrue(book.wasSentTo(receipt.txId, "bp_peer"))
        assertFalse(book.wasSentTo(receipt.txId, "bp_other"))
        assertEquals(1, book.fresh().size)

        now = 1_001
        assertEquals("forgotten after its time is up", 0, book.fresh().size)
    }
}
