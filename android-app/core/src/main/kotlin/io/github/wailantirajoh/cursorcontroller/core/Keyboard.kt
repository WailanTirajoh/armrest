package io.github.wailantirajoh.cursorcontroller.core

/** Kode tombol protokol (lihat PROTOCOL.md). Huruf dan tanda baca memakai posisi ANSI di Mac. */
enum class KeyCode(val code: Int) {
    RETURN(0x01), BACKSPACE(0x02), TAB(0x03), ESCAPE(0x04), SPACE(0x05), FORWARD_DELETE(0x06),
    LEFT(0x07), RIGHT(0x08), UP(0x09), DOWN(0x0A),
    HOME(0x0B), END(0x0C), PAGE_UP(0x0D), PAGE_DOWN(0x0E),
    F1(0x10), F2(0x11), F3(0x12), F4(0x13), F5(0x14), F6(0x15),
    F7(0x16), F8(0x17), F9(0x18), F10(0x19), F11(0x1A), F12(0x1B),
    A(0x20), B(0x21), C(0x22), D(0x23), E(0x24), F(0x25), G(0x26), H(0x27), I(0x28), J(0x29), K(0x2A), L(0x2B), M(0x2C),
    N(0x2D), O(0x2E), P(0x2F), Q(0x30), R(0x31), S(0x32), T(0x33), U(0x34), V(0x35), W(0x36), X(0x37), Y(0x38), Z(0x39),
    DIGIT_0(0x40), DIGIT_1(0x41), DIGIT_2(0x42), DIGIT_3(0x43), DIGIT_4(0x44),
    DIGIT_5(0x45), DIGIT_6(0x46), DIGIT_7(0x47), DIGIT_8(0x48), DIGIT_9(0x49),
    MINUS(0x50), EQUAL(0x51), LEFT_BRACKET(0x52), RIGHT_BRACKET(0x53), BACKSLASH(0x54),
    SEMICOLON(0x55), QUOTE(0x56), COMMA(0x57), PERIOD(0x58), SLASH(0x59), GRAVE(0x5A);

    companion object {
        fun fromCode(code: Int): KeyCode? = entries.firstOrNull { it.code == code }

        /** Tombol untuk shortcut (mis. ⌘ + "c"). null kalau karakter tidak punya tombol ANSI sendiri. */
        fun forChar(char: Char): KeyCode? = when (val c = char.lowercaseChar()) {
            in 'a'..'z' -> fromCode(A.code + (c - 'a'))
            in '0'..'9' -> fromCode(DIGIT_0.code + (c - '0'))
            ' ' -> SPACE
            '-' -> MINUS
            '=' -> EQUAL
            '[' -> LEFT_BRACKET
            ']' -> RIGHT_BRACKET
            '\\' -> BACKSLASH
            ';' -> SEMICOLON
            '\'' -> QUOTE
            ',' -> COMMA
            '.' -> PERIOD
            '/' -> SLASH
            '`' -> GRAVE
            else -> null
        }
    }
}

object KeyModifiers {
    const val SHIFT = 0x01
    const val CONTROL = 0x02
    const val OPTION = 0x04
    const val COMMAND = 0x08
    const val ALL = SHIFT or CONTROL or OPTION or COMMAND
}

/** Perubahan yang perlu diketik ulang di Mac: hapus `backspaces` grapheme, lalu ketik `insert`. */
data class TypingEdit(val backspaces: Int, val insert: String) {
    val isEmpty: Boolean get() = backspaces == 0 && insert.isEmpty()
}

