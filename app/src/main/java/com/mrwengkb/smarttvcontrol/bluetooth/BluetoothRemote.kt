package com.mrwengkb.smarttvcontrol.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.mrwengkb.smarttvcontrol.core.Hid
import com.mrwengkb.smarttvcontrol.core.HidAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

enum class BtStatus {
    /** No Bluetooth, or Android older than 9 (which has no API for acting as a keyboard). */
    UNSUPPORTED,
    /** Not started. */
    STOPPED,
    /** Android 12+: the user hasn't allowed "Nearby devices" yet. */
    NEEDS_PERMISSION,
    /** The phone's Bluetooth is off. */
    ADAPTER_OFF,
    STARTING,
    /** Ready as a Bluetooth keyboard and remote, waiting for the TV. */
    READY,
    CONNECTING,
    CONNECTED,
}

data class BtDevice(val address: String, val name: String, val looksLikeTv: Boolean)

data class BtState(
    val status: BtStatus = BtStatus.STOPPED,
    /** The phone's Bluetooth name, which the TV shows when pairing. */
    val phoneName: String? = null,
    /** The TV we're connected or connecting to. */
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    /** Devices this phone has paired with (the TV should be one of them). */
    val paired: List<BtDevice> = emptyList(),
    /** The user turned the Bluetooth remote on (kept across launches). */
    val enabled: Boolean = false,
    val error: String? = null,
) {
    val connected: Boolean get() = status == BtStatus.CONNECTED
}

/**
 * Makes the phone a Bluetooth keyboard with media keys (Android 9+ `BluetoothHidDevice`), so it
 * can control a TV without Wi-Fi. The TV pairs with the phone once, like any Bluetooth keyboard;
 * after that the app reconnects to it by itself.
 */
