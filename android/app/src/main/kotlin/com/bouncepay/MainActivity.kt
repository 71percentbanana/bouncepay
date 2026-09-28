package com.bouncepay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.bouncepay.mesh.MeshNode
import com.bouncepay.mesh.MeshService
import com.bouncepay.mesh.MeshStatus
import com.bouncepay.mesh.NearbyPhone
import com.bouncepay.model.SignedReceipt
import com.bouncepay.mesh.Role
import com.bouncepay.model.PacketState
import com.bouncepay.model.StoredPacket
import com.bouncepay.store.Settings
import com.bouncepay.ui.BouncePayTheme
import com.bouncepay.ui.CarryGold
import com.bouncepay.ui.SettledGreen
import kotlinx.coroutines.launch

/** The merchant every demo payment goes to; the mock bank opens it on start. */
/** The merchant the mock bank opens on start; always offered as a payee. */
private val MERCHANT = NearbyPhone("campus-stationery", "Campus Stationery")

private fun payeeLabel(id: String?): String = when {
    id == null -> "?"
    id == MERCHANT.id -> MERCHANT.name
    else -> "…" + id.takeLast(6)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the system bars must be too — the
        // default follows the system theme and leaves dark icons on a dark
        // background when the phone is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val node = mesh

        setContent {
            BouncePayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MeshScreen(node)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed while away (Bluetooth toggled, network
        // joined); don't make the user wait for the next cycle to see it.
        if (hasBlePermissions()) mesh.poke()
    }
}

/** The permissions BLE needs, which differ sharply across versions. */
fun blePermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
    // Before Android 12 a BLE scan could infer location, so the platform
    // demanded the location permission for it.
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

/** Everything to ask for up front. Notifications are optional; BLE is not. */
private fun requestedPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        blePermissions() + Manifest.permission.POST_NOTIFICATIONS
    else blePermissions()

fun Context.hasBlePermissions(): Boolean = blePermissions().all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Before Android 12, a BLE scan silently returns nothing while the phone's
 * location switch is off — no error, just no peers. Worth telling the user.
 */
fun Context.scanBlockedByLocation(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
    val manager = getSystemService(LocationManager::class.java) ?: return false
    return !LocationManagerCompat.isLocationEnabled(manager)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshScreen(mesh: MeshNode) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val status by mesh.status.collectAsState()
    val packets by mesh.store.packets.collectAsState()
    val settings by mesh.prefs.settings.collectAsState()
    val nearby by mesh.nearby.collectAsState()
    val received by mesh.incoming.received.collectAsState()
    var payee by remember { mutableStateOf(MERCHANT) }
    var finding by remember { mutableStateOf(false) }

    var amountPaise by remember { mutableIntStateOf(100_00) }
    var granted by remember { mutableStateOf(context.hasBlePermissions()) }
    var loadingWallet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var stoppedByUser by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = context.hasBlePermissions()
        if (granted) MeshService.start(context)
    }

    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { mesh.poke() }

    LaunchedEffect(Unit) {
        if (granted) MeshService.start(context) else permissionLauncher.launch(requestedPermissions())
    }

    fun say(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = inner.calculateTopPadding() + 12.dp,
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header(status.deviceId, onSettings = { showSettings = true }) }

            if (!granted) {
                item {
                    Notice(
                        icon = Icons.Rounded.BluetoothDisabled,
                        title = "Bluetooth permission needed",
                        body = "BouncePay reaches nearby phones over Bluetooth. Nothing is shared except signed payment packets.",
                        action = "Grant",
                        onAction = { permissionLauncher.launch(requestedPermissions()) },
                    )
                }
            } else if (stoppedByUser && !status.running) {
                item {
                    Notice(
                        icon = Icons.Rounded.PauseCircle,
                        title = "Relaying is off",
                        body = "This phone is not carrying packets or settling them.",
                        action = "Start",
                        onAction = {
                            stoppedByUser = false
                            MeshService.start(context)
                        },
                    )
                }
            } else if (status.running && !status.bluetoothOn) {
                item {
                    Notice(
                        icon = Icons.Rounded.BluetoothDisabled,
                        title = "Bluetooth is off",
                        body = "The mesh needs Bluetooth to hand packets to nearby phones.",
                        action = "Turn on",
                        onAction = {
                            runCatching {
                                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }
                        },
                    )
                }
            } else if (status.running && context.scanBlockedByLocation()) {
                item {
                    Notice(
                        icon = Icons.Rounded.LocationOff,
                        title = "Location is off",
                        body = "On this Android version, Bluetooth can't find nearby phones unless location is on. BouncePay does not use your location.",
                        action = "Settings",
                        onAction = {
                            runCatching {
                                context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            }
                        },
                    )
                }
            }

            item { StatusCard(status, settings) }

            item {
                WalletCard(
                    walletPaise = settings.walletPaise,
                    enrolled = settings.enrolled,
                    loading = loadingWallet,
                    onLoad = {
                        loadingWallet = true
                        scope.launch {
                            mesh.loadWallet()
                                .onSuccess { say(it) }
                                .onFailure { say("Could not load wallet: ${it.message}") }
                            loadingWallet = false
                        }
                    },
                )
            }

            item {
                PayCard(
                    amountPaise = amountPaise,
                    walletPaise = settings.walletPaise,
                    enabled = granted,
                    payee = payee,
                    payees = listOf(MERCHANT) + nearby.filter { it.id != status.deviceId },
                    finding = finding,
                    onPayee = { payee = it },
                    onFind = {
                        finding = true
                        scope.launch {
                            runCatching { mesh.discoverNearby() }
                            finding = false
                            if (mesh.nearby.value.isEmpty()) say("No BouncePay phones in range")
                        }
                    },
                    onAmount = { amountPaise = it },
                    onPay = {
                        mesh.pay(amountPaise, payee.id)
                            .onSuccess { say("Signed ${it.fields.rupees} to ${payee.name} — it will hop to the bank") }
                            .onFailure { say(it.message ?: "Could not sign the payment") }
                    },
                )
            }

            val waiting = packets.filter {
                runCatching { it.packet.fields.payeeId }.getOrNull() == status.deviceId &&
                    (it.state == PacketState.HELD || it.state == PacketState.FORWARDED)
            }
            if (waiting.isNotEmpty() || received.isNotEmpty()) {
                item { ReceivedCard(waiting, received) }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Packets on this phone",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (packets.any { it.state == PacketState.SETTLED || it.state == PacketState.REJECTED }) {
                        TextButton(onClick = { mesh.store.clearTerminal() }) { Text("Clear finished") }
                    }
                }
            }

            if (packets.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Pay something, or keep the app open near a friend — " +
                            "their payments may pass through you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(packets.reversed(), key = { it.packet.payload }) { stored ->
                    PacketCard(stored, selfId = status.deviceId)
                }
            }
        }
    }

    if (showSettings) {
        // Fully open: the switches are the point of this sheet and should
        // not sit below the fold; Back then closes it in one press.
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SettingsSheet(
                settings = settings,
                onChange = { transform ->
                    mesh.prefs.update(transform)
                    mesh.poke()
                },
                onStop = {
                    MeshService.stop(context)
                    stoppedByUser = true
                    showSettings = false
                },
            )
        }
    }
}

