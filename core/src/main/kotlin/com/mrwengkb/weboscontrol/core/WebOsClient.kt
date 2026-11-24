package com.mrwengkb.weboscontrol.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for the webOS "Second Screen Application Protocol" (SSAP): JSON messages over a
 * WebSocket on port 3001 (TLS, 2018+ firmware) or 3000 (plain, older TVs).
 *
 * Call [connect] first; it registers with the TV, which shows a pairing prompt the first time.
 * Keep the returned client key and pass it next time to connect without a prompt.
 */
class WebOsClient(
    val host: String,
    private val httpClient: OkHttpClient = LanHttp.client,
    private val endpoints: List<String> = listOf("wss://$host:3001", "ws://$host:3000"),
) {
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        /** The TV is showing the "allow this device?" prompt. */
        data object AwaitingPairing : State
        data object Connected : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null
    private val handlers = ConcurrentHashMap<String, (JsonObject) -> Unit>()
    private val nextId = AtomicInteger(1)

    val isConnected: Boolean get() = socket != null && _state.value == State.Connected

    /**
     * Opens the connection and registers. Returns the client key to store for next time.
     * Throws [WebOsException] if the TV cannot be reached or pairing is refused or times out.
     */
    suspend fun connect(clientKey: String?, pairingTimeoutMs: Long = 60_000): String {
        close()
        _state.value = State.Connecting
        var lastError: Throwable? = null
        for (url in endpoints) {
            val ws = try {
                openSocket(url, onMessage = ::dispatch, onLost = ::connectionLost)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Disconnected
                throw e
            } catch (e: Exception) {
                lastError = e
                continue
            }
            socket = ws
            try {
                val key = register(ws, clientKey, pairingTimeoutMs)
                _state.value = State.Connected
                return key
            } catch (e: Throwable) {
                socket = null
                ws.cancel()
                failPending("Registration aborted")
                _state.value = if (e is kotlinx.coroutines.CancellationException) State.Disconnected
                else State.Failed(e.message ?: "Pairing failed")
                throw e
            }
        }
        val message = "Could not reach the TV at $host (${lastError?.message ?: "no endpoint"})"
        _state.value = State.Failed(message)
        throw WebOsException(message, lastError)
    }

    private suspend fun register(ws: WebSocket, clientKey: String?, timeoutMs: Long): String {
        val id = "register_${nextId.getAndIncrement()}"
        val updates = Channel<JsonObject>(Channel.UNLIMITED)
        handlers[id] = { updates.trySend(it) }
        try {
            send(ws, buildJsonObject {
                put("type", "register")
                put("id", id)
                put("payload", Pairing.payload(clientKey))
            })
            return withTimeout(timeoutMs) {
                var key: String? = null
                while (key == null) {
                    val msg = updates.receive()
                    val payload = msg["payload"] as? JsonObject
                    when (msg.string("type")) {
                        "registered" -> key = payload?.string("client-key")
                            ?: throw WebOsException("TV did not return a client key")
                        "response" -> if (payload?.string("pairingType") == "PROMPT") {
                            _state.value = State.AwaitingPairing
                        }
                        "error" -> throw WebOsException(msg.string("error") ?: "Pairing was rejected")
                    }
                }
                key
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw WebOsException("Pairing timed out. Accept the prompt on the TV and try again.", e)
        } finally {
            handlers.remove(id)
        }
    }

    /** Sends a request and returns its payload. Throws [WebOsException] on an error reply. */
    suspend fun request(uri: String, payload: JsonObject? = null, timeoutMs: Long = 8_000): JsonObject {
        val ws = socket ?: throw WebOsException("Not connected")
        val id = "req_${nextId.getAndIncrement()}"
        val reply = CompletableDeferred<JsonObject>()
        handlers[id] = { reply.complete(it) }
        try {
            send(ws, message("request", id, uri, payload))
            val msg = try {
                withTimeout(timeoutMs) { reply.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw WebOsException("No reply from TV for $uri", e)
            }
            return checkReply(msg, uri)
        } finally {
            handlers.remove(id)
        }
    }

    /** Subscribes to [uri]; the flow emits each payload until it is cancelled. */
    fun subscribe(uri: String, payload: JsonObject? = null): Flow<JsonObject> = callbackFlow {
        val ws = socket ?: throw WebOsException("Not connected")
        val id = "sub_${nextId.getAndIncrement()}"
        handlers[id] = { msg ->
            try {
                trySend(checkReply(msg, uri))
            } catch (e: WebOsException) {
                close(e)
            }
        }
        send(ws, message("subscribe", id, uri, payload))
        awaitClose {
            handlers.remove(id)
            socket?.send(json.encodeToString(JsonObject.serializer(), message("unsubscribe", id, uri, null)))
        }
    }

    /** Opens the TV's pointer/button input socket, used for d-pad keys and the touchpad. */
    suspend fun openPointerSocket(): PointerSocket {
        val payload = request("ssap://com.webos.service.networkinput/getPointerInputSocket")
        val path = payload.string("socketPath") ?: throw WebOsException("TV returned no pointer socket")
        lateinit var pointer: PointerSocket
        val ws = openSocket(path, onMessage = { _, _ -> }, onLost = { _, _ -> pointer.markClosed() })
        pointer = PointerSocket(ws)
        return pointer
    }

    fun close() {
        val ws = socket
        socket = null
        ws?.close(1000, null)
        _state.value = State.Disconnected
        failPending("Disconnected")
    }

    private suspend fun openSocket(
        url: String,
        onMessage: (WebSocket, String) -> Unit,
        onLost: (WebSocket, String) -> Unit,
    ): WebSocket = suspendCancellableCoroutine { cont ->
        val listener = object : WebSocketListener() {
            @Volatile
            var opened = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                if (cont.isActive) cont.resume(webSocket) else webSocket.cancel()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onMessage(webSocket, text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                onLost(webSocket, "Connection closed by TV")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                onLost(webSocket, "Connection closed")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened) {
                    if (cont.isActive) cont.resumeWithException(WebOsException("$url: ${t.message}", t))
                } else {
                    onLost(webSocket, t.message ?: "Connection lost")
                }
            }
        }
        val ws = httpClient.newWebSocket(Request.Builder().url(url).build(), listener)
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun dispatch(ws: WebSocket, text: String) {
        if (ws !== socket) return
        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = msg.string("id") ?: return
        handlers[id]?.invoke(msg)
    }

    private fun connectionLost(ws: WebSocket, reason: String) {
        if (ws !== socket) return
        socket = null
        _state.value = State.Failed(reason)
        failPending(reason)
    }

    private fun failPending(reason: String) {
        val error = buildJsonObject {
            put("type", "error")
            put("error", reason)
        }
        handlers.values.toList().forEach { it(error) }
    }

    private fun send(ws: WebSocket, msg: JsonObject) {
        if (!ws.send(json.encodeToString(JsonObject.serializer(), msg))) throw WebOsException("Not connected")
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        private fun message(type: String, id: String, uri: String, payload: JsonObject?) = buildJsonObject {
            put("type", type)
            put("id", id)
            put("uri", uri)
            if (payload != null) put("payload", payload)
        }

        internal fun checkReply(msg: JsonObject, uri: String): JsonObject {
            val payload = msg["payload"] as? JsonObject ?: JsonObject(emptyMap())
            if (msg.string("type") == "error") {
                throw WebOsException(msg.string("error") ?: payload.string("errorText") ?: "$uri failed")
            }
            if ((payload["returnValue"] as? JsonPrimitive)?.booleanOrNull == false) {
                throw WebOsException(payload.string("errorText") ?: "$uri failed")
            }
            return payload
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** The register payload, including LG's signed manifest that grants the remote-control permissions. */
object Pairing {
    private val basePayload: JsonObject by lazy {
        val text = Pairing::class.java.getResourceAsStream("pairing.json")?.use { it.readBytes().decodeToString() }
            ?: error("pairing.json resource missing")
        WebOsClient.json.parseToJsonElement(text).jsonObject
    }

    fun payload(clientKey: String?): JsonObject =
        if (clientKey.isNullOrBlank()) basePayload
        else JsonObject(basePayload + ("client-key" to JsonPrimitive(clientKey)))
}

/**
 * The pointer input socket. Messages are `key:value` lines ending in a blank line, for example
 * `type:button\nname:UP\n\n`.
 */
class PointerSocket internal constructor(private val ws: WebSocket) {
    @Volatile
    var isOpen = true
        private set

    internal fun markClosed() {
        isOpen = false
    }

    fun button(name: String) = send("button", "name" to name)
    fun move(dx: Int, dy: Int, down: Boolean = false) = send("move", "dx" to dx, "dy" to dy, "down" to if (down) 1 else 0)
    fun click() = send("click")
    fun scroll(dx: Int, dy: Int) = send("scroll", "dx" to dx, "dy" to dy)

    private fun send(type: String, vararg fields: Pair<String, Any>): Boolean =
        isOpen && ws.send(format(type, *fields))

    fun close() {
        isOpen = false
        ws.close(1000, null)
    }

    companion object {
        fun format(type: String, vararg fields: Pair<String, Any>): String =
            (listOf("type:$type") + fields.map { (k, v) -> "$k:$v" }).joinToString("\n") + "\n\n"
    }
}