object TypingDiff {
    /**
     * Membandingkan isi field sebelum dan sesudah diubah IME. Semua setelah awalan yang sama dianggap
     * dihapus lalu diketik ulang, jadi ketik biasa, Backspace, dan koreksi otomatis ("teh " → "the ")
     * semuanya tertangani, di posisi mana pun perubahannya.
     */
    fun between(old: String, new: String): TypingEdit {
        var prefix = 0
        val max = minOf(old.length, new.length)
        while (prefix < max && old[prefix] == new[prefix]) prefix++
        // Mundur ke batas grapheme yang sama di kedua teks, supaya emoji atau huruf beraksen tidak terbelah.
        val oldBounds = Graphemes.boundaries(old).toHashSet()
        val newBounds = Graphemes.boundaries(new).toHashSet()
        while (prefix > 0 && (prefix !in oldBounds || prefix !in newBounds)) prefix--
        return TypingEdit(Graphemes.count(old.substring(prefix)), new.substring(prefix))
    }
}

/**
 * Isi field ketik di HP. Field selalu diawali karakter tak terlihat, supaya Backspace saat field
 * tampak kosong tetap terdeteksi (IME menghapus karakter itu, lalu kita kembalikan).
 */
class TypingBuffer {
    var text: String = SENTINEL
        private set

    val typed: String get() = text.removePrefix(SENTINEL)

    fun update(fieldText: String): TypingEdit {
        val sentinelKept = fieldText.startsWith(SENTINEL)
        val newTyped = if (sentinelKept) fieldText.removePrefix(SENTINEL) else fieldText.replace(SENTINEL, "")
        val edit = TypingDiff.between(typed, newTyped)
        text = SENTINEL + newTyped
        return if (sentinelKept) edit else edit.copy(backspaces = edit.backspaces + 1)
    }

    /** Batalkan perubahan terakhir, mis. karakter yang dipakai sebagai shortcut, bukan teks. */
    fun revertTo(typed: String) {
        text = SENTINEL + typed
    }

    fun reset() {
        text = SENTINEL
    }

    companion object {
        const val SENTINEL = "\u200B"
    }
}

/** Memecah teks panjang (mis. hasil tempel) menjadi frame ≤ `maxBytes` UTF-8 di batas grapheme. */
fun splitTextFrames(text: String, maxBytes: Int = InputMessage.MAX_TEXT_BYTES): List<String> {
    if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text)
    val bounds = Graphemes.boundaries(text)
    val frames = ArrayList<String>()
    val current = StringBuilder()
    var bytes = 0
    for (i in 0 until bounds.size - 1) {
        val grapheme = text.substring(bounds[i], bounds[i + 1])
        val size = grapheme.toByteArray(Charsets.UTF_8).size
        if (bytes + size > maxBytes && current.isNotEmpty()) {
            frames += current.toString()
            current.clear()
            bytes = 0
        }
        current.append(grapheme)
        bytes += size
    }
    if (current.isNotEmpty()) frames += current.toString()
    return frames
}

/**
 * Kapan panel keyboard terbuka. Dengan mode otomatis, panel terbuka saat Mac melaporkan kolom teks aktif dan
 * tertutup lagi saat fokus pindah, tapi hanya kalau tadi terbukanya otomatis. Panel yang dibuka manual tidak
 * ditutup otomatis, dan panel yang ditutup manual baru terbuka lagi saat kolom teks berikutnya fokus.
 */
class KeyboardPanelState {
    var isOpen = false
        private set
    private var openedByFocus = false
    private var lastFocus: Boolean? = null

    fun toggle() {
        isOpen = !isOpen
        openedByFocus = false
    }

    /** Status fokus dari Mac. null = tidak diketahui, mis. koneksi putus atau mode otomatis dimatikan. */
    fun onTextFocus(focused: Boolean?, auto: Boolean) {
        if (focused == lastFocus) return
        lastFocus = focused
        if (!auto || focused == null) return
        if (focused && !isOpen) {
            isOpen = true
            openedByFocus = true
        } else if (!focused && openedByFocus) {
            isOpen = false
            openedByFocus = false
        }
    }

    fun reset() {
        isOpen = false
        openedByFocus = false
        lastFocus = null
    }
}
