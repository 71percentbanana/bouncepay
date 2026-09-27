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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** What happened when we tried to hand a packet to a peer. */
sealed interface Delivery {
    data class Sent(val peerId: String) : Delivery
    data class Skipped(val peerId: String, val why: String) : Delivery
    data class Failed(val why: String) : Delivery
}

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
     * Connects to [peer] and hands over [packet], unless the peer has already
     * carried it.
     */
    suspend fun deliver(packet: Packet, peer: Peer): Delivery {
        val session = GattSession(context, packet, deviceId)
        return try {
            withTimeout(OPERATION_TIMEOUT_MS) { session.run(peer) }
        } catch (_: TimeoutCancellationException) {
            Delivery.Failed("timed out talking to ${peer.address}")
        } catch (e: Exception) {
            Delivery.Failed(e.message ?: e::class.java.simpleName)
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
 * One connect → discover → read id → write chunks exchange.
 *
 * Separate class because each connection needs its own callback state, and
 * reusing a BluetoothGatt across peers is a reliable way to get confusing
 * failures.
 */
@SuppressLint("MissingPermission")
private class GattSession(
    private val context: Context,
    private val packet: Packet,
    private val selfId: String,
) {
    private var gatt: BluetoothGatt? = null

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

    suspend fun run(peer: Peer): Delivery {
        gatt = peer.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: return Delivery.Failed("could not open a GATT connection")

        if (!connected.await()) return Delivery.Failed("connection refused by ${peer.address}")

        if (!gatt!!.discoverServices()) return Delivery.Failed("could not start service discovery")
        if (!discovered.await()) return Delivery.Failed("service discovery failed")

        val service = gatt!!.getService(BleIds.SERVICE)
            ?: return Delivery.Failed("peer does not run BouncePay")

        // Bigger MTU means fewer round trips; failure is survivable.
        gatt!!.requestMtu(BleIds.DESIRED_MTU)
        val mtu = mtuReady.await()

        val idChar = service.getCharacteristic(BleIds.CHAR_DEVICE_ID)
        val peerId = if (idChar != null && gatt!!.readCharacteristic(idChar)) {
            peerIdRead.await()
        } else null

        // Anti-loop. Without this two phones in range of each other hand the
        // same packet back and forth forever and it never reaches the bridge.
        if (peerId != null && packet.hops.contains(peerId)) {
            return Delivery.Skipped(peerId, "already carried this packet")
        }
        if (peerId == selfId) {
            return Delivery.Skipped(peerId, "that is us")
        }

        val inbox = service.getCharacteristic(BleIds.CHAR_PACKET_IN)
            ?: return Delivery.Failed("peer has no inbox characteristic")

        val outgoing = packet.withHop(selfId)
        val chunks = Chunking.split(outgoing.toBytes(), mtu)

        for ((index, chunk) in chunks.withIndex()) {
            writeAck = CompletableDeferred()
            if (!writeChunk(inbox, chunk)) {
                return Delivery.Failed("write rejected at chunk ${index + 1}/${chunks.size}")
            }
            if (!writeAck.await()) {
                return Delivery.Failed("peer did not acknowledge chunk ${index + 1}/${chunks.size}")
            }
        }

        return Delivery.Sent(peerId ?: peer.address)
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
    }
}
