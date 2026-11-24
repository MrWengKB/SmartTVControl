package com.mrwengkb.smarttvcontrol.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Android key codes the remote protocol accepts (the same numbers as android.view.KeyEvent). */
object AndroidKey {
    const val HOME = 3
    const val BACK = 4
    const val NUM_0 = 7 // 0-9 are 7..16
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val A = 29 // a-z are 29..54
    const val COMMA = 55
    const val PERIOD = 56
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val MINUS = 69
    const val EQUALS = 70
    const val SLASH = 76
    const val AT = 77
    const val PLUS = 81
    const val MENU = 82
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val VOLUME_MUTE = 164
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val GUIDE = 172
    const val SETTINGS = 176
    const val TV_INPUT = 178
    const val PROG_RED = 183
    const val PROG_GREEN = 184
    const val PROG_YELLOW = 185
    const val PROG_BLUE = 186

    fun number(n: Int) = NUM_0 + n

    /** Maps the webOS button names used by the remote screen to Android key codes. */
    fun fromButton(name: String): Int? = when (name) {
        TvButton.UP -> DPAD_UP
        TvButton.DOWN -> DPAD_DOWN
        TvButton.LEFT -> DPAD_LEFT
        TvButton.RIGHT -> DPAD_RIGHT
        TvButton.ENTER -> DPAD_CENTER
        TvButton.BACK, TvButton.EXIT -> BACK
        TvButton.HOME -> HOME
        TvButton.MENU -> SETTINGS
        TvButton.INFO -> INFO
        TvButton.GUIDE -> GUIDE
        TvButton.RED -> PROG_RED
        TvButton.GREEN -> PROG_GREEN
        TvButton.YELLOW -> PROG_YELLOW
        TvButton.BLUE -> PROG_BLUE
        TvButton.DASH -> MINUS
        else -> name.toIntOrNull()?.takeIf { it in 0..9 }?.let(::number)
    }

    /** The key that types [c], for TVs where no text box has been announced; null if there is none. */
    fun forChar(c: Char): Int? = when (c.lowercaseChar()) {
        in 'a'..'z' -> A + (c.lowercaseChar() - 'a')
        in '0'..'9' -> NUM_0 + (c - '0')
        ' ' -> SPACE
        ',' -> COMMA
        '.' -> PERIOD
        '-' -> MINUS
        '=' -> EQUALS
        '/' -> SLASH
        '@' -> AT
        '+' -> PLUS
        else -> null
    }
}

data class AndroidTvVolume(val level: Int, val max: Int, val muted: Boolean)

/**
 * Client for the Android TV Remote protocol v2 (Google TV and Android TV), port 6466. The
 * connection is TLS with the client certificate the TV accepted during [AndroidTvPairing].
 *
 * Messages are protobuf "RemoteMessage"s, each preceded by its length. The TV opens with
 * remote_configure, then remote_set_active and remote_start; it also pings every few seconds
 * and drops the connection if we don't answer.
 */
