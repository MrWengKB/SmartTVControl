package com.mrwengkb.smarttvcontrol

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mrwengkb.smarttvcontrol.core.AndroidKey
import com.mrwengkb.smarttvcontrol.core.AndroidTvApps
import com.mrwengkb.smarttvcontrol.core.AndroidTvClient
import com.mrwengkb.smarttvcontrol.core.AndroidTvIdentity
import com.mrwengkb.smarttvcontrol.core.AndroidTvNotPairedException
import com.mrwengkb.smarttvcontrol.core.AndroidTvPairing
import com.mrwengkb.smarttvcontrol.core.AndroidTvWrongCodeException
import com.mrwengkb.smarttvcontrol.core.AppInfo
import com.mrwengkb.smarttvcontrol.core.TvPlatform
import com.mrwengkb.smarttvcontrol.core.TvProbe
import com.mrwengkb.smarttvcontrol.core.InputInfo
import com.mrwengkb.smarttvcontrol.core.KeyboardState
import com.mrwengkb.smarttvcontrol.core.keyboardUpdates
import com.mrwengkb.smarttvcontrol.core.textEdit
import com.mrwengkb.smarttvcontrol.core.PointerSocket
import com.mrwengkb.smarttvcontrol.core.SsdpDiscovery
import com.mrwengkb.smarttvcontrol.core.TvDevice
import com.mrwengkb.smarttvcontrol.core.VolumeStatus
import com.mrwengkb.smarttvcontrol.core.WakeOnLan
import com.mrwengkb.smarttvcontrol.core.WebOsClient
import com.mrwengkb.smarttvcontrol.core.channelDown
import com.mrwengkb.smarttvcontrol.core.channelUp
import com.mrwengkb.smarttvcontrol.core.deleteCharacters
import com.mrwengkb.smarttvcontrol.core.fastForward
import com.mrwengkb.smarttvcontrol.core.insertText
import com.mrwengkb.smarttvcontrol.core.launchApp
import com.mrwengkb.smarttvcontrol.core.listApps
import com.mrwengkb.smarttvcontrol.core.listInputs
import com.mrwengkb.smarttvcontrol.core.pause
import com.mrwengkb.smarttvcontrol.core.play
import com.mrwengkb.smarttvcontrol.core.powerOff
import com.mrwengkb.smarttvcontrol.core.rewind
import com.mrwengkb.smarttvcontrol.core.sendEnter
import com.mrwengkb.smarttvcontrol.core.setMute
import com.mrwengkb.smarttvcontrol.core.stop
import com.mrwengkb.smarttvcontrol.core.switchInput
import com.mrwengkb.smarttvcontrol.core.tvInfo
import com.mrwengkb.smarttvcontrol.core.volumeDown
import com.mrwengkb.smarttvcontrol.core.volumeUp
import com.mrwengkb.smarttvcontrol.core.volumeUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress

enum class Screen { Devices, Remote }

