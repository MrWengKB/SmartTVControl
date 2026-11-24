package com.mrwengkb.smarttvcontrol.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mrwengkb.smarttvcontrol.RemoteViewModel
import com.mrwengkb.smarttvcontrol.UiState
import com.mrwengkb.smarttvcontrol.core.TvPlatform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(state: UiState, vm: RemoteViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Choose a TV") },
                navigationIcon = {
                    if (state.current != null) {
                        IconButton(onClick = vm::showRemote) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to remote")
                        }
                    }
                },
                actions = {
                    if (state.discovering) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::discover) {
                            Icon(Icons.Default.Refresh, contentDescription = "Search again")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val savedHosts = state.saved.map { it.host }.toSet()
        val newDevices = state.discovered.filterNot { it.host in savedHosts }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.saved.isNotEmpty()) {
                item { SectionTitle("Paired TVs") }
                items(state.saved, key = { "saved-" + it.host }) { tv ->
                    val online = state.discovered.any { it.host == tv.host }
                    DeviceRow(
                        title = tv.name,
                        subtitle = listOf(tv.platform.label, tv.host).joinToString(" · ") + if (online) " · on the network" else "",
                        onClick = { vm.select(tv) },
                        onForget = { vm.forget(tv) },
                    )
                }
            }
            item { SectionTitle(if (state.discovering) "Searching your network…" else "Found on your network") }
            if (newDevices.isEmpty() && !state.discovering) {
                item {
                    Text(
                        "No new TVs found. Make sure the phone and TV are on the same Wi-Fi and the TV is on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(newDevices, key = { "found-" + it.host }) { device ->
                DeviceRow(
                    title = device.name,
                    subtitle = listOfNotNull(device.platform.label, device.modelName, device.host).joinToString(" · "),
                    onClick = { vm.select(device) },
                )
            }
            item { Spacer(Modifier.size(8.dp)) }
            item { ManualEntry(probing = state.probing, onAdd = vm::addManually) }
            item {
                Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Version ${com.mrwengkb.smarttvcontrol.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { vm.checkForUpdates() },
                        enabled = !state.checkingUpdate,
                    ) { Text(if (state.checkingUpdate) "Checking…" else "Check for updates") }
                }
            }
        }
    }
    state.chooseOsFor?.let { host -> ChooseOsDialog(host, onPick = { vm.addWithPlatform(host, it) }, onDismiss = vm::dismissChooseOs) }
}

/** Shown when a typed IP doesn't answer on either OS's ports, e.g. because the TV is off. */
@Composable
private fun ChooseOsDialog(host: String, onPick: (TvPlatform) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which TV is this?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The TV at $host didn't answer, so the app couldn't tell what it runs. Choose its system:")
                TvPlatform.entries.forEach { platform ->
                    Card(Modifier.fillMaxWidth().clickable { onPick(platform) }) {
                        Text(platform.label, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun DeviceRow(title: String, subtitle: String, onClick: () -> Unit, onForget: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tv, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onForget != null) {
                IconButton(onClick = onForget) { Icon(Icons.Default.Delete, contentDescription = "Forget $title") }
            }
        }
    }
}

@Composable
private fun ManualEntry(probing: Boolean, onAdd: (String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Enter the TV's IP address")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("e.g. 192.168.1.20") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onAdd(host) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box {
                Button(onClick = { onAdd(host) }, enabled = host.isNotBlank() && !probing) {
                    if (probing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
                }
            }
        }
    }
}
