package com.vlesscardvpn.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Allowlisted decoder of AOSP tombstone.proto. No memory, registers, log buffers or FDs are read. */
internal object NativeTombstoneReader {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_FIELDS = 4096

    fun read(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = stream.read(buffer)
            if (size < 0) break
            if (size == 0) continue
            require(output.size() + size <= MAX_BYTES) { "Native trace exceeds size limit" }
            output.write(buffer, 0, size)
        }
        return decode(output.toByteArray())
    }

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "Native trace exceeds size limit" }
        val root = fields(bytes)
        val tid = root.number(6)
        val signal = root.firstOrNull { it.tag == 10 }?.data?.let(::fields).orEmpty()
        val abort = root.text(14).take(2048)
        val thread = root.filter { it.tag == 16 }.firstNotNullOfOrNull { entry ->
            val map = fields(entry.data ?: return@firstNotNullOfOrNull null)
            if (map.number(1) == tid && tid != 0L) map.firstOrNull { it.tag == 2 }?.data?.let(::fields) else null
        }
        return buildString {
            appendLine("Native tombstone (Android system trace)")
            appendLine("Signal: ${signal.text(2)} (${signal.number(1)}); crashing tid=$tid")
            if (abort.isNotBlank()) appendLine("Abort message: $abort")
            if (thread == null) appendLine("Crashing-thread backtrace unavailable")
            else {
                val frames = thread.filter { it.tag == 4 }.take(24)
                if (frames.isEmpty()) appendLine("Crashing-thread backtrace unavailable")
                frames.forEachIndexed { index, frame ->
                    val values = fields(frame.data ?: return@forEachIndexed)
                    val library = values.text(6).substringAfterLast('/').take(160)
                    val function = values.text(4).take(300)
                    appendLine("#${index.toString().padStart(2, '0')} pc ${values.number(1).toString(16)} $library $function+${values.number(5)}")
                }
            }
        }.take(12000)
    }

    private data class Field(val tag: Int, val value: Long = 0, val data: ByteArray? = null)
    private fun List<Field>.number(tag: Int) = firstOrNull { it.tag == tag }?.value ?: 0L
    private fun List<Field>.text(tag: Int) = firstOrNull { it.tag == tag }?.data?.let {
        String(it, 0, minOf(it.size, 4096), Charsets.UTF_8)
    }.orEmpty()

    private fun fields(bytes: ByteArray): List<Field> {
        var offset = 0
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(offset < bytes.size) { "Truncated native trace" }
                val b = bytes[offset++].toInt() and 255
                if (shift == 63) require(b <= 1) { "Invalid native trace varint" }
                value = value or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return value
            }
            error("Invalid native trace varint")
        }
        fun skip(count: Int) {
            require(count >= 0 && count <= bytes.size - offset) { "Truncated native trace field" }
            offset += count
        }
        val result = mutableListOf<Field>()
        while (offset < bytes.size) {
            require(result.size < MAX_FIELDS) { "Too many native trace fields" }
            val key = varint()
            val tag = key ushr 3
            require(tag in 1..536870911) { "Invalid native trace field" }
            when ((key and 7).toInt()) {
                0 -> result.add(Field(tag.toInt(), value = varint()))
                1 -> { skip(8); result.add(Field(tag.toInt())) }
                2 -> {
                    val length = varint()
                    require(length in 0..(bytes.size - offset).toLong()) { "Truncated native trace message" }
                    val end = offset + length.toInt()
                    result.add(Field(tag.toInt(), data = bytes.copyOfRange(offset, end)))
                    offset = end
                }
                5 -> { skip(4); result.add(Field(tag.toInt())) }
                else -> error("Unsupported native trace wire type")
            }
        }
        return result
    }
}
