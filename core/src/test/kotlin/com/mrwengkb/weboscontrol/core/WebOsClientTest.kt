package com.mrwengkb.weboscontrol.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Talks to a fake TV served by MockWebServer. */
class WebOsClientTest {
    private lateinit var server: MockWebServer
    private val received = LinkedBlockingQueue<JsonObject>()
    private val pointerReceived = LinkedBlockingQueue<String>()

    /** Behaviour of the fake TV: reply to each incoming SSAP message. */
    private var tv: (WebSocket, JsonObject) -> Unit = { _, _ -> }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() } // open WebSockets can make shutdown give up; harmless here
    }

    private fun enqueueTv() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Json.parseToJsonElement(text).jsonObject
                received.add(msg)
                tv(webSocket, msg)
            }
        }))
    }

    private fun client() = WebOsClient(
        host = server.hostName,
        endpoints = listOf("ws://${server.hostName}:${server.port}/"),
    )

    private fun reply(ws: WebSocket, json: String) {
        ws.send(json)
    }

    private val pairingTv: (WebSocket, JsonObject) -> Unit = { ws, msg ->
        val id = msg["id"]!!.jsonPrimitive.content
        when (msg["type"]!!.jsonPrimitive.content) {
            "register" -> {
                val payload = msg["payload"]!!.jsonObject
                if (payload["client-key"] == null) {
                    reply(ws, """{"type":"response","id":"$id","payload":{"pairingType":"PROMPT","returnValue":true}}""")
                    Thread.sleep(100)
                }
                reply(ws, """{"type":"registered","id":"$id","payload":{"client-key":"KEY123"}}""")
            }
            "request" -> when (msg["uri"]!!.jsonPrimitive.content) {
                Ssap.GET_VOLUME -> reply(ws, """{"type":"response","id":"$id","payload":{"returnValue":true,"volumeStatus":{"volume":17,"muteStatus":true}}}""")
                Ssap.CHANNEL_UP -> reply(ws, """{"type":"error","id":"$id","error":"401 insufficient permissions","payload":{}}""")
                Ssap.LIST_INPUTS -> reply(ws, """{"type":"response","id":"$id","payload":{"returnValue":false,"errorText":"no inputs"}}""")
                "ssap://com.webos.service.networkinput/getPointerInputSocket" -> {
                    val path = "ws://${server.hostName}:${server.port}/pointer"
                    reply(ws, """{"type":"response","id":"$id","payload":{"returnValue":true,"socketPath":"$path"}}""")
                }
                else -> reply(ws, """{"type":"response","id":"$id","payload":{"returnValue":true}}""")
            }
            "subscribe" -> {
                reply(ws, """{"type":"response","id":"$id","payload":{"returnValue":true,"volume":5,"muted":false}}""")
            }
        }
    }

    @Test
    fun pairsAndReturnsClientKey() = runBlocking {
        tv = pairingTv
        enqueueTv()
        val client = client()
        val states = mutableListOf<WebOsClient.State>()
        val key = withTimeout(5_000) { client.connect(clientKey = null) }
        states += client.state.value
        assertEquals("KEY123", key)
        assertEquals(WebOsClient.State.Connected, client.state.value)

        val register = received.poll(1, TimeUnit.SECONDS)!!
        assertEquals("register", register["type"]!!.jsonPrimitive.content)
        val manifest = register["payload"]!!.jsonObject["manifest"]!!.jsonObject
        assertTrue(manifest["signatures"].toString().contains("signatureVersion"))
        client.close()
    }

    @Test
    fun sendsStoredClientKey() = runBlocking {
        tv = pairingTv
        enqueueTv()
        val client = client()
        client.connect(clientKey = "STORED")
        val register = received.poll(1, TimeUnit.SECONDS)!!
        assertEquals("STORED", register["payload"]!!.jsonObject["client-key"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun requestsParseRepliesAndErrors() = runBlocking {
        tv = pairingTv
        enqueueTv()
        val client = client()
        client.connect(clientKey = "STORED")

        assertEquals(VolumeStatus(17, true), client.getVolume())
        client.setMute(true)
        val muteRequest = generateSequence { received.poll(1, TimeUnit.SECONDS) }
            .first { it["uri"]?.jsonPrimitive?.content == Ssap.SET_MUTE }
        assertEquals("true", muteRequest["payload"]!!.jsonObject["mute"]!!.jsonPrimitive.content)

        try {
            client.channelUp()
            fail("expected error")
        } catch (e: WebOsException) {
            assertEquals("401 insufficient permissions", e.message)
        }
        try {
            client.listInputs()
            fail("expected error")
        } catch (e: WebOsException) {
            assertEquals("no inputs", e.message)
        }
        client.close()
    }

    @Test
    fun subscriptionEmitsUpdates() = runBlocking {
        tv = pairingTv
        enqueueTv()
        val client = client()
        client.connect(clientKey = "STORED")
        val first = withTimeout(3_000) { client.volumeUpdates().first() }
        assertEquals(VolumeStatus(5, false), first)
        client.close()
    }

    @Test
    fun pointerSocketSendsButtons() = runBlocking {
        tv = pairingTv
        enqueueTv()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                pointerReceived.add(text)
            }
        }))
        val client = client()
        client.connect(clientKey = "STORED")
        val pointer = client.openPointerSocket()
        assertTrue(pointer.button(TvButton.UP))
        assertTrue(pointer.move(3, -4))
        assertEquals("type:button\nname:UP\n\n", pointerReceived.poll(2, TimeUnit.SECONDS))
        assertEquals("type:move\ndx:3\ndy:-4\ndown:0\n\n", pointerReceived.poll(2, TimeUnit.SECONDS))
        pointer.close()
        client.close()
    }

    @Test
    fun rejectedPairingFails() = runBlocking {
        tv = { ws, msg ->
            val id = msg["id"]!!.jsonPrimitive.content
            reply(ws, """{"type":"error","id":"$id","error":"403 User denied access","payload":{}}""")
        }
        enqueueTv()
        val client = client()
        try {
            client.connect(clientKey = null)
            fail("expected error")
        } catch (e: WebOsException) {
            assertEquals("403 User denied access", e.message)
        }
        assertTrue(client.state.value is WebOsClient.State.Failed)
    }

    @Test
    fun unreachableTvFails() = runBlocking {
        val client = WebOsClient("127.0.0.1", endpoints = listOf("ws://127.0.0.1:1/"))
        try {
            client.connect(clientKey = null)
            fail("expected error")
        } catch (e: WebOsException) {
            assertTrue(e.message!!.startsWith("Could not reach"))
        }
    }

    @Test
    fun connectionLossFailsPendingRequests() = runBlocking {
        var serverSocket: WebSocket? = null
        tv = { ws, msg ->
            serverSocket = ws
            if (msg["type"]!!.jsonPrimitive.content == "register") pairingTv(ws, msg)
            else ws.close(1001, "going away") // never answers requests
        }
        enqueueTv()
        val client = client()
        client.connect(clientKey = "STORED")
        try {
            client.request("ssap://audio/volumeUp", timeoutMs = 5_000)
            fail("expected error")
        } catch (e: WebOsException) {
            // expected: connection closed before a reply
        }
        assertTrue(serverSocket != null)
        assertTrue(client.state.value is WebOsClient.State.Failed)
    }

    @Suppress("unused")
    private fun Response.ignore() = Unit
}
