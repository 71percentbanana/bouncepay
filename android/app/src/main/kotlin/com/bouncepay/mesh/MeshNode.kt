package com.bouncepay.mesh

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.bouncepay.bank.Bank
import com.bouncepay.bank.EmbeddedBank
import com.bouncepay.bank.RemoteBank
import com.bouncepay.bank.Settlement
import com.bouncepay.bank.hasNetwork
import com.bouncepay.ble.Delivery
import com.bouncepay.ble.MeshCentral
import com.bouncepay.ble.MeshPeripheral
import com.bouncepay.crypto.DeviceKey
import com.bouncepay.model.Packet
import com.bouncepay.model.PacketState
import com.bouncepay.store.AppPrefs
import com.bouncepay.store.PacketStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** How this device is taking part right now. Derived every cycle, never assigned. */
enum class Role {
    /** Reaches the bank, so it settles whatever it holds. */
    BRIDGE,

    /** Has a network but no bank on it — settling with the on-device fallback. */
    BRIDGE_FALLBACK,

    /** Cannot settle; hands packets to peers and carries for others. */
    RELAY,
}

data class MeshStatus(
    val deviceId: String = "",
    val running: Boolean = false,
    val bluetoothOn: Boolean = false,
    val advertising: Boolean = false,
    val role: Role = Role.RELAY,
    val peersInRange: Int = 0,
    val activity: String = "Idle",
)

/**
 * One node in the BouncePay mesh.
 *
 * Every device runs the same loop, and its behaviour falls out of what it can
 * reach rather than from a role assigned in advance:
 *
 *   * reaches the bank          → it is the bridge, settle what it holds
 *   * cannot reach the bank     → find a peer and hand packets on
 *   * nothing to send           → advertise, and carry for others
 *
 * Nobody is designated a relay. A phone becomes one by being in range at the
 * right moment, which is the property the whole idea rests on.
 */