@SuppressLint("MissingPermission") // Every entry point checks hasPermission() first.
class BluetoothRemote(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = appContext.getSharedPreferences("bluetooth", Context.MODE_PRIVATE)

    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && adapter != null

    private val _state = MutableStateFlow(
        BtState(status = if (isSupported) BtStatus.STOPPED else BtStatus.UNSUPPORTED, enabled = prefs.getBoolean(KEY_ENABLED, false)),
    )
    val state: StateFlow<BtState> = _state.asStateFlow()

    // Bluetooth callbacks and report sending both run on this one thread, in order.
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val sendMutex = Mutex()

    private var hid: Any? = null // BluetoothHidDevice; typed Any so older Androids never load the class
    private var registered = false
    private var registering = false
    private var proxyPending = false
    /** Between start() and pause()/stop(). */
    @Volatile private var active = false
    private var host: BluetoothDevice? = null
    private var pendingConnect: String? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> if (active) start()
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            release()
                            _state.update { it.copy(status = BtStatus.ADAPTER_OFF, deviceName = null, deviceAddress = null) }
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshPaired()
            }
        }
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Turns the Bluetooth remote on (and remembers that), then reconnects to the last TV. */
    fun start() {
        if (!isSupported) return
        setEnabled(true)
        active = true
        if (!hasPermission()) {
            _state.update { it.copy(status = BtStatus.NEEDS_PERMISSION) }
            return
        }
        listenForChanges()
        refreshPaired()
        val a = adapter ?: return
        if (!a.isEnabled) {
            _state.update { it.copy(status = BtStatus.ADAPTER_OFF) }
            return
        }
        _state.update { it.copy(phoneName = runCatching { a.name }.getOrNull(), error = null) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startHid(a)
    }

    /** Turns the Bluetooth remote off for good (until [start]). */
    fun stop() {
        setEnabled(false)
        active = false
        release()
        if (isSupported) _state.update { it.copy(status = BtStatus.STOPPED, deviceName = null, deviceAddress = null) }
    }

    /** The app went to the background: let go of the TV so its own remote and keyboard work normally. */
    fun pause() {
        active = false
        release()
        if (isSupported && _state.value.status != BtStatus.UNSUPPORTED) {
            _state.update { it.copy(status = BtStatus.STOPPED, deviceName = null, deviceAddress = null) }
        }
    }

    /** The app came back: start again if the user had the Bluetooth remote on. */
    fun resume() {
        if (_state.value.enabled) start()
    }

    /** Connects to a TV this phone has already paired with. */
    fun connect(address: String) {
        if (!hasPermission()) return
        val a = adapter ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return
        val h = hid as? BluetoothHidDevice
        if (h == null || !registered) {
            pendingConnect = address
            start()
            return
        }
        scope.launch {
            host?.takeIf { it.address != address }?.let { runCatching { h.disconnect(it) } }
            _state.update { it.copy(status = BtStatus.CONNECTING, deviceName = device.safeName(), deviceAddress = address, error = null) }
            if (runCatching { h.connect(device) }.getOrDefault(false) != true) {
                _state.update { it.copy(status = BtStatus.READY, error = "Couldn't connect to ${device.safeName()}.") }
            }
        }
    }

    fun disconnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val h = hid as? BluetoothHidDevice ?: return
        val d = host ?: return
        scope.launch { runCatching { h.disconnect(d) } }
    }

    fun refreshPaired() {
        if (!isSupported || !hasPermission()) return
        val devices = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .map { BtDevice(it.address, it.safeName(), it.looksLikeTv()) }
            .sortedWith(compareByDescending<BtDevice> { it.looksLikeTv }.thenBy { it.name.lowercase() })
        _state.update { it.copy(paired = devices) }
    }

    /** Sends a key press and release. Returns false when no TV is connected. */
    fun send(action: HidAction): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val h = hid as? BluetoothHidDevice ?: return false
        val d = host ?: return false
        if (!_state.value.connected) return false
        scope.launch {
            sendMutex.withLock {
                runCatching {
                    when (action) {
                        is HidAction.Key -> {
                            h.sendReport(d, Hid.KEYBOARD_REPORT_ID, Hid.keyboardReport(action.press))
                            delay(PRESS_MS)
                            h.sendReport(d, Hid.KEYBOARD_REPORT_ID, Hid.keyboardReport(null))
                        }
                        is HidAction.Consumer -> {
                            h.sendReport(d, Hid.CONSUMER_REPORT_ID, Hid.consumerReport(action.usage))
                            delay(PRESS_MS)
                            h.sendReport(d, Hid.CONSUMER_REPORT_ID, Hid.consumerReport(0))
                        }
                    }
                    delay(GAP_MS)
                }
            }
        }
        return true
    }

    fun close() {
        release()
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
        scope.cancel()
        executor.shutdown()
    }

    // ---- Internals ---------------------------------------------------------------------------

    private fun setEnabled(on: Boolean) {
        if (_state.value.enabled != on) prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _state.update { it.copy(enabled = on) }
    }

    private fun listenForChanges() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun startHid(a: BluetoothAdapter) {
        val h = hid as? BluetoothHidDevice
        if (h != null) {
            if (!registered && !registering) register(h)
            return
        }
        if (proxyPending) return
        proxyPending = true
        _state.update { it.copy(status = BtStatus.STARTING) }
        val ok = a.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                proxyPending = false
                val device = proxy as BluetoothHidDevice
                if (!active) {
                    // Stopped while the proxy was on its way.
                    runCatching { a.closeProfileProxy(BluetoothProfile.HID_DEVICE, device) }
                    return
                }
                hid = device
                register(device)
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                registered = false
                host = null
                _state.update { it.copy(status = BtStatus.STOPPED, deviceName = null, deviceAddress = null) }
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) {
            proxyPending = false
            fail("This phone's Bluetooth can't act as a keyboard.")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun register(h: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Smart TV Remote",
            "Keyboard and media remote",
            "SmartTVControl",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            Hid.DESCRIPTOR,
        )
        registering = true
        val ok = runCatching {
            h.registerApp(sdp, null, null, executor, callback as BluetoothHidDevice.Callback)
        }.getOrDefault(false)
        if (!ok) registering = false
        if (!ok) fail("Couldn't start the Bluetooth keyboard. Another app may be using it; close it and try again.")
    }

    private val callback: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HidCallback() else null

    @RequiresApi(Build.VERSION_CODES.P)
    private inner class HidCallback : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BluetoothRemote.registered = registered
            registering = false
            if (!registered) {
                host = null
                if (_state.value.status != BtStatus.ADAPTER_OFF && hid != null) {
                    _state.update { it.copy(status = BtStatus.STOPPED, deviceName = null, deviceAddress = null) }
                }
                return
            }
            _state.update { it.copy(status = BtStatus.READY) }
            // Reconnect: the device the stack offers, a TV the user just picked, or the last TV.
            val target = pendingConnect ?: pluggedDevice?.address ?: prefs.getString(KEY_LAST, null)
            pendingConnect = null
            if (target != null) connect(target)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    prefs.edit().putString(KEY_LAST, device.address).apply()
                    _state.update {
                        it.copy(status = BtStatus.CONNECTED, deviceName = device.safeName(), deviceAddress = device.address, error = null)
                    }
                    refreshPaired()
                }
                BluetoothProfile.STATE_CONNECTING -> _state.update {
                    it.copy(status = BtStatus.CONNECTING, deviceName = device.safeName(), deviceAddress = device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnecting = _state.value.status == BtStatus.CONNECTING && _state.value.deviceAddress == device.address
                    if (host == null || host?.address == device.address) {
                        host = null
                        if (registered) _state.update {
                            it.copy(
                                status = BtStatus.READY,
                                deviceName = null,
                                deviceAddress = null,
                                error = if (wasConnecting) "${device.safeName()} didn't accept the connection. Is it on and paired with this phone?" else null,
                            )
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid as? BluetoothHidDevice ?: return
            if (type != BluetoothHidDevice.REPORT_TYPE_INPUT) {
                runCatching { h.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ) }
                return
            }
            val empty = if (id.toInt() == Hid.CONSUMER_REPORT_ID) Hid.consumerReport(0) else Hid.keyboardReport(null)
            runCatching { h.replyReport(device, type, id, empty) }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // Keyboard LEDs (Caps Lock etc.); nothing to show, just acknowledge.
            val h = hid as? BluetoothHidDevice ?: return
            runCatching { h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(status = BtStatus.STOPPED, error = message) }
    }

    private fun release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val h = hid as? BluetoothHidDevice ?: return
        runCatching { host?.let(h::disconnect) }
        runCatching { h.unregisterApp() }
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null
        registered = false
        registering = false
        host = null
    }

    private fun BluetoothDevice.safeName(): String = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address

    private fun BluetoothDevice.looksLikeTv(): Boolean = runCatching {
        bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    }.getOrDefault(false)

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST = "last"
        const val PRESS_MS = 30L
        const val GAP_MS = 10L
    }
}