@Composable
private fun Header(deviceId: String, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "BouncePay",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (deviceId.isBlank()) "starting…" else deviceId,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun StatusCard(status: MeshStatus, settings: Settings) {
    val (roleLabel, roleColor) = when (status.role) {
        Role.BRIDGE -> "Bridge" to SettledGreen
        Role.BRIDGE_FALLBACK -> "Bridge · fallback" to CarryGold
        Role.RELAY -> (if (settings.forceOffline) "Relay · forced offline" else "Relay") to MaterialTheme.colorScheme.primary
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(roleColor.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(roleLabel, style = MaterialTheme.typography.labelLarge, color = roleColor)
                }
                Spacer(Modifier.weight(1f))
                if (!status.running) {
                    Text("stopped", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Dot("Bluetooth", status.bluetoothOn, SettledGreen)
                Dot("Visible", status.advertising, SettledGreen)
                Dot("Peers · ${status.peersInRange}", status.peersInRange > 0, SettledGreen)
            }
            if (status.receiptsToShare > 0) {
                Text(
                    "Passing ${status.receiptsToShare} bank receipt(s) back to their payers",
                    style = MaterialTheme.typography.bodySmall,
                    color = SettledGreen,
                )
            }
            Text(
                status.activity,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Dot(label: String, on: Boolean, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (on) color else MaterialTheme.colorScheme.outlineVariant)
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun WalletCard(walletPaise: Int, enrolled: Boolean, loading: Boolean, onLoad: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Offline wallet",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    MeshNode.rupees(walletPaise),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (enrolled) "Account open at the bank" else "Load while online, spend while offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onLoad, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (enrolled) "Sync" else "Load")
                }
            }
        }
    }
}