class AndroidTvClient(
    val host: String,
    private val identity: AndroidTvIdentity,
    private val port: Int = PORT,
    private val deviceModel: String = "Smart TV Remote",
    private val deviceVendor: String = "MrWengKB",
) {
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data object Connected : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _volume = MutableStateFlow<AndroidTvVolume?>(null)
    val volume: StateFlow<AndroidTvVolume?> = _volume.asStateFlow()

    /** Whether the screen is on, as last reported by the TV (null until it says). */
    private val _isOn = MutableStateFlow<Boolean?>(null)
    val isOn: StateFlow<Boolean?> = _isOn.asStateFlow()

    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()

    /** Emits whenever the TV announces that a text box wants input. */
    private val _keyboardShown = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val keyboardShown: SharedFlow<Unit> = _keyboardShown.asSharedFlow()

    @Volatile private var socket: SSLSocket? = null
    private var scope: CoroutineScope? = null
    private val writeLock = Any()

    @Volatile private var imeCounter: Int? = null
    @Volatile private var fieldCounter: Int? = null

    val isConnected: Boolean get() = socket != null && _state.value == State.Connected

    /** True once the TV has told us about a text field, so [setText] can be used. */
    val hasTextField: Boolean get() = imeCounter != null && fieldCounter != null

    /**
     * Connects and waits for the TV to accept the session. Throws [AndroidTvNotPairedException]
     * when the TV rejects our certificate, so the caller can pair and try again.
     */
    suspend fun connect(timeoutMs: Long = 10_000) {
        close()
        _state.value = State.Connecting
        val s = try {
            runInterruptible(Dispatchers.IO) { identity.connect(host, port) }
        } catch (e: java.net.SocketException) {
            if (e is java.net.ConnectException || e is java.net.NoRouteToHostException) throw unreachable(e)
            // The TCP connection worked but TLS was cut off: the TV rejected our certificate.
            _state.value = State.Disconnected
            throw AndroidTvNotPairedException(e)
        } catch (e: SSLException) {
            _state.value = State.Disconnected
            throw AndroidTvNotPairedException(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = State.Disconnected
            throw e
        } catch (e: Exception) {
            throw unreachable(e)
        }
        s.soTimeout = 0
        socket = s
        val ready = CompletableDeferred<Unit>()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sessionScope
        sessionScope.launch { readLoop(s, ready) }
        try {
            withTimeout(timeoutMs) { ready.await() }
            _state.value = State.Connected
        } catch (e: Throwable) {
            close()
            when (e) {
                is AndroidTvNotPairedException -> { _state.value = State.Disconnected; throw e }
                is kotlinx.coroutines.CancellationException -> {
                    if (e is kotlinx.coroutines.TimeoutCancellationException) {
                        val message = "The TV at $host did not answer"
                        _state.value = State.Failed(message)
                        throw AndroidTvException(message, e)
                    }
                    _state.value = State.Disconnected
                    throw e
                }
                else -> {
                    val message = e.message ?: "Connection failed"
                    _state.value = State.Failed(message)
                    throw AndroidTvException(message, e)
                }
            }
        }
    }

    private fun unreachable(e: Exception): AndroidTvException {
        val message = "Could not reach the TV at $host (${e.message ?: e.javaClass.simpleName})"
        _state.value = State.Failed(message)
        return AndroidTvException(message, e)
    }

    private fun readLoop(s: SSLSocket, ready: CompletableDeferred<Unit>) {
        var configured = false
        try {
            while (true) {
                val msg = ProtoMessage.parse(ProtoFraming.read(s.inputStream))
                when {
                    msg.has(CONFIGURE) -> {
                        configured = true
                        send(ProtoWriter().message(CONFIGURE) {
                            int(1, FEATURES)
                            message(2) {
                                string(1, deviceModel)
                                string(2, deviceVendor)
                                int(3, 1)
                                string(4, "1")
                                string(5, "atvremote")
                                string(6, "1.0.0")
                            }
                        })
                    }
                    msg.has(SET_ACTIVE) -> {
                        send(ProtoWriter().message(SET_ACTIVE) { int(1, FEATURES) })
                        ready.complete(Unit)
                    }
                    msg.has(PING_REQUEST) -> {
                        val v = msg.message(PING_REQUEST)?.int(1) ?: 0
                        send(ProtoWriter().message(PING_RESPONSE) { int(1, v) })
                    }
                    msg.has(START) -> {
                        _isOn.value = msg.message(START)?.bool(1) ?: false
                        ready.complete(Unit)
                    }
                    msg.has(SET_VOLUME_LEVEL) -> msg.message(SET_VOLUME_LEVEL)?.let { v ->
                        _volume.value = AndroidTvVolume(v.int(7) ?: 0, v.int(6) ?: 0, v.bool(8) ?: false)
                    }
                    msg.has(IME_KEY_INJECT) -> msg.message(IME_KEY_INJECT)?.let { inject ->
                        inject.message(1)?.let { app ->
                            app.string(12)?.let { _currentApp.value = it }
                            app.int(1)?.let { imeCounter = it }
                        }
                        inject.message(2)?.int(1)?.let { fieldCounter = it }
                    }
                    msg.has(IME_BATCH_EDIT) -> msg.message(IME_BATCH_EDIT)?.let { edit ->
                        edit.int(1)?.let { imeCounter = it }
                        edit.int(2)?.let { fieldCounter = it }
                    }
                    msg.has(IME_SHOW_REQUEST) -> {
                        msg.message(IME_SHOW_REQUEST)?.message(2)?.int(1)?.let { fieldCounter = it }
                        if (imeCounter == null) imeCounter = 0
                        if (fieldCounter == null) fieldCounter = 0
                        _keyboardShown.tryEmit(Unit)
                    }
                }
            }
        } catch (e: Exception) {
            if (!ready.isCompleted) {
                // A TV that doesn't know our certificate closes the connection (or fails the
                // TLS handshake late, with TLS 1.3) before it ever configures the session.
                ready.completeExceptionally(
                    if (!configured && (e is SSLException || e is EOFException || e is IOException)) AndroidTvNotPairedException(e)
                    else e,
                )
            } else if (socket === s) {
                socket = null
                _state.value = State.Failed("The connection to the TV was lost")
            }
        }
    }

    private fun send(body: ProtoWriter) {
        val s = socket ?: throw AndroidTvException("Not connected to the TV")
        synchronized(writeLock) { ProtoFraming.write(s.outputStream, body.toByteArray()) }
    }

    private suspend fun sendAsync(body: ProtoWriter) = withContext(Dispatchers.IO) {
        try {
            send(body)
        } catch (e: IOException) {
            throw AndroidTvException("Not connected to the TV", e)
        }
    }

    /** Presses and releases a key. */
    suspend fun key(code: Int) = sendAsync(ProtoWriter().message(KEY_INJECT) { int(1, code); int(2, DIRECTION_SHORT) })

    /** Opens an app link, such as `market://launch?id=com.netflix.ninja` or an https deep link. */
    suspend fun launch(link: String) = sendAsync(ProtoWriter().message(APP_LINK_LAUNCH) { string(1, link) })

    /** Replaces the text in the TV's focused text box with [text], cursor at the end. */
    suspend fun setText(text: String) {
        val ime = imeCounter ?: 0
        val field = fieldCounter ?: 0
        sendAsync(ProtoWriter().message(IME_BATCH_EDIT) {
            int(1, ime)
            int(2, field)
            message(3) {
                int(1, 1)
                message(2) {
                    int(1, text.length - 1)
                    int(2, text.length - 1)
                    string(3, text)
                }
            }
        })
    }

    fun close() {
        val s = socket
        socket = null
        runCatching { s?.close() }
        scope?.cancel()
        scope = null
        imeCounter = null
        fieldCounter = null
        if (_state.value !is State.Failed) _state.value = State.Disconnected
    }

    companion object {
        const val PORT = 6466

        // RemoteMessage field numbers.
        const val CONFIGURE = 1
        const val SET_ACTIVE = 2
        const val ERROR = 3
        const val PING_REQUEST = 8
        const val PING_RESPONSE = 9
        const val KEY_INJECT = 10
        const val IME_KEY_INJECT = 20
        const val IME_BATCH_EDIT = 21
        const val IME_SHOW_REQUEST = 22
        const val START = 40
        const val SET_VOLUME_LEVEL = 50
        const val APP_LINK_LAUNCH = 90

        const val DIRECTION_SHORT = 3

        /** Features we support: ping, keys, text input, power, volume and app links. */
        const val FEATURES = 1 or 2 or 4 or 32 or 64 or 512
    }
}

/** A starter list of apps for Android TVs, which don't let remotes list installed apps. */
object AndroidTvApps {
    val popular: List<AppInfo> = listOf(
        "YouTube" to "com.google.android.youtube.tv",
        "Netflix" to "com.netflix.ninja",
        "Prime Video" to "com.amazon.amazonvideo.livingroom",
        "Disney+" to "com.disney.disneyplus",
        "YouTube Music" to "com.google.android.youtube.tvmusic",
        "Spotify" to "com.spotify.tv.android",
        "Plex" to "com.plexapp.android",
        "Twitch" to "tv.twitch.android.app",
        "Apple TV" to "com.apple.atve.androidtv.appletv",
        "Google Play Store" to "com.android.vending",
    ).map { (title, pkg) -> AppInfo(id = "market://launch?id=$pkg", title = title, iconUrl = null) }
}
