package com.mrwengkb.smarttvcontrol.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mrwengkb.smarttvcontrol.RemoteViewModel
import com.mrwengkb.smarttvcontrol.bluetooth.BtState
import com.mrwengkb.smarttvcontrol.bluetooth.BtStatus

/** Sets up the Bluetooth remote: permission, Bluetooth on, pairing, and picking the TV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothSheet(bt: BtState, vm: RemoteViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) vm.startBluetooth()
        else vm.showMessage("The Bluetooth remote needs the Nearby devices permission.")
    }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.startBluetooth() }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshBluetooth() }

    fun needsPermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    fun askPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        } else {
            vm.startBluetooth()
        }
    }

    fun makeVisible() {
        try {
            discoverable.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
            )
        } catch (e: Exception) {
            vm.showMessage("Couldn't make the phone visible. Open the phone's Bluetooth settings instead.")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Bluetooth remote", style = MaterialTheme.typography.titleLarge)
            Text(
                "For when Wi-Fi isn't available. The phone acts as a Bluetooth keyboard and remote for the TV. " +
                    "Arrows, OK, Back, Home, volume, media keys and typing work. Opening apps, switching inputs " +
                    "and turning the TV on need Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            bt.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            when (bt.status) {
                BtStatus.UNSUPPORTED -> Text(
                    "This phone can't act as a Bluetooth keyboard. It needs Android 9 or later with Bluetooth.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                BtStatus.NEEDS_PERMISSION -> Action(
                    "Allow the app to use Bluetooth (Android calls it \"Nearby devices\").",
                    "Allow Bluetooth",
                ) { askPermission() }
                BtStatus.ADAPTER_OFF -> Action("The phone's Bluetooth is off.", "Turn on Bluetooth") {
                    try {
                        enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } catch (e: Exception) {
                        vm.showMessage("Turn on Bluetooth in the phone's settings.")
                    }
                }
                BtStatus.STOPPED -> Action(
                    "The Bluetooth remote is off.",
                    "Start Bluetooth remote",
                ) { if (needsPermission()) askPermission() else vm.startBluetooth() }
                BtStatus.STARTING -> Busy("Starting…")
                BtStatus.CONNECTING -> Busy("Connecting to ${bt.deviceName ?: "the TV"}…")
                BtStatus.READY, BtStatus.CONNECTED -> Unit
            }

            if (bt.status == BtStatus.CONNECTED) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BluetoothConnected, contentDescription = null)
                        Spacer(Modifier.size(12.dp))
                        Text("Connected to ${bt.deviceName}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = vm::disconnectBluetooth) { Text("Disconnect") }
                    }
                }
            }

            if (bt.status in listOf(BtStatus.READY, BtStatus.CONNECTING, BtStatus.CONNECTED)) {
                if (bt.paired.isNotEmpty()) {
                    Text("Paired devices", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Tap your TV to connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    bt.paired.forEach { device ->
                        val current = device.address == bt.deviceAddress
                        Card(Modifier.fillMaxWidth().clickable(enabled = !current) { vm.connectBluetooth(device.address) }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (device.looksLikeTv) Icons.Default.Tv else Icons.Default.Bluetooth, contentDescription = null)
                                Spacer(Modifier.size(12.dp))
                                Text(device.name, Modifier.weight(1f))
                                if (current) {
                                    Text(
                                        if (bt.connected) "Connected" else "Connecting…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                Text("Pair with a TV (once)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "1. Keep this screen open and tap Make phone visible.\n" +
                        "2. On the TV, search for Bluetooth devices:\n" +
                        "   • Google TV / Android TV: Settings > Remotes & Accessories > Pair remote or accessory.\n" +
                        "   • LG webOS: Settings > General (or Connection) > Devices or Bluetooth, then search for " +
                        "a keyboard or device. Not every LG model accepts Bluetooth keyboards.\n" +
                        "3. Pick \"${bt.phoneName ?: "this phone"}\" on the TV and confirm the code on both screens.\n" +
                        "After that, the app connects by itself whenever it's open.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::makeVisible) { Text("Make phone visible") }
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        } catch (e: ActivityNotFoundException) {
                            vm.showMessage("Couldn't open Bluetooth settings.")
                        }
                    }) { Text("Phone settings") }
                }
                TextButton(onClick = vm::stopBluetooth, modifier = Modifier.align(Alignment.End)) { Text("Turn off Bluetooth remote") }
            }
        }
    }
}

@Composable
private fun Action(text: String, button: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
