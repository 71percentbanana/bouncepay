package com.bouncepay.mesh

import com.bouncepay.model.SignedReceipt
import java.util.concurrent.ConcurrentHashMap

/**
 * Receipts on their way back to payers.
 *
 * Gossip, bounded: each receipt is offered to each phone once, and only for a
 * while after this phone first saw it. That is long enough to reach a payer a
 * few hops away and short enough that a busy bridge is not scanning for ever.
 * Held in memory — if the phone restarts, the payer will still learn the
 * outcome the next time it is online, from the bank directly.
 */
class ReceiptBook(
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val receipt: SignedReceipt, val firstSeen: Long) {
        val sentTo: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** @return true if this receipt was new here. */
    fun add(receipt: SignedReceipt): Boolean =
        entries.putIfAbsent(receipt.txId, Entry(receipt, clock())) == null

    /** Receipts still worth passing on; forgets the rest. */
    fun fresh(): List<SignedReceipt> {
        val now = clock()
        entries.entries.removeIf { now - it.value.firstSeen > ttlMs }
        return entries.values.map { it.receipt }
    }

    fun wasSentTo(txId: String, peerId: String): Boolean =
        entries[txId]?.sentTo?.contains(peerId) == true

    fun markSent(txId: String, peerId: String) {
        entries[txId]?.sentTo?.add(peerId)
    }

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
    }
}
