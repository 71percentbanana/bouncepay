package com.bouncepay.ble

import com.bouncepay.model.Packet
import com.bouncepay.model.SignedReceipt

/** What to hand one peer, decided once its account id is known. */
data class Outgoing(val packets: List<Packet>, val receipts: List<SignedReceipt>) {
    val isEmpty: Boolean get() = packets.isEmpty() && receipts.isEmpty()
}

/**
 * What happened in one connection. Partial success is normal — a peer can
 * walk out of range between two packets — so what got through is reported
 * alongside any error, and the caller records exactly that.
 */
data class Exchange(
    val peerId: String?,
    /** What the peer calls itself, if it said. */
    val peerName: String? = null,
    val packetsSent: List<String> = emptyList(),
    val receiptsSent: List<String> = emptyList(),
    val error: String? = null,
)
