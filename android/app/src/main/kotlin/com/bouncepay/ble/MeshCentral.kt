package com.bouncepay.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.bouncepay.model.Packet
import com.bouncepay.model.SignedReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class Peer(val device: BluetoothDevice, val rssi: Int) {
    val address: String get() = device.address
}

/**
 * The sending half of a mesh node.
 *
 * Scans for peers advertising the BouncePay service, then connects and writes
 * a packet across.
 *
 * GATT is strictly one-operation-at-a-time: issuing a second write before the
 * first has called back silently drops it. Every step here therefore awaits
 * its callback before the next, which is why the whole thing is suspending
 * rather than fire-and-forget.
 */
@SuppressLint("MissingPermission")   // callers gate on BLUETOOTH_* at runtime
class MeshCentral(
    private val context: Context,
    private val deviceId: String,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter

    /** Collects peers advertising our service for [durationMs]. */
    suspend fun scan(durationMs: Long = 4_000): List<Peer> {
        val scanner = adapter?.bluetoothLeScanner ?: return emptyList()
        val found = LinkedHashMap<String, Peer>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found[result.device.address] = Peer(result.device, result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
            }
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleIds.SERVICE)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return try {
            scanner.startScan(filters, settings, callback)
            delay(durationMs)
            // Strongest signal first: the closest phone is the most likely to
            // still be there when the transfer finishes.
            found.values.sortedByDescending { it.rssi }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /**
     * Connects to [peer], learns who it is, and hands over whatever [plan]
     * says that peer still needs. [plan] gets null if the id could not be read.
     */
    suspend fun exchange(peer: Peer, plan: (peerId: String?) -> Outgoing): Exchange {
        val session = GattSession(context, deviceId, plan)
        return try {
            withTimeout(OPERATION_TIMEOUT_MS) { session.run(peer) }
        } catch (_: TimeoutCancellationException) {
            session.progress.copy(error = "timed out talking to ${peer.address}")
        } catch (e: Exception) {
            session.progress.copy(error = e.message ?: e::class.java.simpleName)
        } finally {
            session.close()
        }
    }

    private companion object {
        const val TAG = "MeshCentral"
        const val OPERATION_TIMEOUT_MS = 20_000L
    }
}

/**
 * One connect → discover → read id → write packets → write receipts exchange.
 *
 * Separate class because each connection needs its own callback state, and
 * reusing a BluetoothGatt across peers is a reliable way to get confusing
 * failures.
 */
@SuppressLint("MissingPermission")
private class GattSession(
    private val context: Context,
    private val selfId: String,
    private val plan: (String?) -> Outgoing,
) {
    private var gatt: BluetoothGatt? = null

    /** What has got through so far; survives a timeout part-way. */
    @Volatile var progress = Exchange(peerId = null)

    private val connected = CompletableDeferred<Boolean>()
    private val discovered = CompletableDeferred<Boolean>()
    private val mtuReady = CompletableDeferred<Int>()
    private var peerIdRead = CompletableDeferred<String?>()
    private var writeAck = CompletableDeferred<Boolean>()

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!connected.isCompleted) connected.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Fail anything still waiting rather than letting it hang.
                    if (!connected.isCompleted) connected.complete(false)
                    if (!discovered.isCompleted) discovered.complete(false)
                    if (!mtuReady.isCompleted) mtuReady.complete(GATT_DEFAULT_MTU)
                    if (!peerIdRead.isCompleted) peerIdRead.complete(null)
                    if (!writeAck.isCompleted) writeAck.complete(false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!discovered.isCompleted) discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!mtuReady.isCompleted) {
                mtuReady.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else GATT_DEFAULT_MTU)
            }
        }

        // API 33+ delivers the value as a parameter.
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            completeRead(if (status == BluetoothGatt.GATT_SUCCESS) String(value, Charsets.UTF_8) else null)
        }

        @Deprecated("Needed for API < 33, which has no value parameter")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = c.value
            completeRead(
                if (status == BluetoothGatt.GATT_SUCCESS && value != null)
                    String(value, Charsets.UTF_8) else null
            )
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            if (!writeAck.isCompleted) writeAck.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        private fun completeRead(value: String?) {
            if (!peerIdRead.isCompleted) peerIdRead.complete(value)
        }
    }

    suspend fun run(peer: Peer): Exchange {
        gatt = peer.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: return Exchange(null, error = "could not open a GATT connection")

        if (!connected.await()) return Exchange(null, error = "connection refused by ${peer.address}")

        if (!gatt!!.discoverServices()) return Exchange(null, error = "could not start service discovery")
        if (!discovered.await()) return Exchange(null, error = "service discovery failed")

        val service = gatt!!.getService(BleIds.SERVICE)
            ?: return Exchange(null, error = "peer does not run BouncePay")

        // Bigger MTU means fewer round trips; failure is survivable. Some
        // stacks never answer the request at all, so don't wait long for it —
        // the default MTU just means more, smaller chunks.
        val mtu = if (gatt!!.requestMtu(BleIds.DESIRED_MTU)) {
            withTimeoutOrNull(MTU_WAIT_MS) { mtuReady.await() } ?: GATT_DEFAULT_MTU
        } else {
            GATT_DEFAULT_MTU
        }

        val idChar = service.getCharacteristic(BleIds.CHAR_DEVICE_ID)
        val raw = if (idChar != null && gatt!!.readCharacteristic(idChar)) {
            withTimeoutOrNull(ID_READ_WAIT_MS) { peerIdRead.await() }
        } else null
        val (peerId, peerName) = parseProfile(raw)
        progress = Exchange(peerId, peerName)

        if (peerId == selfId) return progress.copy(error = "that is us")

        // Decided only now, with the peer's id in hand. This is the anti-loop:
        // a packet whose hop list already names this peer is not offered, so
        // two phones in range of each other never pass one back and forth.
        val outgoing = plan(peerId)
        if (outgoing.isEmpty) return progress

        val packetInbox = service.getCharacteristic(BleIds.CHAR_PACKET_IN)
        for (packet in outgoing.packets) {
            if (packetInbox == null) return progress.copy(error = "peer has no packet inbox")
            val error = writeAll(packetInbox, packet.withHop(selfId).toBytes(), mtu)
            if (error != null) return progress.copy(error = error)
            progress = progress.copy(packetsSent = progress.packetsSent + packet.txId)
        }

        // Older builds of the app have no receipt inbox; that is not a failure.
        val receiptInbox = service.getCharacteristic(BleIds.CHAR_RECEIPT_IN)
        if (outgoing.receipts.isNotEmpty() && receiptInbox != null) {
            val error = writeAll(receiptInbox, SignedReceipt.encodeBatch(outgoing.receipts), mtu)
            if (error != null) return progress.copy(error = error)
            progress = progress.copy(receiptsSent = outgoing.receipts.map { it.txId })
        }

        return progress
    }

    /** `{"id":…,"name":…}`, or a bare id from builds before names existed. */
    private fun parseProfile(raw: String?): Pair<String?, String?> {
        if (raw.isNullOrBlank()) return null to null
        if (!raw.trimStart().startsWith("{")) return raw to null
        return runCatching {
            val o = org.json.JSONObject(raw)
            o.getString("id") to o.optString("name").ifBlank { null }
        }.getOrDefault(null to null)
    }

    /** Writes [data] as chunks, each awaited before the next. Null on success. */
    private suspend fun writeAll(target: BluetoothGattCharacteristic, data: ByteArray, mtu: Int): String? {
        val chunks = Chunking.split(data, mtu)
        for ((index, chunk) in chunks.withIndex()) {
            writeAck = CompletableDeferred()
            if (!writeChunk(target, chunk)) return "write rejected at chunk ${index + 1}/${chunks.size}"
            if (!writeAck.await()) return "peer did not acknowledge chunk ${index + 1}/${chunks.size}"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun writeChunk(characteristic: BluetoothGattCharacteristic, chunk: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = chunk
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(characteristic)
        }
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        const val GATT_DEFAULT_MTU = 23
        const val MTU_WAIT_MS = 3_000L
        const val ID_READ_WAIT_MS = 5_000L
    }
}
