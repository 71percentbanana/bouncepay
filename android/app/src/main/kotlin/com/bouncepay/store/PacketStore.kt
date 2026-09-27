package com.bouncepay.store

import android.content.Context
import com.bouncepay.model.Packet
import com.bouncepay.model.PacketState
import com.bouncepay.model.SignedReceipt
import com.bouncepay.model.StoredPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The store in store-and-forward.
 *
 * A packet handed to this device is this device's responsibility until the
 * bank confirms it, and that may be hours later and across a restart — the
 * whole point is that the relay can be switched off, put in a pocket and
 * carried somewhere with signal. So the queue is written to disk on every
 * change, not held in memory.
 *
 * Writes go to a temp file and are then renamed, so a process death midway
 * through leaves the previous good queue rather than a truncated one.
 *
 * Backed by a file rather than Room on purpose: no annotation processor, no
 * schema migrations, and the whole thing is inspectable with `adb shell cat`
 * during a demo. The interface is narrow enough to swap for Room later.
 */
class PacketStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "packet-queue.json"))
    private val lock = Any()

    private val _packets = MutableStateFlow<List<StoredPacket>>(emptyList())
    val packets: StateFlow<List<StoredPacket>> = _packets.asStateFlow()

    init {
        synchronized(lock) { _packets.value = readFromDisk() }
    }

    /** Everything still worth trying to move. */
    fun pending(): List<StoredPacket> =
        _packets.value.filter { it.state == PacketState.HELD || it.state == PacketState.FORWARDED }

    fun contains(txId: String): Boolean = _packets.value.any { it.packet.txId == txId }

    /**
     * Adds a packet, or merges hop provenance if it is already held.
     *
     * The same packet legitimately arrives more than once — relays forward
     * opportunistically and none of them knows the others succeeded — so a
     * repeat is normal traffic, not an error. Returns true only when this is
     * genuinely new, which is what decides whether to re-broadcast.
     */
    fun offer(packet: Packet): Boolean = synchronized(lock) {
        val current = _packets.value
        val existing = current.firstOrNull { it.packet.txId == packet.txId }

        if (existing != null) {
            val mergedHops = (existing.packet.hops + packet.hops).distinct()
            if (mergedHops.size != existing.packet.hops.size) {
                replace(existing.copy(packet = existing.packet.copy(hops = mergedHops)))
            }
            return false
        }

        _packets.value = current + StoredPacket(
            packet = packet,
            state = PacketState.HELD,
            receivedAt = System.currentTimeMillis(),
        )
        persist()
        true
    }

    /**
     * Records that [peerId] now holds a copy.
     *
     * The peer is added to the hop list, not just noted: the hop list is what
     * the sender checks before connecting, so without this a relay would hand
     * the same packet to the same neighbour on every cycle, forever.
     *
     * The packet stays pending — handing it on is not proof it settled.
     */
    fun recordHandoff(txId: String, peerId: String) = update(txId) {
        it.copy(
            packet = it.packet.withHop(peerId),
            state = PacketState.FORWARDED,
            note = "handed to $peerId",
        )
    }

    fun markSettled(txId: String, note: String?, receipt: SignedReceipt? = null) = update(txId) {
        it.copy(state = PacketState.SETTLED, note = note, receipt = receipt ?: it.receipt)
    }

    fun find(txId: String): StoredPacket? = _packets.value.firstOrNull { it.packet.txId == txId }

    fun markRejected(txId: String, reason: String) = update(txId) {
        it.copy(state = PacketState.REJECTED, note = reason)
    }

    fun clearTerminal() = synchronized(lock) {
        _packets.value = _packets.value.filter {
            it.state != PacketState.SETTLED && it.state != PacketState.REJECTED
        }
        persist()
    }

    private fun update(txId: String, transform: (StoredPacket) -> StoredPacket) =
        synchronized(lock) {
            val target = _packets.value.firstOrNull { it.packet.txId == txId } ?: return
            replace(transform(target))
        }

    private fun replace(updated: StoredPacket) {
        _packets.value = _packets.value.map {
            if (it.packet.txId == updated.packet.txId) updated else it
        }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        for (stored in _packets.value) {
            array.put(JSONObject().apply {
                put("packet", JSONObject(stored.packet.toJson()))
                put("state", stored.state.name)
                put("receivedAt", stored.receivedAt)
                stored.note?.let { put("note", it) }
                stored.receipt?.let { put("receipt", it.toJsonObject()) }
            })
        }
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.writeText(array.toString())
                temp.delete()
            }
        }
    }

    private fun readFromDisk(): List<StoredPacket> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                StoredPacket(
                    packet = Packet.fromJson(o.getJSONObject("packet").toString()),
                    state = PacketState.valueOf(o.getString("state")),
                    receivedAt = o.getLong("receivedAt"),
                    note = o.optString("note").ifBlank { null },
                    receipt = o.optJSONObject("receipt")?.let { SignedReceipt.fromJsonObject(it) },
                )
            }.getOrNull()   // one corrupt entry must not lose the whole queue
        }
    }.getOrDefault(emptyList())
}
