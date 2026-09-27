package com.bouncepay.store

import android.content.Context
import com.bouncepay.model.SignedReceipt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * Money this phone has received, as proven by the bank.
 *
 * Only a bank-signed receipt naming this phone as payee gets in, and each
 * txId only once — the same receipt arrives from several neighbours, and
 * crediting it twice would let the payee spend money that does not exist.
 */
class IncomingLedger(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "incoming.json"))

    private val lock = Any()
    private val _received = MutableStateFlow(read())
    val received: StateFlow<List<SignedReceipt>> = _received.asStateFlow()

    /** @return true if this is a payment not seen before. */
    fun record(receipt: SignedReceipt): Boolean = synchronized(lock) {
        if (_received.value.any { it.txId == receipt.txId }) return false
        _received.value = _received.value + receipt
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(JSONArray(_received.value.map { it.toJsonObject() }).toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
        true
    }

    private fun read(): List<SignedReceipt> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            runCatching { SignedReceipt.fromJsonObject(array.getJSONObject(i)) }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
