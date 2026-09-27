package com.bouncepay.model

import com.bouncepay.crypto.DeviceKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * A payment authorised offline.
 *
 * [payload] is the signed material and travels as an opaque string. It is
 * never re-serialised on the way through the mesh or on the server, because
 * two platforms will not produce byte-identical JSON from the same object and
 * every signature would then fail verification. Sign the string, carry the
 * string, verify the string.
 *
 * [hops] is provenance, deliberately *outside* the signature: each relay
 * appends itself, so the envelope changes in flight while the authorisation
 * underneath it cannot.
 */
data class Packet(
    val payload: String,
    val sig: String,
    val payerPubKey: String,
    val hops: List<String> = emptyList(),
) {
    val fields: PacketFields by lazy { PacketFields.parse(payload) }

    val txId: String get() = fields.txId

    fun withHop(deviceId: String): Packet =
        if (hops.contains(deviceId)) this else copy(hops = hops + deviceId)

    fun toJson(): String = JSONObject().apply {
        put("payload", payload)
        put("sig", sig)
        put("payerPubKey", payerPubKey)
        put("hops", JSONArray(hops))
    }.toString()

    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        const val PROTOCOL_VERSION = 1

        /** Offline spend ceiling, mirrored by the settlement service. */
        const val MAX_AMOUNT_PAISE = 2_000_00

        fun fromJson(json: String): Packet {
            val o = JSONObject(json)
            val hopsArray = o.optJSONArray("hops") ?: JSONArray()
            return Packet(
                payload = o.getString("payload"),
                sig = o.getString("sig"),
                payerPubKey = o.getString("payerPubKey"),
                hops = (0 until hopsArray.length()).map { hopsArray.getString(it) },
            )
        }

        fun fromBytes(bytes: ByteArray): Packet = fromJson(String(bytes, Charsets.UTF_8))

        /**
         * Builds and signs a payment on this device. Requires no network.
         *
         * The payload is assembled with explicit string concatenation rather
         * than JSONObject, because JSONObject makes no ordering guarantee and
         * the exact bytes here are what the signature commits to.
         */
        fun create(amountPaise: Int, payeeId: String): Packet {
            require(amountPaise > 0) { "amount must be positive" }
            require(amountPaise <= MAX_AMOUNT_PAISE) { "amount exceeds the offline ceiling" }

            val payerId = DeviceKey.accountId()
            val nonce = ByteArray(12).also { Random.nextBytes(it) }
                .joinToString("") { "%02x".format(it) }

            val payload = buildString {
                append("{")
                append("\"v\":").append(PROTOCOL_VERSION).append(",")
                append("\"txId\":\"").append(UUID.randomUUID()).append("\",")
                append("\"nonce\":\"").append(nonce).append("\",")
                append("\"amountPaise\":").append(amountPaise).append(",")
                append("\"payerId\":\"").append(payerId).append("\",")
                append("\"payeeId\":\"").append(payeeId).append("\",")
                append("\"createdAt\":").append(System.currentTimeMillis())
                append("}")
            }

            return Packet(
                payload = payload,
                sig = DeviceKey.sign(payload),
                payerPubKey = DeviceKey.publicKeySpki(),
                hops = listOf(payerId),
            )
        }
    }
}

/** The parsed view of a signed payload. */
data class PacketFields(
    val v: Int,
    val txId: String,
    val nonce: String,
    val amountPaise: Int,
    val payerId: String,
    val payeeId: String,
    val createdAt: Long,
) {
    val rupees: String get() = "₹%,.2f".format(amountPaise / 100.0)

    companion object {
        fun parse(payload: String): PacketFields {
            val o = JSONObject(payload)
            return PacketFields(
                v = o.getInt("v"),
                txId = o.getString("txId"),
                nonce = o.getString("nonce"),
                amountPaise = o.getInt("amountPaise"),
                payerId = o.getString("payerId"),
                payeeId = o.getString("payeeId"),
                createdAt = o.getLong("createdAt"),
            )
        }
    }
}

/** Where a stored packet is in its life. */
enum class PacketState {
    /** Signed here, or received from a peer, and waiting for a way out. */
    HELD,

    /** Handed to at least one peer; kept until settlement is confirmed. */
    FORWARDED,

    /** The bank acknowledged it. Terminal. */
    SETTLED,

    /** The bank refused it. Terminal; carries the reason. */
    REJECTED,
}

data class StoredPacket(
    val packet: Packet,
    val state: PacketState,
    val receivedAt: Long,
    val note: String? = null,
)
