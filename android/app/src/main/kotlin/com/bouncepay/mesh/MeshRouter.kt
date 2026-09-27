package com.bouncepay.mesh

import com.bouncepay.bank.Bank
import com.bouncepay.bank.Settlement
import com.bouncepay.ble.Exchange
import com.bouncepay.ble.Outgoing
import com.bouncepay.model.Packet
import com.bouncepay.model.PacketState
import com.bouncepay.model.SignedReceipt
import com.bouncepay.store.PacketStore

/**
 * Every routing decision a node makes, with no radio and no Android in sight.
 *
 * [MeshNode] owns the Bluetooth, the network and the UI; it asks this class
 * what to do. Keeping the decisions here means they can be exercised in plain
 * JVM tests — several routers wired into a topology — which is the only way
 * to test the mesh's behaviour without a drawer full of phones.
 */
class MeshRouter(
    val selfId: String,
    val store: PacketStore,
    val receipts: ReceiptBook,
    /** The bank key this phone has pinned, if it has ever reached the bank. */
    private val bankKey: () -> String?,
    /** Returns spend to this phone's offline wallet. */
    private val refund: (amountPaise: Int) -> Unit,
) {

    /**
     * A packet handed over by a peer.
     *
     * A relay accepts blind. It has no way to judge the payment, so its only
     * job is to keep it safe and keep it moving; verification belongs to the
     * bank. @return true if the packet was new here.
     */
    fun onPacket(packet: Packet): Boolean = store.offer(packet.withHop(selfId))

    data class ReceiptIntake(val news: Boolean, val settledHere: Int, val dropped: Int)

    /**
     * Receipts coming back from the bank's side of the mesh.
     *
     * Checked against the pinned bank key before anything is believed: a
     * relay could otherwise tell a payer its payment went through when it
     * never left the relay's pocket. A phone that has never met the bank
     * cannot check, so it carries receipts on without acting on them.
     */
    fun onReceipts(incoming: List<SignedReceipt>): ReceiptIntake {
        val key = bankKey()
        var news = false
        var settledHere = 0
        var dropped = 0
        for (receipt in incoming) {
            val verified = key != null && receipt.verifiedBy(key)
            if (key != null && !verified) {
                dropped++
                continue
            }
            if (receipts.add(receipt)) news = true
            if (verified && applyReceipt(receipt, "settled · bank receipt came back over the mesh")) settledHere++
        }
        return ReceiptIntake(news, settledHere, dropped)
    }

    /** Marks a held packet settled by [receipt]. True if that changed anything. */
    private fun applyReceipt(receipt: SignedReceipt, note: String): Boolean {
        val held = store.find(receipt.txId) ?: return false
        if (held.state == PacketState.SETTLED && held.receipt != null) return false
        store.markSettled(receipt.txId, note, receipt)
        return true
    }

    /**
     * This node is the bridge: push everything it holds to [bank].
     *
     * Stops at the first [Settlement.Unreachable] — the bank vanishing is not
     * the packets' fault, they stay queued — and reports each outcome to
     * [onEach] so the caller can show progress.
     */
    suspend fun settleAll(bank: Bank, onEach: (Packet, Settlement) -> Unit = { _, _ -> }) {
        for (packet in store.pending().map { it.packet }) {
            val fields = packet.fields
            val result = bank.settle(packet)
            when (result) {
                is Settlement.Settled -> {
                    val transfers = (packet.hops.size - 1).coerceAtLeast(0)
                    store.markSettled(
                        packet.txId,
                        if (result.duplicate) "already settled"
                        else "settled by ${result.source} · $transfers hop(s)",
                        result.proof,
                    )
                    // Send the proof back the way the packet came, so the
                    // payer — still offline — finds out.
                    result.proof?.let { receipts.add(it) }
                }
                is Settlement.Rejected -> {
                    // A refusal is final: carrying it further cannot help.
                    store.markRejected(packet.txId, "${result.code}: ${result.message}")
                    // No money moved, so this phone's own spend comes back.
                    if (fields.payerId == selfId) refund(fields.amountPaise)
                }
                is Settlement.Unreachable -> {
                    onEach(packet, result)
                    return
                }
            }
            onEach(packet, result)
        }
    }

    /** Packets still looking for a way out. A bridge forwards nothing. */
    fun toForward(isBridge: Boolean): List<Packet> =
        if (isBridge) emptyList() else store.pending().map { it.packet }

    /**
     * What one peer still needs from us.
     *
     * Packets whose hop list already names the peer are not offered — that is
     * the anti-loop, and what stops two phones in range of each other passing
     * one back and forth. Receipts go to each peer once. [peerId] is null when
     * the peer's id could not be read, and then everything is offered: the
     * receiving side merges duplicates.
     */
    fun needs(peerId: String?, packets: List<Packet>, toShare: List<SignedReceipt>): Outgoing {
        val forPeer = packets.mapNotNull { original ->
            // Re-read: a hand-off or a receipt earlier this pass may have
            // changed it.
            store.find(original.txId)
                ?.takeIf { it.state == PacketState.HELD || it.state == PacketState.FORWARDED }
                ?.packet
                ?.takeIf { peerId == null || peerId !in it.hops }
        }
        val receiptsForPeer = toShare.filter { peerId == null || !receipts.wasSentTo(it.txId, peerId) }
        return Outgoing(forPeer, receiptsForPeer)
    }

    /**
     * Records exactly what got through, which may be less than was planned.
     * A hand-off adds the peer to the packet's hops; the packet stays pending,
     * because handing it on is not proof it settled.
     */
    fun record(result: Exchange, peerAddress: String) {
        val peer = result.peerId ?: peerAddress
        for (txId in result.packetsSent) store.recordHandoff(txId, peer)
        result.peerId?.let { id -> result.receiptsSent.forEach { receipts.markSent(it, id) } }
    }
}
