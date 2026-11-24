package com.mrwengkb.smarttvcontrol.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.security.MessageDigest
import javax.net.ssl.SSLSocket

class AndroidTvException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the TV does not know this app's certificate yet, so it has to pair first. */
class AndroidTvNotPairedException(cause: Throwable? = null) :
    Exception("The TV needs to be paired with this phone", cause)

/** Thrown when the code typed in does not match the one on the TV. */
class AndroidTvWrongCodeException : Exception("That code doesn't match the one on the TV")

/**
 * Pairing for the Android TV Remote protocol v2 (port 6467). [start] asks the TV to show a
 * six-character code; [finish] proves we saw it. Afterwards the TV accepts our certificate on
 * the remote port.
 *
 * Messages are protobuf "OuterMessage"s: protocol_version=1, status=2, pairing_request=10,
 * pairing_request_ack=11, options=20, configuration=30, configuration_ack=31, secret=40,
 * secret_ack=41.
 */
class AndroidTvPairing(
    private val host: String,
    private val identity: AndroidTvIdentity,
    private val port: Int = PORT,
    private val clientName: String = "Smart TV Remote",
) {
    private var socket: SSLSocket? = null

    /** Connects and runs the handshake up to the point where the TV shows the code. */
    suspend fun start() = runInterruptible(Dispatchers.IO) {
        close()
        val s = identity.connect(host, port)
        socket = s
        s.soTimeout = 15_000
        try {
            exchange(s, ProtoWriter().message(PAIRING_REQUEST) { string(1, SERVICE_NAME); string(2, clientName) }, PAIRING_REQUEST_ACK)
            exchange(s, ProtoWriter().message(OPTIONS) {
                message(1) { int(1, ENCODING_HEXADECIMAL); int(2, CODE_LENGTH) }
                int(3, ROLE_INPUT)
            }, OPTIONS)
            exchange(s, ProtoWriter().message(CONFIGURATION) {
                message(1) { int(1, ENCODING_HEXADECIMAL); int(2, CODE_LENGTH) }
                int(2, ROLE_INPUT)
            }, CONFIGURATION_ACK)
        } catch (e: Exception) {
            close()
            throw e
        }
        // Give the user time to read the code off the TV.
        s.soTimeout = 0
    }

    /** Sends the code shown on the TV. Throws [AndroidTvWrongCodeException] if it doesn't match. */
    suspend fun finish(code: String) = runInterruptible(Dispatchers.IO) {
        val s = socket ?: throw AndroidTvException("Pairing was not started")
        val secret = secret(code, identity.certificate, s.session.peerCertificates[0])
            ?: throw AndroidTvWrongCodeException()
        s.soTimeout = 15_000
        // A code that fails the check byte is caught above and the user can retype it. If the TV
        // still refuses, this pairing attempt is over and a new one has to be started.
        try {
            exchange(s, ProtoWriter().message(SECRET) { bytes(1, secret) }, SECRET_ACK)
        } finally {
            close()
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun exchange(s: SSLSocket, body: ProtoWriter, expect: Int): ProtoMessage {
        val outer = ProtoWriter().int(1, PROTOCOL_VERSION).int(2, STATUS_OK)
        ProtoFraming.write(s.outputStream, outer.toByteArray() + body.toByteArray())
        val reply = ProtoMessage.parse(ProtoFraming.read(s.inputStream))
        val status = reply.int(2) ?: STATUS_OK
        if (status != STATUS_OK) throw AndroidTvException("The TV refused pairing (status $status)")
        if (!reply.has(expect)) throw AndroidTvException("Unexpected pairing reply from the TV")
        return reply
    }

    companion object {
        const val PORT = 6467
        const val SERVICE_NAME = "atvremote"
        const val PROTOCOL_VERSION = 2
        const val STATUS_OK = 200
        const val PAIRING_REQUEST = 10
        const val PAIRING_REQUEST_ACK = 11
        const val OPTIONS = 20
        const val CONFIGURATION = 30
        const val CONFIGURATION_ACK = 31
        const val SECRET = 40
        const val SECRET_ACK = 41
        const val ENCODING_HEXADECIMAL = 3
        const val ROLE_INPUT = 1
        const val CODE_LENGTH = 6

        /**
         * The pairing secret: SHA-256 over the client's RSA modulus and exponent, the TV's
         * modulus and exponent, and the last four hex digits of the code. The first two hex
         * digits of the code are a check byte that must equal the hash's first byte, so a
         * mistyped code is caught here and null is returned.
         */
        fun secret(code: String, client: java.security.cert.Certificate, server: java.security.cert.Certificate): ByteArray? {
            val clean = code.trim().uppercase()
            if (clean.length != CODE_LENGTH || clean.any { it !in "0123456789ABCDEF" }) return null
            val (cm, ce) = AndroidTvIdentity.rsaParts(client)
            val (sm, se) = AndroidTvIdentity.rsaParts(server)
            val digest = MessageDigest.getInstance("SHA-256").run {
                update(cm); update(ce); update(sm); update(se)
                update(hex(clean.substring(2)))
                digest()
            }
            val check = clean.substring(0, 2).toInt(16)
            return if ((digest[0].toInt() and 0xFF) == check) digest else null
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
