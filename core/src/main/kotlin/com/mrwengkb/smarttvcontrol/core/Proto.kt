package com.mrwengkb.smarttvcontrol.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * A minimal protobuf encoder/decoder, enough for the Android TV Remote protocol. It only knows
 * varint (wire type 0) and length-delimited (wire type 2) fields, which is all that protocol uses.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Long): ProtoWriter = apply {
        writeVarint(out, (field.toLong() shl 3) or 0)
        writeVarint(out, value)
    }

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())
    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        writeVarint(out, (field.toLong() shl 3) or 2)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun writeVarint(out: OutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }
}

/** A decoded message: each field number maps to its values (Long for varints, ByteArray otherwise). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {
    fun has(field: Int): Boolean = fields.containsKey(field)
    fun long(field: Int): Long? = fields[field]?.lastOrNull() as? Long
    fun int(field: Int): Int? = long(field)?.toInt()
    fun bool(field: Int): Boolean? = long(field)?.let { it != 0L }
    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    fun message(field: Int): ProtoMessage? = bytes(field)?.let(::parse)
    fun messages(field: Int): List<ProtoMessage> = fields[field].orEmpty().filterIsInstance<ByteArray>().map(::parse)

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IllegalArgumentException("Truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IllegalArgumentException("Varint too long")
                }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> fields.getOrPut(field) { mutableListOf() } += varint()
                    1 -> pos += 8
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || pos + len > data.size) throw IllegalArgumentException("Truncated field")
                        fields.getOrPut(field) { mutableListOf() } += data.copyOfRange(pos, pos + len)
                        pos += len
                    }
                    5 -> pos += 4
                    else -> throw IllegalArgumentException("Unsupported wire type in field $field")
                }
            }
            return ProtoMessage(fields)
        }
    }
}

/** Messages on the Android TV sockets are each preceded by their length as a varint. */
object ProtoFraming {
    fun write(out: OutputStream, message: ByteArray) {
        val buf = ByteArrayOutputStream()
        ProtoWriter.writeVarint(buf, message.size.toLong())
        buf.write(message)
        out.write(buf.toByteArray())
        out.flush()
    }

    /** Reads one message; throws [EOFException] when the other side closed the connection. */
    fun read(input: InputStream, maxSize: Int = 1 shl 20): ByteArray {
        var len = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed")
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw IllegalArgumentException("Bad message length")
        }
        if (len > maxSize) throw IllegalArgumentException("Message too large: $len")
        val data = ByteArray(len.toInt())
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n < 0) throw EOFException("Connection closed")
            read += n
        }
        return data
    }
}
