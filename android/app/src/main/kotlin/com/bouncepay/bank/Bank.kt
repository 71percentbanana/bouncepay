package com.bouncepay.bank

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bouncepay.crypto.DeviceKey
import com.bouncepay.model.Packet
import com.bouncepay.model.SignedReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of handing a packet to a settlement authority. */
sealed interface Settlement {
    data class Settled(
        val txId: String,
        val payeeBalancePaise: Int,
        val duplicate: Boolean,
        /** Which bank took it — shown to the user so a fallback is never hidden. */
        val source: String,
        /** Bank-signed, so it can be passed back through the mesh. Null from the fallback. */
        val proof: SignedReceipt? = null,
    ) : Settlement

    data class Rejected(val code: String, val message: String) : Settlement
    data class Unreachable(val why: String) : Settlement
}

interface Bank {
    val label: String
    suspend fun settle(packet: Packet): Settlement
}

/**
 * True when this device has *a* network — enough to be the mesh's bridge.
 *
 * Deliberately does not require NET_CAPABILITY_VALIDATED. On demo day the bank
 * runs on a laptop on a local Wi-Fi or hotspot that often has no route to the
 * wider internet; Android marks that unvalidated, and a stricter check would
 * leave the bridge convinced it is offline while the bank sits one hop away.
 * Whether the bank is actually reachable is decided by asking it.
 */