@Composable
private fun PayCard(
    amountPaise: Int,
    walletPaise: Int,
    enabled: Boolean,
    payee: NearbyPhone,
    payees: List<NearbyPhone>,
    finding: Boolean,
    onPayee: (NearbyPhone) -> Unit,
    onFind: () -> Unit,
    onAmount: (Int) -> Unit,
    onPay: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pay",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(payee.name, style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onFind, enabled = enabled && !finding) {
                    if (finding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Find nearby")
                }
            }
            // Anyone in range can be paid; the merchant is always there.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                payees.forEach { option ->
                    FilterChip(
                        selected = option.id == payee.id,
                        onClick = { onPayee(option) },
                        label = { Text(option.name, maxLines = 1) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50_00, 100_00, 250_00, 500_00).forEach { paise ->
                    FilterChip(
                        selected = amountPaise == paise,
                        onClick = { onAmount(paise) },
                        label = { Text(wholeRupees(paise)) },
                    )
                }
            }
            Button(
                onClick = onPay,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text(
                    "Pay ${wholeRupees(amountPaise)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (enabled && walletPaise < amountPaise) {
                Text(
                    if (walletPaise == 0) "Load the offline wallet first."
                    else "Not enough in the offline wallet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Money coming to this phone. A payment addressed here shows up the moment a
 * neighbour hands it over — but it is only *received* once the bank's signed
 * receipt arrives, and only then does it count towards the wallet.
 */
@Composable
private fun ReceivedCard(waiting: List<StoredPacket>, received: List<SignedReceipt>) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Received",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            waiting.forEach { stored ->
                val f = stored.packet.fields
                IncomingRow(f.rupees, "from …${f.payerId.takeLast(6)}", "Waiting for the bank", CarryGold)
            }
            received.asReversed().forEach { r ->
                val f = r.fields
                IncomingRow(MeshNode.rupees(f.amountPaise), "from …${f.payerId.takeLast(6)}", "✓ Confirmed", SettledGreen)
            }
        }
    }
}

@Composable
private fun IncomingRow(amount: String, from: String, state: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(amount, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Text(
            from,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(state, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun PacketCard(stored: StoredPacket, selfId: String) {
    val fields = runCatching { stored.packet.fields }.getOrNull()
    val accent = when (stored.state) {
        PacketState.SETTLED -> SettledGreen
        PacketState.REJECTED -> MaterialTheme.colorScheme.error
        else -> CarryGold
    }
    val label = when (stored.state) {
        PacketState.HELD -> "Holding"
        PacketState.FORWARDED -> "Handed on"
        PacketState.SETTLED -> "Settled"
        PacketState.REJECTED -> "Refused"
    }
    val mine = fields?.payerId == selfId

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fields?.rupees ?: "—", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        mine -> "you → ${payeeLabel(fields?.payeeId)}"
                        fields?.payeeId == selfId -> "paying you"
                        else -> "carrying for …${fields?.payerId?.takeLast(6) ?: "?"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
            }
            // The route so far: who signed it and every phone it has touched.
            Text(
                stored.packet.hops.joinToString("  →  ") { if (it == selfId) "you" else it.takeLast(6) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            stored.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stored.receipt != null) {
                Text(
                    "✓ Signed receipt from the bank",
                    style = MaterialTheme.typography.labelMedium,
                    color = SettledGreen,
                )
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onStop: () -> Unit,
) {
    var url by remember { mutableStateOf(settings.bankUrl) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        var name by remember { mutableStateOf(settings.displayName) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            label = { Text("Your name nearby") },
            supportingText = { Text("What other phones see when they look for someone to pay") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onChange { it.copy(displayName = name.trim()) } },
            enabled = name.trim() != settings.displayName,
        ) { Text("Save name") }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Bank URL") },
            supportingText = { Text("Printed by the mock bank on start. Same Wi-Fi or hotspot as this phone.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onChange { it.copy(bankUrl = url.trim()) } },
            enabled = url.trim() != settings.bankUrl && url.startsWith("http"),
        ) { Text("Save URL") }

        SettingRow(
            title = "Force offline",
            body = "Act as a relay even with a network. More reliable than airplane mode, which often turns Bluetooth off too.",
            checked = settings.forceOffline,
            onChecked = { v -> onChange { it.copy(forceOffline = v) } },
        )
        SettingRow(
            title = "On-device fallback bank",
            body = "If this phone is online but the bank does not answer, settle locally so a demo still completes. Always labelled.",
            checked = settings.useFallbackBank,
            onChecked = { v -> onChange { it.copy(useFallbackBank = v) } },
        )

        Text(
            settings.bankPubKey?.let { "Bank key pinned · …${it.takeLast(10)}" }
                ?: "Bank key not pinned yet — reach the bank once to trust its receipts",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (settings.bankPubKey != null) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop relaying") }
    }
}

@Composable
private fun SettingRow(title: String, body: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun wholeRupees(paise: Int) = "₹%,d".format(paise / 100)
