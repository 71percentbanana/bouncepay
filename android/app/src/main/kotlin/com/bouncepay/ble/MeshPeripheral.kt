package com.bouncepay.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.util.Log
import com.bouncepay.model.Packet
import com.bouncepay.model.SignedReceipt

/**
 * The receiving half of a mesh node.
 *
 * Advertises the BouncePay service so peers can discover this phone without
 * pairing, and runs a GATT server that accepts packets written to it.
 *
 * Advertising and accepting connections is all a relay needs to do — it never
 * initiates anything to receive. That matters for the story: the stranger
 * carrying your payment does nothing, and their phone cannot read what it is
 * carrying.
 */
@SuppressLint("MissingPermission")   // callers gate on BLUETOOTH_* at runtime
class MeshPeripheral(
    private val context: Context,
    private val deviceId: String,
    private val onPacket: (Packet, fromAddress: String) -> Unit,
    private val onReceipts: (List<SignedReceipt>, fromAddress: String) -> Unit,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser = adapter?.bluetoothLeAdvertiser

    /**
     * One assembler per peer and characteristic: two phones may be
     * mid-transfer at once, and one phone sends packets and receipts in the
     * same connection. Server callbacks arrive on binder threads, so access
     * is synchronised.
     */
    private val assemblers = HashMap<String, Chunking.Assembler>()

    var isAdvertising: Boolean = false
        private set

    /** The GATT server is open; the node can accept packets. */
    val isRunning: Boolean get() = gattServer != null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "advertising as $deviceId")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.w(TAG, "advertise failed: ${describeAdvertiseError(errorCode)}")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                // Drop partial transfers: a peer that vanished mid-packet will
                // start again from chunk zero when it comes back.
                synchronized(assemblers) { assemblers.keys.removeAll { it.startsWith(device.address) } }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            // Advertise only once the service is registered. Advertising first
            // lets a fast peer connect, discover nothing, and give up on us.
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == BleIds.SERVICE) {
                startAdvertising()
            } else {
                Log.w(TAG, "could not register the BouncePay service: $status")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == BleIds.CHAR_DEVICE_ID) {
                val value = deviceId.toByteArray(Charsets.UTF_8)
                val slice = if (offset >= value.size) ByteArray(0)
                            else value.copyOfRange(offset, value.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray,
        ) {
            val uuid = characteristic.uuid
            if (uuid != BleIds.CHAR_PACKET_IN && uuid != BleIds.CHAR_RECEIPT_IN) {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                return
            }

            // Acknowledge before parsing. The sender is blocked waiting on this
            // response and will time out the whole transfer if work happens first.
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }

            val complete = synchronized(assemblers) {
                assemblers.getOrPut("${device.address}/$uuid") { Chunking.Assembler() }.accept(value)
            } ?: return

            if (uuid == BleIds.CHAR_RECEIPT_IN) {
                runCatching { SignedReceipt.decodeBatch(complete) }
                    .onSuccess { onReceipts(it, device.address) }
                    .onFailure { Log.w(TAG, "discarded malformed receipts from ${device.address}: ${it.message}") }
                return
            }

            runCatching { Packet.fromBytes(complete) }
                .onSuccess { packet ->
                    Log.i(TAG, "received ${packet.txId} from ${device.address}")
                    onPacket(packet, device.address)
                }
                .onFailure {
                    Log.w(TAG, "discarded malformed packet from ${device.address}: ${it.message}")
                }
        }
    }

    /**
     * Opens the GATT server; advertising follows in [onServiceAdded].
     * Safe to call repeatedly — the node retries when Bluetooth comes back on.
     */
    fun start(): Boolean {
        if (gattServer != null) return true
        val bluetooth = adapter ?: return false
        if (!bluetooth.isEnabled) return false

        val server = manager?.openGattServer(context, serverCallback) ?: return false
        gattServer = server

        val service = BluetoothGattService(BleIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleIds.CHAR_PACKET_IN,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleIds.CHAR_RECEIPT_IN,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleIds.CHAR_DEVICE_ID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )
        if (!server.addService(service)) {
            Log.w(TAG, "addService refused")
            stop()
            return false
        }
        return true
    }

    private fun startAdvertising() {
        advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "this device cannot advertise; it can still send as a central")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // The 31-byte advertisement holds the service UUID and nothing else —
        // the device name alone would overflow it. Peers learn the account id
        // by reading CHAR_DEVICE_ID after connecting.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(android.os.ParcelUuid(BleIds.SERVICE))
            .build()

        runCatching { advertiser?.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { Log.w(TAG, "startAdvertising threw", it) }
    }

    fun stop() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        gattServer = null
        synchronized(assemblers) { assemblers.clear() }
        isAdvertising = false
    }

    private fun describeAdvertiseError(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertisement payload too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers on this device"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already advertising"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal bluetooth error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "peripheral mode unsupported on this device"
        else -> "code $code"
    }

    private companion object {
        const val TAG = "MeshPeripheral"
    }
}