fun Context.hasNetwork(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

data class Enrollment(val accountId: String, val balancePaise: Int, val bankPubKey: String?)

/**
 * The settlement service in mock-bank/.
 *
 * Only ever called by whichever device happens to have a network — the
 * bridge. Plain HttpURLConnection keeps the app free of a networking
 * dependency for a handful of requests.
 */
class RemoteBank(private val baseUrl: String) : Bank {

    override val label = "Bank at $baseUrl"

    /**
     * Cheap liveness check, so a bridge can decide quickly between banks.
     * Returns the bank's receipt-signing key, or null if it did not answer.
     */
    suspend fun health(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(baseUrl.trimEnd('/') + "/v1/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = REACHABILITY_TIMEOUT_MS
                readTimeout = REACHABILITY_TIMEOUT_MS
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val body = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                if (!body.optBoolean("ok")) null else body.optString("bankPubKey")
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Opens this device's account and loads its offline wallet.
     *
     * Has to happen while online — the whole point is that the payer is
     * offline at the moment of paying, so its account must already exist.
     */
    suspend fun enroll(
        openingPaise: Int,
        payerPubKey: String = DeviceKey.publicKeySpki(),
        label: String = android.os.Build.MODEL ?: "Android",
    ): Result<Enrollment> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, json) = post("/v1/enroll", JSONObject().apply {
                put("payerPubKey", payerPubKey)
                put("label", label)
                put("openingPaise", openingPaise)
            }.toString())
            check(code in 200..299) { json?.optString("message") ?: "enrolment refused ($code)" }
            Enrollment(
                accountId = json!!.getString("accountId"),
                balancePaise = json.getJSONObject("account").getInt("balancePaise"),
                bankPubKey = json.optString("bankPubKey").ifBlank { null },
            )
        }
    }

    override suspend fun settle(packet: Packet): Settlement = withContext(Dispatchers.IO) {
        try {
            val (code, json) = post("/v1/settle", packet.toJson())
            if (code in 200..299 && json != null) {
                val receipt = json.optJSONObject("receipt")
                Settlement.Settled(
                    txId = receipt?.optString("txId") ?: packet.txId,
                    payeeBalancePaise = receipt?.optInt("payeeBalancePaise") ?: 0,
                    duplicate = json.optString("status") == "DUPLICATE",
                    source = "bank",
                    proof = json.optJSONObject("proof")?.let { runCatching { SignedReceipt.fromJsonObject(it) }.getOrNull() },
                )
            } else {
                Settlement.Rejected(
                    code = json?.optString("code")?.ifBlank { null } ?: "HTTP_$code",
                    message = json?.optString("message")?.ifBlank { null } ?: "settlement refused",
                )
            }
        } catch (e: Exception) {
            // Not a rejection: the packet is still good, there is just no way
            // through right now. It stays queued and is retried.
            Settlement.Unreachable(e.message ?: "no route to the bank")
        }
    }

    private fun post(path: String, body: String): Pair<Int, JSONObject?> {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("content-type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to runCatching { JSONObject(text) }.getOrNull()
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val REACHABILITY_TIMEOUT_MS = 2_500
        const val REQUEST_TIMEOUT_MS = 8_000
    }
}

/**
 * The on-device fallback.
 *
 * Applies the service's packet rules with the same refusal codes — the
 * signature is really verified, the payer is really bound to the signing key,
 * stale packets expire, a reused nonce is refused — and remembers every txId
 * and nonce on disk so those guarantees survive a restart. What it cannot do
 * is check the payer's balance: it has no ledger of payers, only the
 * merchant's.
 *
 * It is only consulted by a device acting as the bridge when the real bank
 * does not answer, and every settlement it makes is labelled as such.
 */
class EmbeddedBank(context: Context) : Bank {

    override val label = "On-device bank"

    private val file = File(context.filesDir, "embedded-bank.json")
    private val lock = Any()

    private val settled = HashMap<String, Int>()   // txId -> payee balance afterwards
    private val nonces = HashSet<String>()
    private var merchantBalancePaise = 2_000_00

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val o = JSONObject(file.readText())
            merchantBalancePaise = o.optInt("merchantBalancePaise", merchantBalancePaise)
            o.optJSONObject("settled")?.let { s -> s.keys().forEach { settled[it] = s.getInt(it) } }
            o.optJSONArray("nonces")?.let { n -> (0 until n.length()).forEach { nonces.add(n.getString(it)) } }
        }
    }

    override suspend fun settle(packet: Packet): Settlement = synchronized(lock) {
        val fields = runCatching { packet.fields }.getOrElse {
            return Settlement.Rejected("MALFORMED", "payload is not readable")
        }
        if (fields.v != Packet.PROTOCOL_VERSION) {
            return Settlement.Rejected("UNSUPPORTED_VERSION", "version ${fields.v}")
        }
        if (fields.amountPaise <= 0) {
            return Settlement.Rejected("INVALID_AMOUNT", "amount must be positive")
        }
        if (fields.amountPaise > Packet.MAX_AMOUNT_PAISE) {
            return Settlement.Rejected("AMOUNT_EXCEEDS_LIMIT", "over the offline ceiling")
        }
        if (!DeviceKey.verify(packet.payload, packet.sig, packet.payerPubKey)) {
            return Settlement.Rejected("BAD_SIGNATURE", "signature does not verify")
        }
        if (fields.payerId != DeviceKey.accountIdFor(packet.payerPubKey)) {
            return Settlement.Rejected("KEY_MISMATCH", "payer does not own the signing key")
        }
        val age = System.currentTimeMillis() - fields.createdAt
        if (age > MAX_AGE_MS) {
            return Settlement.Rejected("EXPIRED", "packet is ${age / 86_400_000} days old")
        }
        if (age < -FUTURE_SKEW_MS) {
            return Settlement.Rejected("FUTURE_DATED", "packet is dated in the future beyond clock skew")
        }

        settled[fields.txId]?.let {
            return Settlement.Settled(fields.txId, it, duplicate = true, source = "on-device fallback")
        }
        if (nonces.contains(fields.nonce)) {
            return Settlement.Rejected("REPLAYED_NONCE", "nonce already used")
        }

        merchantBalancePaise += fields.amountPaise
        settled[fields.txId] = merchantBalancePaise
        nonces.add(fields.nonce)
        persist()

        Settlement.Settled(fields.txId, merchantBalancePaise, duplicate = false, source = "on-device fallback")
    }

    private companion object {
        /** Mirrors mock-bank/src/packet.js. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val FUTURE_SKEW_MS = 5L * 60 * 1000
    }

    private fun persist() {
        runCatching {
            file.writeText(JSONObject().apply {
                put("merchantBalancePaise", merchantBalancePaise)
                put("settled", JSONObject(settled as Map<*, *>))
                put("nonces", JSONArray(nonces.toList()))
            }.toString())
        }
    }
}