class MeshNode(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val prefs = AppPrefs(context)
    val store = PacketStore(context)

    val deviceId: String = DeviceKey.accountId()

    private val central = MeshCentral(context, deviceId)
    private val embeddedBank = EmbeddedBank(context)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)

    private val peripheral = MeshPeripheral(
        context = context,
        deviceId = deviceId,
        onPacket = { packet, from -> onPacketReceived(packet, from) },
    )

    /**
     * BLE address → account id, learned from every connection.
     *
     * Lets a relay skip a neighbour that already holds a packet without
     * connecting to it again just to ask. Addresses rotate every few minutes
     * for privacy, which only costs one extra connection when they do.
     */
    private val knownPeers = ConcurrentHashMap<String, String>()

    private val _status = MutableStateFlow(MeshStatus(deviceId = deviceId))
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    /** Nudges the loop so a tap or an arriving packet is acted on at once. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var pump: Job? = null
    private var lastScanAt = 0L

    fun start() {
        if (pump != null) return
        DeviceKey.ensureKey()
        _status.update { it.copy(running = true, activity = "Starting…") }
        pump = scope.launch { pumpLoop() }
    }

    fun stop() {
        pump?.cancel()
        pump = null
        peripheral.stop()
        _status.update { it.copy(running = false, advertising = false, activity = "Stopped") }
    }

    fun poke() {
        wake.trySend(Unit)
    }

    /**
     * Signs a payment here and queues it. No network required.
     *
     * Refuses to sign past the offline wallet: the bank enforces the real
     * balance, but only after the packet has crossed the mesh.
     */
    fun pay(amountPaise: Int, payeeId: String): Result<Packet> {
        if (!prefs.tryDebit(amountPaise)) {
            val wallet = prefs.current.walletPaise
            return Result.failure(IllegalStateException(
                if (wallet == 0) "Load your offline wallet first"
                else "Offline wallet has only ${rupees(wallet)}"
            ))
        }
        return runCatching { Packet.create(amountPaise, payeeId) }
            .onSuccess { packet ->
                store.offer(packet)
                _status.update { it.copy(activity = "Signed ${packet.fields.rupees} — looking for a way out") }
                poke()
            }
            .onFailure { refund(amountPaise) }
    }

    /**
     * Opens this device's account and loads its offline wallet.
     *
     * Needs the bank, so it has to happen before going offline. If the bank
     * cannot be reached and the fallback is allowed, the wallet is loaded
     * locally instead and the result says so — useful for a demo with no
     * laptop, and it will be refused by a real bank later, as it should be.
     */
    suspend fun loadWallet(openingPaise: Int = DEFAULT_WALLET_PAISE): Result<String> {
        val settings = prefs.current
        val remote = RemoteBank(settings.bankUrl)

        if (!settings.forceOffline && context.hasNetwork()) {
            val enrolled = remote.enroll(openingPaise)
            enrolled.getOrNull()?.let { e ->
                // The bank's balance has not yet seen payments still in flight
                // from this phone; leave room for them or they would be spent twice.
                val inFlight = ownPendingPaise()
                prefs.update { it.copy(enrolled = true, walletPaise = (e.balancePaise - inFlight).coerceAtLeast(0)) }
                return Result.success("Wallet synced with the bank: ${rupees(prefs.current.walletPaise)}")
            }
            if (!settings.useFallbackBank) {
                return Result.failure(enrolled.exceptionOrNull() ?: IllegalStateException("bank unreachable"))
            }
        } else if (!settings.useFallbackBank) {
            return Result.failure(IllegalStateException("Go online to load the wallet"))
        }

        prefs.update { it.copy(walletPaise = openingPaise) }
        return Result.success("Bank unreachable — loaded ${rupees(openingPaise)} on-device (demo)")
    }

    private fun ownPendingPaise(): Int = store.pending()
        .map { it.packet.fields }
        .filter { it.payerId == deviceId }
        .sumOf { it.amountPaise }

    private fun refund(amountPaise: Int) =
        prefs.update { it.copy(walletPaise = it.walletPaise + amountPaise) }

    private fun onPacketReceived(packet: Packet, from: String) {
        // A relay accepts blind. It has no way to judge the payment, so its only
        // job is to keep it safe and keep it moving. Verification belongs to
        // the bank.
        val isNew = store.offer(packet.withHop(deviceId))
        _status.update {
            it.copy(activity = if (isNew) "Carrying ${packet.fields.rupees} for someone" else "Already had that packet")
        }
        if (isNew) poke()
    }

    /**
     * The carry-and-forward loop.
     *
     * Runs in a foreground service, so it keeps going with the screen off —
     * which is what lets a phone pick a packet up in a dead zone and deliver
     * it minutes later somewhere else.
     */
    private suspend fun pumpLoop() {
        while (scope.isActive) {
            runCatching { pumpOnce() }
                .onFailure { e ->
                    Log.w(TAG, "pump cycle failed", e)
                    _status.update { it.copy(activity = "Error: ${e.message ?: e::class.java.simpleName}") }
                }
            withTimeoutOrNull(PUMP_INTERVAL_MS) { wake.receive() }
        }
    }

    private suspend fun pumpOnce() {
        keepRadioUp()

        val settings = prefs.current
        val remote = RemoteBank(settings.bankUrl)
        val network = !settings.forceOffline && context.hasNetwork()

        // A network alone does not make a bridge; reaching the bank does.
        val bank: Bank? = when {
            !network -> null
            remote.isReachable() -> remote
            settings.useFallbackBank -> embeddedBank
            else -> null
        }
        val role = when (bank) {
            null -> Role.RELAY
            is RemoteBank -> Role.BRIDGE
            else -> Role.BRIDGE_FALLBACK
        }
        _status.update { it.copy(role = role) }

        val pending = store.pending().map { it.packet }
        if (pending.isEmpty()) {
            _status.update {
                it.copy(activity = when (role) {
                    Role.BRIDGE -> "Bridge · connected to the bank"
                    Role.BRIDGE_FALLBACK -> "Bridge · bank unreachable, using on-device fallback"
                    Role.RELAY -> if (settings.forceOffline) "Offline (forced) · ready to relay" else "Offline · ready to relay"
                })
            }
            return
        }

        if (bank != null) settleAll(pending, bank) else forwardAll(pending)
    }

    /** Opens the GATT server when Bluetooth is on, and notices when it is switched off. */
    private fun keepRadioUp() {
        val on = bluetooth?.adapter?.isEnabled == true
        if (!on && peripheral.isRunning) peripheral.stop()
        if (on && !peripheral.isRunning) peripheral.start()
        _status.update {
            it.copy(bluetoothOn = on, advertising = on && peripheral.isAdvertising)
        }
    }

    /** This device is the bridge: push everything it is carrying to the bank. */
    private suspend fun settleAll(packets: List<Packet>, bank: Bank) {
        _status.update { it.copy(activity = "Bridging ${packets.size} packet(s) · ${bank.label}") }

        for (packet in packets) {
            val fields = packet.fields
            when (val result = bank.settle(packet)) {
                is Settlement.Settled -> {
                    val transfers = (packet.hops.size - 1).coerceAtLeast(0)
                    store.markSettled(
                        packet.txId,
                        if (result.duplicate) "already settled"
                        else "settled by ${result.source} · $transfers hop(s)",
                    )
                    _status.update { it.copy(activity = "Settled ${fields.rupees} via ${result.source}") }
                }
                is Settlement.Rejected -> {
                    // A refusal is final: carrying it further cannot help.
                    store.markRejected(packet.txId, "${result.code}: ${result.message}")
                    // No money moved, so this phone's own spend comes back.
                    if (fields.payerId == deviceId) refund(fields.amountPaise)
                    _status.update { it.copy(activity = "Refused: ${result.code}") }
                }
                is Settlement.Unreachable -> {
                    // Keep it — the bank vanishing is not the packet's fault —
                    // and stop hammering it for the rest of this cycle.
                    _status.update { it.copy(activity = "Bank stopped answering · holding") }
                    return
                }
            }
        }
    }

    /** Cannot settle here: find someone who might. */
    private suspend fun forwardAll(packets: List<Packet>) {
        // Android silently drops scans started more than five times in thirty
        // seconds, so a burst of taps must not turn into a burst of scans.
        val sinceLast = System.currentTimeMillis() - lastScanAt
        if (sinceLast < MIN_SCAN_GAP_MS) delay(MIN_SCAN_GAP_MS - sinceLast)

        _status.update { it.copy(activity = "Holding ${packets.size} · scanning for peers…") }
        lastScanAt = System.currentTimeMillis()
        val peers = central.scan(SCAN_DURATION_MS)
        _status.update { it.copy(peersInRange = peers.size) }

        if (peers.isEmpty()) {
            _status.update { it.copy(activity = "No peers in range · holding ${packets.size} packet(s)") }
            return
        }

        var handedOn = 0
        for (original in packets) {
            // Re-read: an earlier hand-off this cycle may have added hops.
            val packet = store.packets.value.firstOrNull { it.packet.txId == original.txId }?.packet ?: continue
            val candidates = peers.filter { peer ->
                val known = knownPeers[peer.address]
                known == null || known !in packet.hops
            }

            for (peer in candidates) {
                when (val delivery = central.deliver(packet, peer)) {
                    is Delivery.Sent -> {
                        knownPeers[peer.address] = delivery.peerId
                        store.recordHandoff(packet.txId, delivery.peerId)
                        handedOn++
                        _status.update {
                            it.copy(activity = "Handed ${packet.fields.rupees} to ${delivery.peerId}")
                        }
                        // One good hand-off per cycle is enough. The packet stays
                        // queued until settlement is confirmed, so nothing is lost
                        // if that peer never gets through.
                        break
                    }
                    is Delivery.Skipped -> {
                        knownPeers[peer.address] = delivery.peerId
                        Log.d(TAG, "skipped ${delivery.peerId}: ${delivery.why}")
                    }
                    is Delivery.Failed -> Log.d(TAG, "delivery to ${peer.address} failed: ${delivery.why}")
                }
            }
        }

        if (handedOn == 0) {
            _status.update {
                it.copy(activity = "${peers.size} peer(s) nearby, none took it yet · holding")
            }
        }
    }

    fun settledCount(): Int = store.packets.value.count { it.state == PacketState.SETTLED }

    companion object {
        private const val TAG = "MeshNode"
        private const val PUMP_INTERVAL_MS = 6_000L
        private const val SCAN_DURATION_MS = 4_000L
        private const val MIN_SCAN_GAP_MS = 6_500L
        const val DEFAULT_WALLET_PAISE = 2_000_00

        fun rupees(paise: Int): String = "₹%,.2f".format(paise / 100.0)
    }
}
