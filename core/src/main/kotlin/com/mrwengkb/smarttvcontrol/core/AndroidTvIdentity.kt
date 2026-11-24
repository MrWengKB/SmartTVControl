package com.mrwengkb.smarttvcontrol.core

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The client certificate this app shows to Android TVs. The TV remembers the certificate when
 * pairing succeeds, so the same identity must be kept and reused for every later connection.
 * It is a self-signed RSA certificate, built here without extra libraries.
 */
class AndroidTvIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    /** Base64 of the PKCS#8 private key and the DER certificate, for storing. */
    fun encode(): Pair<String, String> =
        Base64.getEncoder().encodeToString(privateKey.encoded) to
            Base64.getEncoder().encodeToString(certificate.encoded)

    /** A TLS context that presents this identity and accepts the TV's self-signed certificate. */
    fun sslContext(): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, PASSWORD, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD) }
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(TrustAll), SecureRandom())
        }
    }

    /** Opens a TLS connection to the TV with a connect timeout and finishes the handshake. */
    fun connect(host: String, port: Int, timeoutMs: Int = 5_000): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val ssl = sslContext().socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.startHandshake()
            return ssl
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private val PASSWORD = "atvremote".toCharArray()

        fun decode(privateKeyBase64: String, certificateBase64: String): AndroidTvIdentity {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.getDecoder().decode(certificateBase64).inputStream()) as X509Certificate
            return AndroidTvIdentity(key, cert)
        }

        /** Makes a new RSA 2048 key and a self-signed certificate valid for about 20 years. */
        fun generate(commonName: String = "atvremote", now: Date = Date()): AndroidTvIdentity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val sha256WithRsa = Der.seq(Der.oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)), Der.NULL)
            val name = Der.seq(Der.set(Der.seq(Der.oid(byteArrayOf(0x55, 0x04, 0x03)), Der.utf8(commonName))))
            val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
            val notBefore = Date(now.time - 24L * 3600 * 1000)
            val notAfter = Date(now.time + 20L * 365 * 24 * 3600 * 1000)
            val tbs = Der.seq(
                Der.tagged(0, Der.integer(BigInteger.valueOf(2))), // X.509 v3
                Der.integer(serial),
                sha256WithRsa,
                name,
                Der.seq(Der.utcTime(notBefore), Der.utcTime(notAfter)),
                name,
                pair.public.encoded, // already a DER SubjectPublicKeyInfo
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update(tbs)
                sign()
            }
            val der = Der.seq(tbs, sha256WithRsa, Der.bitString(signature))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
            return AndroidTvIdentity(pair.private, cert)
        }

        /** The unsigned big-endian bytes of the certificate's RSA modulus and exponent. */
        fun rsaParts(cert: java.security.cert.Certificate): Pair<ByteArray, ByteArray> {
            val key = cert.publicKey as? RSAPublicKey ?: throw IllegalArgumentException("Not an RSA certificate")
            return unsigned(key.modulus) to unsigned(key.publicExponent)
        }

        private fun unsigned(n: BigInteger): ByteArray {
            val b = n.toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }
    }
}

/** Just enough ASN.1 DER to build a certificate. */
internal object Der {
    val NULL = byteArrayOf(0x05, 0x00)

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xFF) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    fun tagged(n: Int, content: ByteArray) = tlv(0xA0 + n, content)
    fun integer(n: BigInteger) = tlv(0x02, n.toByteArray())
    fun oid(encoded: ByteArray) = tlv(0x06, encoded)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)

    fun utcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
