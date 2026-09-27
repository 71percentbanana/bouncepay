package com.bouncepay.ble

import java.util.UUID

/**
 * The wire contract every BouncePay device implements.
 *
 * Each device runs both roles at once: a GATT *server* so peers can hand it
 * packets, and a GATT *client* so it can hand packets on. That symmetry is
 * what makes it a mesh rather than a set of clients talking to one hub.
 */
object BleIds {

    /** Advertised so peers can find each other without pairing. */
    val SERVICE: UUID = UUID.fromString("b0cce000-9a11-4e6d-9f2c-000000000001")

    /** Write: a payment packet, in chunks. See [Chunking]. */
    val CHAR_PACKET_IN: UUID = UUID.fromString("b0cce000-9a11-4e6d-9f2c-000000000002")

    /**
     * Read: the peer's account id.
     *
     * A sender reads this *before* transferring so it can skip peers already
     * in the packet's hop list. Without it a packet ping-pongs between two
     * phones in range of each other and never leaves.
     */
    val CHAR_DEVICE_ID: UUID = UUID.fromString("b0cce000-9a11-4e6d-9f2c-000000000003")

    /**
     * Write: a batch of bank-signed receipts, chunked like packets.
     *
     * Receipts flow the other way from payments — from the bridge back
     * towards the payer — so an offline payer learns its payment settled.
     */
    val CHAR_RECEIPT_IN: UUID = UUID.fromString("b0cce000-9a11-4e6d-9f2c-000000000004")

    /** Standard descriptor id, needed when a characteristic supports notify. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Largest MTU worth asking for; Android caps at 517. */
    const val DESIRED_MTU = 517
}

/**
 * Splitting packets across BLE writes.
 *
 * A signed packet is roughly 700–900 bytes — a signature, a public key and the
 * payload — which comfortably exceeds even a negotiated MTU. So each transfer
 * is framed as a short sequence of chunks:
 *
 *     [chunkIndex: u16 BE][chunkCount: u16 BE][data …]
 *
 * The index is carried explicitly rather than relying on arrival order,
 * because a GATT stack is free to reorder writes and a silently mis-assembled
 * packet would fail signature verification with no useful diagnosis.
 */
object Chunking {

    const val HEADER_BYTES = 4

    /** ATT reserves 3 bytes of the MTU for its own header. */
    fun payloadPerChunk(mtu: Int): Int = (mtu - 3 - HEADER_BYTES).coerceAtLeast(20)

    fun split(data: ByteArray, mtu: Int): List<ByteArray> {
        val perChunk = payloadPerChunk(mtu)
        val count = ((data.size + perChunk - 1) / perChunk).coerceAtLeast(1)
        require(count <= 0xFFFF) { "packet too large to frame" }

        return (0 until count).map { index ->
            val start = index * perChunk
            val end = minOf(start + perChunk, data.size)
            val body = data.copyOfRange(start, end)
            ByteArray(HEADER_BYTES + body.size).also { frame ->
                frame[0] = (index shr 8).toByte()
                frame[1] = index.toByte()
                frame[2] = (count shr 8).toByte()
                frame[3] = count.toByte()
                body.copyInto(frame, HEADER_BYTES)
            }
        }
    }

    /** Reassembles one peer's chunks. One instance per connected device. */
    class Assembler {
        private val received = HashMap<Int, ByteArray>()
        private var expected = -1

        /** @return the complete packet bytes once the last chunk lands. */
        fun accept(frame: ByteArray): ByteArray? {
            if (frame.size < HEADER_BYTES) return null

            val index = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
            val count = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
            if (count <= 0) return null

            // A different count means a new transfer began; start over rather
            // than blending two packets into nonsense.
            if (expected != count) {
                received.clear()
                expected = count
            }

            received[index] = frame.copyOfRange(HEADER_BYTES, frame.size)
            if (received.size < count) return null

            val assembled = (0 until count).fold(ByteArray(0)) { acc, i ->
                acc + (received[i] ?: return null)
            }
            reset()
            return assembled
        }

        fun reset() {
            received.clear()
            expected = -1
        }
    }
}
