package io.github.wailantirajoh.cursorcontroller.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class MouseButton(val code: Int) {
    LEFT(0),
    RIGHT(1);

    companion object {
        fun fromCode(code: Int): MouseButton? = entries.firstOrNull { it.code == code }
    }
}

/** Event input biner ke agent (protocol/PROTOCOL.md). dx/dy dalam satuan 0,1 dp. */
sealed interface InputMessage {
    data class Move(val dx: Short, val dy: Short) : InputMessage
    data class Button(val button: MouseButton, val down: Boolean) : InputMessage
    data class Click(val button: MouseButton, val count: Int) : InputMessage
    data class Scroll(val dx: Short, val dy: Short) : InputMessage
    /** Maksimal MAX_TEXT_BYTES byte UTF-8; pakai splitTextFrames untuk teks panjang. */
    data class Text(val text: String) : InputMessage
    data class Key(val key: KeyCode, val modifiers: Int = 0) : InputMessage

    fun encode(): ByteArray = when (this) {
        is Move -> byteArrayOf(0x01) + le(dx) + le(dy)
        is Button -> byteArrayOf(0x02, button.code.toByte(), if (down) 1 else 0)
        is Click -> byteArrayOf(0x03, button.code.toByte(), count.toByte())
        is Scroll -> byteArrayOf(0x04) + le(dx) + le(dy)
        is Text -> byteArrayOf(0x05) + text.toByteArray(Charsets.UTF_8)
        is Key -> byteArrayOf(0x06, key.code.toByte(), modifiers.toByte())
    }

    companion object {
        /** null untuk frame dengan panjang salah, tipe tidak dikenal, atau nilai di luar rentang. */
        fun decode(bytes: ByteArray): InputMessage? {
            if (bytes.isEmpty()) return null
            fun u8(i: Int) = bytes[i].toInt() and 0xFF
            fun i16(i: Int) = ((u8(i + 1) shl 8) or u8(i)).toShort()
            return when {
                bytes[0] == 0x01.toByte() && bytes.size == 5 -> Move(i16(1), i16(3))
                bytes[0] == 0x02.toByte() && bytes.size == 3 && u8(2) <= 1 ->
                    MouseButton.fromCode(u8(1))?.let { Button(it, u8(2) == 1) }
                bytes[0] == 0x03.toByte() && bytes.size == 3 && (u8(2) == 1 || u8(2) == 2) ->
                    MouseButton.fromCode(u8(1))?.let { Click(it, u8(2)) }
                bytes[0] == 0x04.toByte() && bytes.size == 5 -> Scroll(i16(1), i16(3))
                bytes[0] == 0x05.toByte() && bytes.size in 2..MAX_TEXT_BYTES + 1 ->
                    utf8(bytes, 1)?.takeIf(::isAllowedText)?.let { Text(it) }
                bytes[0] == 0x06.toByte() && bytes.size == 3 && (u8(2) and KeyModifiers.ALL.inv()) == 0 ->
                    KeyCode.fromCode(u8(1))?.let { Key(it, u8(2)) }
                else -> null
            }
        }

        const val MAX_TEXT_BYTES = 1024

        /** Teks valid: tidak kosong, tanpa karakter kontrol selain \n dan \t. */
        fun isAllowedText(text: String): Boolean =
            text.isNotEmpty() && text.codePoints().allMatch { it == '\n'.code || it == '\t'.code || (it >= 0x20 && it != 0x7F) }

        private fun utf8(bytes: ByteArray, offset: Int): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        private fun le(value: Short): ByteArray {
            val v = value.toInt()
            return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        }
    }
}

/**
 * Mengumpulkan delta dp per frame lalu mengubahnya ke satuan protokol (0,1 dp).
 * Pecahan dan bagian yang melewati batas i16 disimpan untuk paket berikutnya.
 */
class DeltaAccumulator {
    private var x = 0.0
    private var y = 0.0

    fun add(dxDp: Float, dyDp: Float) {
        x += dxDp * 10.0
        y += dyDp * 10.0
    }

    /** Pasangan (dx, dy) siap kirim, atau null kalau belum ada gerakan utuh. */
    fun drain(): Pair<Short, Short>? {
        val dx = clamp(x.toLong())
        val dy = clamp(y.toLong())
        if (dx == 0L && dy == 0L) return null
        x -= dx
        y -= dy
        return dx.toInt().toShort() to dy.toInt().toShort()
    }

    fun reset() {
        x = 0.0
        y = 0.0
    }

    /** Masih ada gerakan minimal satu satuan (0,1 dp) yang belum dikirim. */
    val hasPending: Boolean get() = kotlin.math.abs(x) >= 1.0 || kotlin.math.abs(y) >= 1.0

    private fun clamp(v: Long) = v.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
}
