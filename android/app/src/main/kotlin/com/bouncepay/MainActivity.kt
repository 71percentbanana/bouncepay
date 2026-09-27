package com.bouncepay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.bouncepay.mesh.MeshNode
import com.bouncepay.mesh.MeshService
import com.bouncepay.mesh.MeshStatus
import com.bouncepay.mesh.Role
import com.bouncepay.model.PacketState
import com.bouncepay.model.StoredPacket
import com.bouncepay.store.Settings
import com.bouncepay.ui.BouncePayTheme
import com.bouncepay.ui.CarryGold
import com.bouncepay.ui.SettledGreen
import kotlinx.coroutines.launch

/** The merchant every demo payment goes to; the mock bank opens it on start. */
private const val PAYEE_ID = "campus-stationery"
private const val PAYEE_NAME = "Campus Stationery"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshScreen(mesh: MeshNode) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val status by mesh.status.collectAsState()
    val packets by mesh.store.packets.collectAsState()
    val settings by mesh.prefs.settings.collectAsState()

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
                    onAmount = { amountPaise = it },
                    onPay = {
                        mesh.pay(amountPaise, PAYEE_ID)
                            .onSuccess { say("Signed ${it.fields.rupees} — it will hop to the bank") }
                            .onFailure { say(it.message ?: "Could not sign the payment") }
                    },
                )
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
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
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
    onAmount: (Int) -> Unit,
    onPay: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(
                    "Pay",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(PAYEE_NAME, style = MaterialTheme.typography.titleMedium)
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
                    if (mine) "your payment" else "carrying for ${fields?.payerId?.takeLast(6) ?: "?"}",
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
