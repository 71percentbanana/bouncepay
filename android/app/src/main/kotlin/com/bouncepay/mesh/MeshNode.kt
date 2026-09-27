package com.bouncepay.mesh

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.bouncepay.bank.Bank
import com.bouncepay.bank.EmbeddedBank
import com.bouncepay.bank.RemoteBank
import com.bouncepay.bank.Settlement
import com.bouncepay.bank.hasNetwork
import com.bouncepay.ble.MeshCentral
import com.bouncepay.ble.MeshPeripheral
import com.bouncepay.ble.Outgoing
import com.bouncepay.crypto.DeviceKey
import com.bouncepay.model.Packet
import com.bouncepay.model.SignedReceipt
import com.bouncepay.model.PacketState
import com.bouncepay.store.AppPrefs
import com.bouncepay.store.IncomingLedger
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
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

/** A phone seen nearby, and what it calls itself. */
data class NearbyPhone(val id: String, val name: String)

data class MeshStatus(
    val deviceId: String = "",
    val running: Boolean = false,
    val bluetoothOn: Boolean = false,
    val advertising: Boolean = false,
    val role: Role = Role.RELAY,
    val peersInRange: Int = 0,
    /** Bank receipts this phone is passing back towards their payers. */
    val receiptsToShare: Int = 0,
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
 *   * holds fresh bank receipts → pass them on, so offline payers learn
 *                                 their payment settled
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
    val incoming = IncomingLedger(context)

    val deviceId: String = DeviceKey.accountId()

    private val central = MeshCentral(context, deviceId)
    private val embeddedBank = EmbeddedBank(context)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)

    fun displayName(): String =
        prefs.current.displayName.ifBlank { android.os.Build.MODEL ?: "Android phone" }

    private val peripheral = MeshPeripheral(
        context = context,
        deviceId = deviceId,
        profile = { JSONObject().put("id", deviceId).put("name", displayName()).toString() },
        onPacket = { packet, from -> onPacketReceived(packet, from) },
        onReceipts = { receipts, from -> onReceiptsReceived(receipts, from) },
    )

    private val receipts = ReceiptBook()

    private val router = MeshRouter(
        selfId = deviceId,
        store = store,
        receipts = receipts,
        bankKey = { prefs.current.bankPubKey },
        refund = ::refund,
        onPaid = ::onPaid,
    )

    /** Who is in range, by BLE address, learned from every connection. */
    private val names = ConcurrentHashMap<String, NearbyPhone>()
    private val _nearby = MutableStateFlow<List<NearbyPhone>>(emptyList())
    val nearby: StateFlow<List<NearbyPhone>> = _nearby.asStateFlow()

    /** One scan or GATT exchange at a time, whether from the loop or the UI. */
    private val radio = Mutex()

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
        if (payeeId == deviceId) return Result.failure(IllegalArgumentException("That is this phone"))
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
                prefs.update {
                    it.copy(
                        enrolled = true,
                        walletPaise = (e.balancePaise - inFlight).coerceAtLeast(0),
                        bankPubKey = e.bankPubKey ?: it.bankPubKey,
                    )
                }
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

    /**
     * The bank has proven a payment to this phone. Credit it once: the same
     * receipt arrives from several neighbours.
     */
    private fun onPaid(receipt: SignedReceipt) {
        if (!incoming.record(receipt)) return
        val amount = receipt.fields.amountPaise
        prefs.update { it.copy(walletPaise = it.walletPaise + amount) }
        _status.update { it.copy(activity = "Received ${rupees(amount)} · confirmed by the bank") }
    }

    /**
     * Looks for phones in range right now, for the payee list. Also hands on
     * anything they need while connected, like any other pass.
     */
    suspend fun discoverNearby() {
        val isBridge = _status.value.role != Role.RELAY
        exchangeWithPeers(router.toForward(isBridge), receipts.fresh())
    }

    private fun onPacketReceived(packet: Packet, from: String) {
        // A relay accepts blind. It has no way to judge the payment, so its only
        // job is to keep it safe and keep it moving. Verification belongs to
        // the bank.
        val isNew = router.onPacket(packet)
        _status.update {
            it.copy(activity = if (isNew) "Carrying ${packet.fields.rupees} for someone" else "Already had that packet")
        }
        if (isNew) poke()
    }

    /** Receipts from a peer; the router decides what to believe. */
    private fun onReceiptsReceived(incoming: List<SignedReceipt>, from: String) {
        val intake = router.onReceipts(incoming)
        if (intake.dropped > 0) Log.w(TAG, "dropped ${intake.dropped} receipt(s) from $from the bank did not sign")
        if (intake.settledHere > 0) {
            _status.update { it.copy(activity = "Bank receipt arrived · ${intake.settledHere} payment(s) settled") }
        }
        // Only news is worth a cycle; two phones swapping what both already
        // have must not keep waking each other.
        if (intake.news) poke()
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
        val bankKey = if (network) remote.health() else null
        if (!bankKey.isNullOrBlank() && bankKey != settings.bankPubKey) {
            // Talking to the configured bank directly is the moment to learn
            // the key its receipts will be signed with.
            prefs.update { it.copy(bankPubKey = bankKey) }
        }
        val bank: Bank? = when {
            !network -> null
            bankKey != null -> remote
            settings.useFallbackBank -> embeddedBank
            else -> null
        }
        val role = when (bank) {
            null -> Role.RELAY
            is RemoteBank -> Role.BRIDGE
            else -> Role.BRIDGE_FALLBACK
        }
        _status.update { it.copy(role = role) }

        if (bank != null && store.pending().isNotEmpty()) settleAll(bank)

        // Whatever is still unsettled, and every fresh receipt, goes to peers.
        val toForward = router.toForward(isBridge = bank != null)
        val toShare = receipts.fresh()
        _status.update { it.copy(receiptsToShare = toShare.size) }

        if (toForward.isEmpty() && toShare.isEmpty()) {
            if (store.pending().isEmpty()) {
                _status.update {
                    it.copy(activity = when (role) {
                        Role.BRIDGE -> "Bridge · connected to the bank"
                        Role.BRIDGE_FALLBACK -> "Bridge · bank unreachable, using on-device fallback"
                        Role.RELAY -> if (settings.forceOffline) "Offline (forced) · ready to relay" else "Offline · ready to relay"
                    })
                }
            }
            return
        }

        exchangeWithPeers(toForward, toShare)
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
    private suspend fun settleAll(bank: Bank) {
        _status.update { it.copy(activity = "Bridging ${store.pending().size} packet(s) · ${bank.label}") }
        router.settleAll(bank) { packet, result ->
            val activity = when (result) {
                is Settlement.Settled -> "Settled ${packet.fields.rupees} via ${result.source}"
                is Settlement.Rejected -> "Refused: ${result.code}"
                is Settlement.Unreachable -> "Bank stopped answering · holding"
            }
            _status.update { it.copy(activity = activity) }
        }
    }

    /**
     * One pass over the phones in range: packets go to anyone not already in
     * their hop list, receipts to anyone not yet sent them. A single
     * connection per peer carries both.
     *
     * Packets are offered to every neighbour, not just the first: a copy per
     * route is how a packet finds the bridge fastest, and the bank settles
     * exactly one of them.
     */
    private suspend fun exchangeWithPeers(packets: List<Packet>, toShare: List<SignedReceipt>) =
        radio.withLock { exchangeLocked(packets, toShare) }

    private suspend fun exchangeLocked(packets: List<Packet>, toShare: List<SignedReceipt>) {
        // Android silently drops scans started more than five times in thirty
        // seconds, so a burst of taps must not turn into a burst of scans.
        val sinceLast = System.currentTimeMillis() - lastScanAt
        if (sinceLast < MIN_SCAN_GAP_MS) delay(MIN_SCAN_GAP_MS - sinceLast)

        _status.update {
            it.copy(activity = when {
                packets.isNotEmpty() -> "Holding ${packets.size} · scanning for peers…"
                toShare.isNotEmpty() -> "Passing ${toShare.size} bank receipt(s) back · scanning…"
                else -> "Looking for phones nearby…"
            })
        }
        lastScanAt = System.currentTimeMillis()
        val peers = central.scan(SCAN_DURATION_MS)
        _status.update { it.copy(peersInRange = peers.size) }

        if (peers.isEmpty()) {
            _nearby.value = emptyList()
            if (packets.isNotEmpty()) {
                _status.update { it.copy(activity = "No peers in range · holding ${packets.size} packet(s)") }
            }
            return
        }

        fun needs(peerId: String?): Outgoing = router.needs(peerId, packets, toShare)

        var packetsHanded = 0
        var receiptsHanded = 0
        for (peer in peers) {
            // Don't even connect to a phone we know already has everything.
            val known = knownPeers[peer.address]
            if (known != null && needs(known).isEmpty) continue

            val result = central.exchange(peer, ::needs)
            val peerId = result.peerId
            if (peerId != null) {
                knownPeers[peer.address] = peerId
                names[peer.address] = NearbyPhone(peerId, result.peerName ?: "Phone ${peerId.takeLast(4)}")
            }

            router.record(result, peer.address)
            packetsHanded += result.packetsSent.size
            receiptsHanded += result.receiptsSent.size

            result.error?.let { Log.d(TAG, "exchange with ${peer.address}: $it") }
            if (result.packetsSent.isNotEmpty() && peerId != null) {
                _status.update { it.copy(activity = "Handed ${result.packetsSent.size} packet(s) to ${peerId.takeLast(6)}") }
            }
        }

        _nearby.value = peers.mapNotNull { names[it.address] }.distinctBy { it.id }

        _status.update {
            it.copy(activity = when {
                packetsHanded > 0 && receiptsHanded > 0 ->
                    "Handed on $packetsHanded packet(s) and $receiptsHanded receipt(s)"
                packetsHanded > 0 -> "Handed on $packetsHanded packet(s) · keeping copies until the bank confirms"
                receiptsHanded > 0 -> "Passed $receiptsHanded bank receipt(s) back"
                packets.isNotEmpty() -> "${peers.size} peer(s) nearby, none took it yet · holding"
                packets.isEmpty() && toShare.isEmpty() -> "${_nearby.value.size} phone(s) nearby"
                else -> it.activity
            })
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
