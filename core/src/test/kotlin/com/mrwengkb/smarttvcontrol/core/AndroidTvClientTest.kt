package com.mrwengkb.smarttvcontrol.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * A fake Android TV: a TLS server that requires a client certificate, like the real one. For
 * the remote port it only accepts certificates that completed pairing.
 */
private class FakeAndroidTv {
    val identity = AndroidTvIdentity.generate("fake-tv")
    val paired = mutableSetOf<String>()
    val received = LinkedBlockingQueue<ProtoMessage>()
    var shownCode: String? = null
    @Volatile var remoteSocket: SSLSocket? = null

    private fun fingerprint(c: java.security.cert.Certificate) = c.encoded.contentHashCode().toString()

    private val trustPaired = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
            if (fingerprint(chain[0]) !in paired) throw java.security.cert.CertificateException("unknown client")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun server(trust: X509TrustManager): SSLServerSocket {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("tv", identity.privateKey, "x".toCharArray(), arrayOf(identity.certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "x".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(trust), SecureRandom()) }
        return (ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply { needClientAuth = true }
    }

    val pairingServer = server(AndroidTvIdentity.TrustAll)
    val remoteServer = server(trustPaired)

    init {
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { pairingServer.accept() as SSLSocket }.getOrNull() ?: break
                runCatching { pairingSession(s) }
                runCatching { s.close() }
            }
        }
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { remoteServer.accept() as SSLSocket }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { remoteSession(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun reply(s: SSLSocket, status: Int = 200, body: ProtoWriter.() -> Unit) {
        val msg = ProtoWriter().int(1, 2).int(2, status).apply(body)
        ProtoFraming.write(s.outputStream, msg.toByteArray())
    }

    private fun pairingSession(s: SSLSocket) {
        s.startHandshake()
        val client = s.session.peerCertificates[0]
        while (true) {
            val m = ProtoMessage.parse(ProtoFraming.read(s.inputStream))
            when {
                m.has(10) -> {
                    assertEquals("atvremote", m.message(10)!!.string(1))
                    reply(s) { message(11) { string(1, "Fake TV") } }
                }
                m.has(20) -> reply(s) { message(20) { message(1) { int(1, 3); int(2, 6) }; int(3, 1) } }
                m.has(30) -> {
                    // Find a code whose check byte matches, as the real TV does by construction.
                    shownCode = (0..0xFFFFFF).asSequence().map { "%06X".format(it) }
                        .first { AndroidTvPairing.secret(it, client, identity.certificate) != null }
                    reply(s) { message(31) {} }
                }
                m.has(40) -> {
                    val expected = AndroidTvPairing.secret(shownCode!!, client, identity.certificate)
                    if (expected != null && m.message(40)!!.bytes(1)!!.contentEquals(expected)) {
                        paired += fingerprint(client)
                        reply(s) { message(41) { bytes(1, expected) } }
                    } else {
                        reply(s, status = 402) {}
                    }
                    return
                }
            }
        }
    }

    private fun send(s: SSLSocket, body: ProtoWriter.() -> Unit) =
        synchronized(this) { ProtoFraming.write(s.outputStream, ProtoWriter().apply(body).toByteArray()) }

    private fun remoteSession(s: SSLSocket) {
        s.startHandshake()
        remoteSocket = s
        send(s) { message(1) { int(1, 622); message(2) { string(1, "TV"); string(2, "Fake") } } }
        while (true) {
            val m = ProtoMessage.parse(ProtoFraming.read(s.inputStream))
            received += m
            when {
                m.has(1) -> send(s) { message(2) { int(1, 622) } }
                m.has(2) -> {
                    send(s) { message(40) { bool(1, true) } }
                    send(s) { message(50) { int(6, 100); int(7, 12); bool(8, false) } }
                    send(s) { message(8) { int(1, 42) } }
                }
            }
        }
    }

    fun push(body: ProtoWriter.() -> Unit) = send(remoteSocket!!, body)

    fun nextReceived(field: Int): ProtoMessage {
        while (true) {
            val m = received.poll(5, TimeUnit.SECONDS) ?: fail("No message $field from the client").let { error("") }
            if (m.has(field)) return m
        }
    }

    fun close() {
        runCatching { pairingServer.close() }
        runCatching { remoteServer.close() }
    }
}

class AndroidTvClientTest {
    private val tv = FakeAndroidTv()
    private val identity = AndroidTvIdentity.generate()

    @After fun tearDown() = tv.close()

    private fun client() = AndroidTvClient("127.0.0.1", identity, port = tv.remoteServer.localPort)

    @Test fun identitySurvivesEncoding() {
        val (key, cert) = identity.encode()
        val back = AndroidTvIdentity.decode(key, cert)
        assertArrayEquals(identity.certificate.encoded, back.certificate.encoded)
        assertArrayEquals(identity.privateKey.encoded, back.privateKey.encoded)
        val (modulus, exponent) = AndroidTvIdentity.rsaParts(identity.certificate)
        assertEquals(256, modulus.size)
        assertArrayEquals(byteArrayOf(1, 0, 1), exponent)
    }

    @Test fun unpairedClientIsAskedToPair() = runBlocking {
        try {
            client().connect()
            fail("Expected pairing to be required")
        } catch (_: AndroidTvNotPairedException) {
        }
    }

    @Test fun wrongCodeIsRejectedBeforeSending() = runBlocking {
        val pairing = AndroidTvPairing("127.0.0.1", identity, port = tv.pairingServer.localPort)
        pairing.start()
        val code = tv.shownCode!!
        // Change the check byte so it can't match.
        val wrong = "%02X".format((code.substring(0, 2).toInt(16) + 1) % 256) + code.substring(2)
        try {
            pairing.finish(wrong)
            fail("Expected a wrong code")
        } catch (_: AndroidTvWrongCodeException) {
        }
        assertNull(AndroidTvPairing.secret("XYZ", identity.certificate, tv.identity.certificate))
    }

    @Test fun pairsThenControlsTheTv() = runBlocking {
        val pairing = AndroidTvPairing("127.0.0.1", identity, port = tv.pairingServer.localPort)
        pairing.start()
        pairing.finish(tv.shownCode!!.lowercase())
        assertEquals(1, tv.paired.size)

        val c = client()
        c.connect()
        assertEquals(AndroidTvClient.State.Connected, c.state.value)

        val configure = tv.nextReceived(1).message(1)!!
        assertEquals(AndroidTvClient.FEATURES and 622, configure.int(1))
        assertEquals("atvremote", configure.message(2)!!.string(5))
        assertEquals(AndroidTvClient.FEATURES and 622, tv.nextReceived(2).message(2)!!.int(1))
        assertEquals(42, tv.nextReceived(9).message(9)!!.int(1))

        withTimeout(5_000) { c.volume.first { it != null } }
        assertEquals(AndroidTvVolume(12, 100, false), c.volume.value)
        assertEquals(true, c.isOn.value)

        c.key(AndroidKey.DPAD_UP)
        val key = tv.nextReceived(10).message(10)!!
        assertEquals(AndroidKey.DPAD_UP, key.int(1))
        assertEquals(AndroidTvClient.DIRECTION_SHORT, key.int(2))

        c.launch("market://launch?id=com.netflix.ninja")
        assertEquals("market://launch?id=com.netflix.ninja", tv.nextReceived(90).message(90)!!.string(1))

        // The TV announces a text box; text then goes in as a batch edit with its counters.
        // Counters come from the key inject; the TV's own batch edit must not override them.
        tv.push { message(20) { message(1) { int(1, 5); string(12, "org.jellyfin.androidtv") }; message(2) { int(1, 141) } } }
        tv.push { message(21) { int(1, 1); int(2, 1) } }
        tv.push { message(22) { message(2) { int(1, 141); string(2, "") } } }
        withTimeout(5_000) { c.imeLog.first { it.lastOrNull()?.startsWith("text box shown") == true } }
        assertTrue(c.hasTextField)
        assertEquals("org.jellyfin.androidtv", c.currentApp.value)
        c.insertText("hi")
        val edit = tv.nextReceived(21).message(21)!!
        assertEquals(5, edit.int(1))
        assertEquals(141, edit.int(2))

        // An app reporting zero counters clears them; a later batch edit then fills them in.
        tv.push { message(20) { message(1) { string(12, "com.google.android.youtube.tv") } } }
        tv.push { message(21) { int(1, 9); int(2, 4) } }
        tv.push { message(22) { message(2) { string(2, "") } } }
        withTimeout(5_000) { c.imeLog.first { it.lastOrNull()?.startsWith("text box shown") == true } }
        c.insertText("a")
        val edit2 = tv.nextReceived(21).message(21)!!
        assertEquals(9, edit2.int(1))
        assertEquals(0, edit2.int(2))
        val info = edit.messages(3).single().message(2)!!
        assertEquals("hi", info.string(3))
        assertEquals(2, info.int(1))
        assertEquals(2, info.int(2))

        c.close()
        assertEquals(AndroidTvClient.State.Disconnected, c.state.value)
    }

    @Test fun keyNamesMapToAndroidKeys() {
        assertEquals(AndroidKey.DPAD_CENTER, AndroidKey.fromButton(TvButton.ENTER))
        assertEquals(AndroidKey.SETTINGS, AndroidKey.fromButton(TvButton.MENU))
        assertEquals(AndroidKey.NUM_0 + 7, AndroidKey.fromButton("7"))
        assertNull(AndroidKey.fromButton("NOPE"))
        assertEquals(AndroidKey.A + 25, AndroidKey.forChar('Z'))
        assertNotNull(AndroidKey.forChar(' '))
    }

    @Test fun probeTellsPlatformsApart() = runBlocking {
        ServerSocket(0).use { open ->
            assertEquals(
                TvPlatform.ANDROID_TV,
                TvProbe.detect("127.0.0.1", webOsPorts = listOf(1), androidTvPorts = listOf(open.localPort)),
            )
            assertEquals(
                TvPlatform.WEBOS,
                TvProbe.detect("127.0.0.1", webOsPorts = listOf(open.localPort), androidTvPorts = listOf(1)),
            )
        }
        assertNull(TvProbe.detect("127.0.0.1", webOsPorts = listOf(1), androidTvPorts = listOf(2)))
    }

    @Test fun protoRoundTrip() {
        val bytes = ProtoWriter().int(1, 300).string(2, "héllo").message(3) { int(1, -1) }.toByteArray()
        val m = ProtoMessage.parse(bytes)
        assertEquals(300, m.int(1))
        assertEquals("héllo", m.string(2))
        assertEquals(-1, m.message(3)!!.int(1))
        val framed = java.io.ByteArrayOutputStream().also { ProtoFraming.write(it, bytes) }.toByteArray()
        assertArrayEquals(bytes, ProtoFraming.read(framed.inputStream()))
    }
}