data class UiState(
    val screen: Screen = Screen.Devices,
    val discovering: Boolean = false,
    val discovered: List<TvDevice> = emptyList(),
    val saved: List<SavedTv> = emptyList(),
    val current: SavedTv? = null,
    val connection: WebOsClient.State = WebOsClient.State.Disconnected,
    val volume: VolumeStatus? = null,
    val apps: List<AppInfo>? = null,
    val inputs: List<InputInfo>? = null,
    val waking: Boolean = false,
    /** Non-null while we are registered as the TV's keyboard; focused when a text box is active. */
    val keyboard: KeyboardState? = null,
    /** A newer release to offer, shown as a dialog. */
    val update: UpdateInfo? = null,
    val checkingUpdate: Boolean = false,
    val message: String? = null,
    /** Android TV: the TV is showing a pairing code for the user to type in. */
    val pairingCode: Boolean = false,
    /** Android TV: goes up each time the TV asks for text, so the keyboard opens by itself. */
    val keyboardRequest: Int = 0,
    /** A typed IP whose TV didn't answer, so the user picks its OS. */
    val chooseOsFor: String? = null,
    /** Checking which OS a typed IP runs. */
    val probing: Boolean = false,
    /** Android TV: recent text-input events, shown in the keyboard sheet to diagnose typing. */
    val imeLog: List<String> = emptyList(),
    /** Android TV: type with key presses instead of the TV's text input. */
    val typeWithKeys: Boolean = false,
) {
    val isAndroidTv: Boolean get() = current?.platform == TvPlatform.ANDROID_TV
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TvStore(app)
    private val updates = UpdateChecker(app)
    private val _ui = MutableStateFlow(UiState(saved = store.all()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var client: WebOsClient? = null
    private var connectJob: Job? = null
    private var sessionJobs = mutableListOf<Job>()
    private var pointer: PointerSocket? = null
    private val pointerMutex = Mutex()

    // Google TV / Android TV session (only one of client and atv is in use at a time).
    private var atv: AndroidTvClient? = null
    private var atvPairing: AndroidTvPairing? = null
    private val identityMutex = Mutex()
    private var identity: AndroidTvIdentity? = null
    private val atvTyping = Channel<suspend (AndroidTvClient) -> Unit>(Channel.UNLIMITED)

    /** Text edits go to the TV one at a time, in order, so fast typing doesn't get scrambled. */
    private val typing = Channel<suspend (WebOsClient) -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (edit in typing) {
                val c = client?.takeIf { it.isConnected } ?: continue
                try {
                    edit(c)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showMessage(e.message ?: "Typing failed")
                }
            }
        }
        viewModelScope.launch {
            for (edit in atvTyping) {
                val c = atv?.takeIf { it.isConnected } ?: continue
                try {
                    edit(c)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showMessage(e.message ?: "Typing failed")
                }
            }
        }
        // Debug builds are test builds; only release builds look for updates on their own.
        if (!BuildConfig.DEBUG) checkForUpdates(manual = false)
        val last = store.last()
        if (last != null) {
            _ui.update { it.copy(screen = Screen.Remote, current = last) }
        } else {
            discover()
        }
    }

    // ---- Lifecycle -------------------------------------------------------------------------

    /** Called when the app comes to the foreground: reconnect to the current TV if needed. */
    fun onForeground() {
        val tv = _ui.value.current ?: return
        val state = _ui.value.connection
        if (connectJob?.isActive != true && state !is WebOsClient.State.Connected && !_ui.value.pairingCode) connect(tv)
    }

    /** Called when the app goes to the background: drop the connection to save battery. */
    fun onBackground() {
        disconnect()
    }

    override fun onCleared() {
        disconnect()
    }

    // ---- Devices ---------------------------------------------------------------------------

    fun showDevices() {
        _ui.update { it.copy(screen = Screen.Devices, saved = store.all()) }
        if (_ui.value.discovered.isEmpty()) discover()
    }

    fun showRemote() {
        if (_ui.value.current != null) _ui.update { it.copy(screen = Screen.Remote) }
    }

    fun discover() {
        if (_ui.value.discovering) return
        _ui.update { it.copy(discovering = true) }
        viewModelScope.launch {
            val wifi = getApplication<Application>().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("webos-discovery").apply { setReferenceCounted(false) }
            val found = try {
                lock.acquire()
                // webOS TVs answer SSDP; Google/Android TVs advertise over mDNS. Search both at once.
                val androidTvs = async {
                    runCatching { AndroidTvDiscovery(getApplication<Application>()).discover() }.getOrDefault(emptyList())
                }
                val webOs = SsdpDiscovery.discover()
                val webOsHosts = webOs.map { it.host }.toSet()
                (webOs + androidTvs.await().filterNot { it.host in webOsHosts }).sortedBy { it.name.lowercase() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Discovery failed: ${e.message}")
                emptyList()
            } finally {
                runCatching { lock.release() }
            }
            _ui.update { it.copy(discovering = false, discovered = found) }
        }
    }

    fun select(device: TvDevice) {
        val existing = store.all().firstOrNull { it.host == device.host }
        val tv = when {
            existing == null -> SavedTv(host = device.host, name = device.name, platform = device.platform)
            existing.platform == device.platform -> existing.copy(name = device.name)
            // Same address, different OS (e.g. a new TV): start again with no old credentials.
            else -> SavedTv(host = device.host, name = device.name, platform = device.platform)
        }
        select(tv)
    }

    fun select(tv: SavedTv) {
        _ui.update { it.copy(chooseOsFor = null) }
        store.save(tv)
        _ui.update { it.copy(screen = Screen.Remote, current = tv, saved = store.all(), apps = null, inputs = null) }
        connect(tv)
    }

    /** Adds a TV by IP: checks which OS it runs from its open ports, or asks if it doesn't answer. */
    fun addManually(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty() || _ui.value.probing) return
        val known = _ui.value.discovered.firstOrNull { it.host == trimmed }
        if (known != null) {
            select(known)
            return
        }
        _ui.update { it.copy(probing = true) }
        viewModelScope.launch {
            val platform = try {
                TvProbe.detect(trimmed)
            } finally {
                _ui.update { it.copy(probing = false) }
            }
            if (platform == null) _ui.update { it.copy(chooseOsFor = trimmed) } else addWithPlatform(trimmed, platform)
        }
    }

    fun addWithPlatform(host: String, platform: TvPlatform) {
        val name = when (platform) {
            TvPlatform.WEBOS -> "LG TV ($host)"
            TvPlatform.ANDROID_TV -> "Google TV ($host)"
        }
        select(TvDevice(host = host, name = name, platform = platform))
    }

    fun dismissChooseOs() = _ui.update { it.copy(chooseOsFor = null) }

    fun forget(tv: SavedTv) {
        store.remove(tv.host)
        if (_ui.value.current?.host == tv.host) {
            disconnect()
            _ui.update { it.copy(current = null) }
        }
        _ui.update { it.copy(saved = store.all()) }
    }

    // ---- Connection ------------------------------------------------------------------------

    fun reconnect() {
        _ui.value.current?.let(::connect)
    }

    private fun connect(tv: SavedTv) {
        if (tv.platform == TvPlatform.ANDROID_TV) {
            connectAndroidTv(tv)
            return
        }
        disconnect()
        val c = WebOsClient(tv.host)
        client = c
        sessionJobs += viewModelScope.launch {
            c.state.collect { s -> if (client === c) _ui.update { it.copy(connection = s) } }
        }
        connectJob = viewModelScope.launch {
            try {
                val key = c.connect(tv.clientKey)
                var updated = tv.copy(clientKey = key)
                saveCurrent(updated)
                onConnected(c)
                val info = runCatching { c.tvInfo() }.getOrNull()
                if (info?.macAddress != null && info.macAddress != updated.macAddress) {
                    updated = updated.copy(macAddress = info.macAddress)
                    saveCurrent(updated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // State flow already shows the failure; nothing else to do.
            }
        }
    }

    private fun saveCurrent(tv: SavedTv) {
        store.save(tv)
        _ui.update { it.copy(current = tv, saved = store.all()) }
    }

    private fun onConnected(c: WebOsClient) {
        sessionJobs += viewModelScope.launch {
            runCatching { c.volumeUpdates().collect { v -> _ui.update { it.copy(volume = v) } } }
        }
        sessionJobs += viewModelScope.launch {
            try {
                c.keyboardUpdates().collect { k -> _ui.update { it.copy(keyboard = k) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Older TVs may not support the remote keyboard; typing then falls back to plain insertText.
            }
        }
        viewModelScope.launch { ensurePointer() }
    }

    private fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        pointer?.close()
        pointer = null
        client?.close()
        client = null
        atv?.close()
        atv = null
        atvPairing?.close()
        atvPairing = null
        _ui.update {
            it.copy(connection = WebOsClient.State.Disconnected, volume = null, keyboard = null, pairingCode = false)
        }
    }

    // ---- Google TV / Android TV ---------------------------------------------------------------

    private suspend fun androidTvIdentity(): AndroidTvIdentity = identityMutex.withLock {
        identity ?: withContext(Dispatchers.Default) { store.androidTvIdentity() }.also { identity = it }
    }

    private fun AndroidTvClient.State.toUi(): WebOsClient.State = when (this) {
        AndroidTvClient.State.Connected -> WebOsClient.State.Connected
        AndroidTvClient.State.Connecting -> WebOsClient.State.Connecting
        AndroidTvClient.State.Disconnected -> WebOsClient.State.Disconnected
        is AndroidTvClient.State.Failed -> WebOsClient.State.Failed(message)
    }

    private fun connectAndroidTv(tv: SavedTv) {
        disconnect()
        _ui.update { it.copy(connection = WebOsClient.State.Connecting) }
        connectJob = viewModelScope.launch {
            val id = try {
                androidTvIdentity()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(connection = WebOsClient.State.Failed("Couldn't create this phone's key: ${e.message}")) }
                return@launch
            }
            val c = AndroidTvClient(tv.host, id, deviceModel = Build.MODEL ?: "Phone", deviceVendor = Build.MANUFACTURER ?: "Android")
            atv = c
            val stateJob = viewModelScope.launch {
                c.state.collect { s -> if (atv === c && !_ui.value.pairingCode) _ui.update { it.copy(connection = s.toUi()) } }
            }
            sessionJobs += stateJob
            try {
                c.connect()
                if (!tv.paired) saveCurrent(tv.copy(paired = true))
                onAndroidTvConnected(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndroidTvNotPairedException) {
                stateJob.cancel()
                if (tv.paired) saveCurrent(tv.copy(paired = false))
                startPairing(tv.host, id)
            } catch (e: Exception) {
                // The state flow already shows the failure.
            }
        }
    }

    /** Asks the TV to show a pairing code; the user then types it in ([submitPairingCode]). */
    private suspend fun startPairing(host: String, id: AndroidTvIdentity) {
        val p = AndroidTvPairing(host, id, clientName = "Smart TV Remote on ${Build.MODEL ?: "phone"}")
        atvPairing = p
        _ui.update { it.copy(connection = WebOsClient.State.AwaitingPairing, pairingCode = true) }
        try {
            p.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            p.close()
            if (atvPairing === p) atvPairing = null
            _ui.update {
                it.copy(pairingCode = false, connection = WebOsClient.State.Failed("Couldn't start pairing with the TV (${e.message})"))
            }
        }
    }

    fun submitPairingCode(code: String) {
        val p = atvPairing ?: return
        val tv = _ui.value.current ?: return
        viewModelScope.launch {
            try {
                p.finish(code)
                atvPairing = null
                _ui.update { it.copy(pairingCode = false) }
                saveCurrent(tv.copy(paired = true))
                connect(_ui.value.current ?: return@launch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AndroidTvWrongCodeException) {
                showMessage("That code doesn't match the one on the TV. Check it and try again.")
            } catch (e: Exception) {
                // The TV ended the pairing (for example it rejected the code): ask for a fresh code.
                showMessage("The TV didn't accept the code. A new code is on the TV.")
                startPairing(tv.host, androidTvIdentity())
            }
        }
    }

    fun setTypeWithKeys(on: Boolean) = _ui.update { it.copy(typeWithKeys = on) }

    fun cancelPairing() {
        atvPairing?.close()
        atvPairing = null
        _ui.update { it.copy(pairingCode = false, connection = WebOsClient.State.Disconnected) }
    }

    private fun onAndroidTvConnected(c: AndroidTvClient) {
        sessionJobs += viewModelScope.launch {
            c.volume.collect { v ->
                if (v != null) _ui.update { it.copy(volume = VolumeStatus(v.level, v.muted)) }
            }
        }
        sessionJobs += viewModelScope.launch {
            c.imeLog.collect { log -> _ui.update { it.copy(imeLog = log) } }
        }
        sessionJobs += viewModelScope.launch {
            c.keyboardShown.collect {
                _ui.update { it.copy(keyboard = KeyboardState(focused = true), keyboardRequest = it.keyboardRequest + 1) }
            }
        }
    }

    private fun atvCommand(block: suspend (AndroidTvClient) -> Unit) {
        val c = atv
        if (c == null || !c.isConnected) {
            showMessage("Not connected to the TV")
            return
        }
        viewModelScope.launch {
            try {
                block(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(e.message ?: "Command failed")
            }
        }
    }

    private fun atvKey(code: Int) = atvCommand { it.key(code) }

    /** Android TVs have no input list for remotes; this presses the remote's Input key instead. */
    fun inputKey() = atvKey(AndroidKey.TV_INPUT)

    private val isAndroidTv: Boolean get() = _ui.value.isAndroidTv

    private suspend fun ensurePointer(): PointerSocket? = pointerMutex.withLock {
        val c = client?.takeIf { it.isConnected } ?: return null
        pointer?.takeIf { it.isOpen }?.let { return it }
        pointer = runCatching { c.openPointerSocket() }.getOrNull()
        pointer
    }

    // ---- Commands --------------------------------------------------------------------------

    private fun command(block: suspend (WebOsClient) -> Unit) {
        val c = client
        if (c == null || !c.isConnected) {
            showMessage("Not connected to the TV")
            return
        }
        viewModelScope.launch {
            try {
                block(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(e.message ?: "Command failed")
            }
        }
    }

    fun button(name: String) {
        if (isAndroidTv) {
            AndroidKey.fromButton(name)?.let(::atvKey)
            return
        }
        viewModelScope.launch {
            val p = ensurePointer()
            if (p == null || !p.button(name)) {
                // The socket may have dropped; reopen once and retry.
                pointerMutex.withLock { pointer = null }
                if (ensurePointer()?.button(name) != true) showMessage("Not connected to the TV")
            }
        }
    }

    fun move(dx: Int, dy: Int) {
        if (isAndroidTv) return
        viewModelScope.launch { ensurePointer()?.move(dx, dy) }
    }

    fun click() {
        if (isAndroidTv) return
        viewModelScope.launch { ensurePointer()?.click() }
    }

    fun scroll(dy: Int) {
        if (isAndroidTv) return
        viewModelScope.launch { ensurePointer()?.scroll(0, dy) }
    }

    fun volumeUp() = if (isAndroidTv) atvKey(AndroidKey.VOLUME_UP) else command { it.volumeUp() }
    fun volumeDown() = if (isAndroidTv) atvKey(AndroidKey.VOLUME_DOWN) else command { it.volumeDown() }
    fun toggleMute() =
        if (isAndroidTv) atvKey(AndroidKey.VOLUME_MUTE) else command { it.setMute(!(_ui.value.volume?.muted ?: false)) }
    fun channelUp() = if (isAndroidTv) atvKey(AndroidKey.CHANNEL_UP) else command { it.channelUp() }
    fun channelDown() = if (isAndroidTv) atvKey(AndroidKey.CHANNEL_DOWN) else command { it.channelDown() }
    fun play() = if (isAndroidTv) atvKey(AndroidKey.MEDIA_PLAY) else command { it.play() }
    fun pause() = if (isAndroidTv) atvKey(AndroidKey.MEDIA_PAUSE) else command { it.pause() }
    fun stop() = if (isAndroidTv) atvKey(AndroidKey.MEDIA_STOP) else command { it.stop() }
    fun rewind() = if (isAndroidTv) atvKey(AndroidKey.MEDIA_REWIND) else command { it.rewind() }
    fun fastForward() = if (isAndroidTv) atvKey(AndroidKey.MEDIA_FAST_FORWARD) else command { it.fastForward() }
    fun launchApp(app: AppInfo) = if (isAndroidTv) atvCommand { it.launch(app.id) } else command { it.launchApp(app.id) }
    fun switchInput(input: InputInfo) = command { it.switchInput(input.id) }
    /** Mirrors an edit of the phone's text field onto the TV's focused text box. */
    fun typed(old: String, new: String) {
        val (delete, insert) = textEdit(old, new)
        if (delete == 0 && insert.isEmpty()) return
        if (isAndroidTv) {
            atvTyping.trySend { c ->
                repeat(delete) { c.key(AndroidKey.DEL) }
                if (c.hasTextField && !_ui.value.typeWithKeys) {
                    // The TV told us about its text box: insert the new text at its cursor.
                    c.insertText(insert)
                } else {
                    // Otherwise type with key presses, which covers letters, digits and a few symbols.
                    insert.forEach { ch -> AndroidKey.forChar(ch)?.let { c.key(it) } }
                }
            }
            return
        }
        typing.trySend { c ->
            if (delete > 0) c.deleteCharacters(delete)
            if (insert.isNotEmpty()) c.insertText(insert)
        }
    }

    fun sendEnter() {
        if (isAndroidTv) atvTyping.trySend { it.key(AndroidKey.ENTER) } else typing.trySend { it.sendEnter() }
    }

    fun backspace() {
        if (isAndroidTv) atvTyping.trySend { it.key(AndroidKey.DEL) } else typing.trySend { it.deleteCharacters(1) }
    }

    fun loadApps() {
        // Android TVs don't let remotes list installed apps, so offer a list of common ones.
        if (isAndroidTv) _ui.update { it.copy(apps = AndroidTvApps.popular) }
        else command { c -> _ui.update { it.copy(apps = c.listApps()) } }
    }
    fun loadInputs() = command { c -> _ui.update { it.copy(inputs = c.listInputs()) } }

    /** Turns the TV off when connected, otherwise tries to wake it with Wake-on-LAN. */
    fun power() {
        if (isAndroidTv) {
            androidTvPower()
            return
        }
        val c = client
        if (c != null && c.isConnected) {
            viewModelScope.launch {
                c.powerOff()
                disconnect()
            }
            return
        }
        val tv = _ui.value.current ?: return
        val mac = tv.macAddress
        if (mac == null) {
            showMessage("Connect once while the TV is on so the app can learn its MAC address for power-on.")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(waking = true) }
            try {
                val subnetBroadcast = withContext(Dispatchers.IO) {
                    runCatching {
                        val parts = InetAddress.getByName(tv.host).address
                        listOf(InetAddress.getByAddress(byteArrayOf(parts[0], parts[1], parts[2], 0xFF.toByte())))
                    }.getOrDefault(emptyList())
                }
                WakeOnLan.wake(mac, subnetBroadcast)
                // Give the TV time to boot its network stack, then keep trying to connect.
                repeat(8) {
                    delay(2_500)
                    if (_ui.value.current?.host != tv.host) return@launch
                    connect(_ui.value.current!!)
                    connectJob?.join()
                    if (client?.isConnected == true) return@launch
                }
                showMessage("The TV did not wake up. Check that \"Turn on via Wi-Fi\" is enabled on the TV.")
            } finally {
                _ui.update { it.copy(waking = false) }
            }
        }
    }

    /**
     * Android TVs keep their remote service running in standby, so the Power key both turns
     * them off and back on. If we're not connected yet, connect first and then press it.
     */
    private fun androidTvPower() {
        val c = atv
        if (c != null && c.isConnected) {
            atvKey(AndroidKey.POWER)
            return
        }
        val tv = _ui.value.current ?: return
        if (_ui.value.pairingCode) return
        viewModelScope.launch {
            _ui.update { it.copy(waking = true) }
            try {
                connect(tv)
                connectJob?.join()
                val now = atv
                if (now != null && now.isConnected) {
                    if (now.isOn.value != true) now.key(AndroidKey.POWER)
                } else if (!_ui.value.pairingCode) {
                    showMessage("The TV didn't answer. Check that it's plugged in and on the same Wi-Fi.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(e.message ?: "Power failed")
            } finally {
                _ui.update { it.copy(waking = false) }
            }
        }
    }

    // ---- Updates ---------------------------------------------------------------------------

    /**
     * Looks for a newer release. The automatic check on launch stays silent when there is
     * nothing new or the network fails, and skips a version the user ignored; a manual check
     * reports the result and offers ignored versions again.
     */
    fun checkForUpdates(manual: Boolean = true) {
        if (_ui.value.checkingUpdate) return
        _ui.update { it.copy(checkingUpdate = true) }
        viewModelScope.launch {
            try {
                val found = updates.latestNewerThan(BuildConfig.VERSION_NAME)
                when {
                    found == null -> if (manual) showMessage("You have the latest version (${BuildConfig.VERSION_NAME}).")
                    !manual && found.version == updates.ignoredVersion -> Unit
                    else -> _ui.update { it.copy(update = found) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) showMessage("Couldn't check for updates: ${e.message}")
            } finally {
                _ui.update { it.copy(checkingUpdate = false) }
            }
        }
    }

    /** "Ignore": don't offer this version again automatically. */
    fun ignoreUpdate() {
        _ui.value.update?.let { updates.ignoredVersion = it.version }
        _ui.update { it.copy(update = null) }
    }

    fun dismissUpdate() = _ui.update { it.copy(update = null) }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }
    fun messageShown() = _ui.update { it.copy(message = null) }
}
