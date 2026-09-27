package com.bouncepay.model

import com.bouncepay.crypto.DeviceKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bank's word that a payment settled, signed so it can travel back to an
 * offline payer through phones nobody trusts.
 *
 * Like a packet payload, [payload] is an opaque string: produced by the bank,
 * carried as-is, verified as-is.
 */
data class SignedReceipt(val payload: String, val sig: String) {

    val fields: ReceiptFields by lazy { ReceiptFields.parse(payload) }

    val txId: String get() = fields.txId

    /** True only if the bank's key signed exactly this payload. */
    fun verifiedBy(bankKeySpki: String): Boolean =
        runCatching { fields.kind == "receipt" && DeviceKey.verify(payload, sig, bankKeySpki) }
            .getOrDefault(false)

    fun toJsonObject(): JSONObject = JSONObject().put("payload", payload).put("sig", sig)

    companion object {
        fun fromJsonObject(o: JSONObject) = SignedReceipt(o.getString("payload"), o.getString("sig"))

        /** Several receipts in one BLE transfer: `{"receipts":[…]}`. */
        fun encodeBatch(receipts: List<SignedReceipt>): ByteArray =
            JSONObject().put("receipts", JSONArray(receipts.map { it.toJsonObject() }))
                .toString().toByteArray(Charsets.UTF_8)

        fun decodeBatch(bytes: ByteArray): List<SignedReceipt> {
            val array = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("receipts")
            return (0 until array.length()).map { fromJsonObject(array.getJSONObject(it)) }
        }
    }
}

data class ReceiptFields(
    val kind: String,
    val txId: String,
    val amountPaise: Int,
    val payerId: String,
    val payeeId: String,
    val settledAt: Long,
) {
    companion object {
        fun parse(payload: String): ReceiptFields {
            val o = JSONObject(payload)
            return ReceiptFields(
                kind = o.getString("kind"),
                txId = o.getString("txId"),
                amountPaise = o.getInt("amountPaise"),
                payerId = o.getString("payerId"),
                payeeId = o.getString("payeeId"),
                settledAt = o.getLong("settledAt"),
            )
        }
    }
}
