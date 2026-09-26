package io.github.wailantirajoh.cursorcontroller.core

/**
 * Pemecah grapheme ringkas (UAX #29 untuk kasus mengetik): huruf + tanda gabung, emoji dengan ZWJ,
 * warna kulit, dan variation selector, bendera (pasangan regional indicator), serta CR LF.
 * Dipakai supaya JVM dan semua versi Android menghitung sama; BreakIterator di Java 17 memecah emoji ZWJ.
 */
object Graphemes {
    /** Indeks UTF-16 batas grapheme, termasuk 0 dan `text.length`. */
    fun boundaries(text: String): IntArray {
        val result = ArrayList<Int>()
        result += 0
        var previous = -1
        var regionalRun = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val joins = previous != -1 && (
                (previous == CR && codePoint == LF) ||
                    isExtend(codePoint) ||
                    previous == ZWJ ||
                    (isRegionalIndicator(previous) && isRegionalIndicator(codePoint) && regionalRun % 2 == 1)
                )
            if (index > 0 && !joins) result += index
            regionalRun = when {
                !isRegionalIndicator(codePoint) -> 0
                joins -> regionalRun + 1
                else -> 1
            }
            previous = codePoint
            index += Character.charCount(codePoint)
        }
        if (text.isNotEmpty()) result += text.length
        return result.toIntArray()
    }

    fun count(text: String): Int = boundaries(text).size - 1

    private fun isExtend(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            codePoint == ZWJ ||
            codePoint in 0xFE00..0xFE0F || // variation selector
            codePoint in 0x1F3FB..0x1F3FF || // warna kulit emoji
            codePoint in 0xE0020..0xE007F || // tag (bendera subdivisi)
            codePoint in 0xE0100..0xE01EF
    }

    private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF

    private const val CR = 0x0D
    private const val LF = 0x0A
    private const val ZWJ = 0x200D
}
