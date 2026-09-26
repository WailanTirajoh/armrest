package io.github.wailantirajoh.cursorcontroller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTest {
    @Test
    fun appendingTextOnlyInsertsTheNewPart() {
        assertEquals(TypingEdit(0, "o"), TypingDiff.between("hall", "hallo"))
    }

    @Test
    fun backspaceRemovesOneCharacter() {
        assertEquals(TypingEdit(1, ""), TypingDiff.between("halo", "hal"))
    }

    @Test
    fun autocorrectReplacementDeletesAndRetypes() {
        assertEquals(TypingEdit(3, "he "), TypingDiff.between("teh ", "the "))
    }

    @Test
    fun emojiCountsAsOneBackspace() {
        assertEquals(TypingEdit(1, ""), TypingDiff.between("hai \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "hai "))
        assertEquals(1, Graphemes.count("\uD83D\uDC4B"))
    }

    @Test
    fun graphemesKeepEmojiFlagsAndAccentsTogether() {
        assertEquals(1, Graphemes.count("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67")) // ZWJ
        assertEquals(1, Graphemes.count("\uD83D\uDC4B\uD83C\uDFFD")) // warna kulit
        assertEquals(2, Graphemes.count("\uD83C\uDDEE\uD83C\uDDE9\uD83C\uDDFA\uD83C\uDDF8")) // dua bendera
        assertEquals(1, Graphemes.count("e\u0301"))
        assertEquals(1, Graphemes.count("\r\n"))
        assertEquals(5, Graphemes.count("halo!"))
    }

    @Test
    fun combiningAccentDoesNotSplitGrapheme() {
        // "cafe" menjadi "cafe" + aksen gabung: hapus "e", lalu ketik "e" + aksen sebagai satu grapheme.
        assertEquals(TypingEdit(1, "e\u0301"), TypingDiff.between("cafe", "cafe\u0301"))
    }

    @Test
    fun bufferDetectsBackspaceOnEmptyField() {
        val buffer = TypingBuffer()
        // IME menghapus karakter tak terlihat di awal field.
        assertEquals(TypingEdit(1, ""), buffer.update(""))
        assertEquals(TypingBuffer.SENTINEL, buffer.text)
        assertEquals(TypingEdit(0, "hai"), buffer.update(TypingBuffer.SENTINEL + "hai"))
        assertEquals("hai", buffer.typed)
    }

    @Test
    fun bufferCanRevertCharacterUsedAsShortcut() {
        val buffer = TypingBuffer()
        buffer.update(TypingBuffer.SENTINEL + "ab")
        assertEquals(TypingEdit(0, "c"), buffer.update(TypingBuffer.SENTINEL + "abc"))
        buffer.revertTo("ab")
        assertEquals(TypingBuffer.SENTINEL + "ab", buffer.text)
    }

    @Test
    fun charactersMapToShortcutKeys() {
        assertEquals(KeyCode.C, KeyCode.forChar('c'))
        assertEquals(KeyCode.C, KeyCode.forChar('C'))
        assertEquals(KeyCode.Z, KeyCode.forChar('z'))
        assertEquals(KeyCode.DIGIT_9, KeyCode.forChar('9'))
        assertEquals(KeyCode.GRAVE, KeyCode.forChar('`'))
        assertNull(KeyCode.forChar('é'))
    }

    @Test
    fun textValidationRejectsControlCharacters() {
        assertTrue(InputMessage.isAllowedText("Halo 👋\n\t"))
        assertFalse(InputMessage.isAllowedText(""))
        assertFalse(InputMessage.isAllowedText("a\u0000b"))
        assertFalse(InputMessage.isAllowedText("a\rb"))
    }

    @Test
    fun longTextIsSplitAtGraphemeBoundaries() {
        val text = "a".repeat(1020) + "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" + "b".repeat(10)
        val frames = splitTextFrames(text)
        assertEquals(2, frames.size)
        assertEquals("a".repeat(1020), frames[0])
        assertTrue(frames.all { it.toByteArray(Charsets.UTF_8).size <= InputMessage.MAX_TEXT_BYTES })
        assertEquals(text, frames.joinToString(""))
    }

    @Test
    fun panelOpensWithMacTextFocusAndClosesWhenFocusLeaves() {
        val panel = KeyboardPanelState()
        panel.onTextFocus(false, auto = true) // status awal
        assertFalse(panel.isOpen)
        panel.onTextFocus(true, auto = true)
        assertTrue(panel.isOpen)
        panel.onTextFocus(false, auto = true)
        assertFalse(panel.isOpen)
    }

    @Test
    fun manuallyOpenedPanelStaysOpenWhenFocusLeaves() {
        val panel = KeyboardPanelState()
        panel.toggle()
        panel.onTextFocus(true, auto = true)
        panel.onTextFocus(false, auto = true)
        assertTrue(panel.isOpen)
    }

    @Test
    fun manuallyClosedPanelReopensOnlyOnNextFocus() {
        val panel = KeyboardPanelState()
        panel.onTextFocus(true, auto = true)
        panel.toggle() // ditutup manual saat kolom teks masih fokus
        panel.onTextFocus(true, auto = true)
        assertFalse(panel.isOpen)
        panel.onTextFocus(false, auto = true)
        panel.onTextFocus(true, auto = true)
        assertTrue(panel.isOpen)
    }

    @Test
    fun reconnectingReappliesMacFocus() {
        val panel = KeyboardPanelState()
        panel.onTextFocus(true, auto = true)
        panel.onTextFocus(null, auto = true) // koneksi putus: panel tetap, status dilupakan
        assertTrue(panel.isOpen)
        panel.onTextFocus(false, auto = true) // setelah tersambung, kolom teks sudah tidak fokus
        assertFalse(panel.isOpen)
    }

    @Test
    fun focusIsIgnoredWhenAutoModeIsOff() {
        val panel = KeyboardPanelState()
        panel.onTextFocus(true, auto = false)
        assertFalse(panel.isOpen)
    }
}
