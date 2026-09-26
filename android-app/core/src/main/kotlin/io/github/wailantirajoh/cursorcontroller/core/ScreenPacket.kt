package io.github.wailantirajoh.cursorcontroller.core

/** Frame biner Mac → HP untuk video layar (protocol/PROTOCOL.md, bagian "Layar Mac"). */
sealed interface ScreenPacket {
    /** Ukuran video dan parameter decoder H.264 (SPS dan PPS, Annex B). Datang sebelum setiap keyframe. */
    class Config(val width: Int, val height: Int, val parameterSets: ByteArray) : ScreenPacket {
        override fun equals(other: Any?) =
            other is Config && width == other.width && height == other.height && parameterSets.contentEquals(other.parameterSets)

        override fun hashCode() = 31 * (31 * width + height) + parameterSets.contentHashCode()
    }

    /** Satu frame video (access unit Annex B). `seq` naik satu per frame. */
    class Frame(val seq: Long, val keyframe: Boolean, val data: ByteArray) : ScreenPacket

    companion object {
        const val TYPE_CONFIG = 0x81
        const val TYPE_FRAME = 0x82
        private const val CODEC_H264 = 1
        private const val HEADER = 6

        /** null untuk tipe atau codec tidak dikenal, ukuran 0, flag tidak dikenal, atau tanpa isi. */
        fun parse(bytes: ByteArray): ScreenPacket? {
            if (bytes.size <= HEADER) return null
            fun u8(i: Int) = bytes[i].toInt() and 0xFF
            return when (u8(0)) {
                TYPE_CONFIG -> {
                    val width = u8(2) or (u8(3) shl 8)
                    val height = u8(4) or (u8(5) shl 8)
                    if (u8(1) != CODEC_H264 || width == 0 || height == 0) null
                    else Config(width, height, bytes.copyOfRange(HEADER, bytes.size))
                }
                TYPE_FRAME -> {
                    if (u8(5) > 1) return null
                    val seq = u8(1).toLong() or (u8(2).toLong() shl 8) or (u8(3).toLong() shl 16) or (u8(4).toLong() shl 24)
                    Frame(seq, u8(5) == 1, bytes.copyOfRange(HEADER, bytes.size))
                }
                else -> null
            }
        }
    }
}

/** NAL unit H.264 dalam format Annex B (diawali start code 00 00 01 atau 00 00 00 01). */
object AnnexB {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun nalType(unit: ByteArray): Int = if (unit.isEmpty()) -1 else unit[0].toInt() and 0x1F

    /** NAL unit tanpa start code. */
    fun split(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<IntArray>() // [awal start code, awal NAL]
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += intArrayOf(if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i, i + 3)
                i += 3
            } else {
                i++
            }
        }
        return starts.indices.map { k ->
            val end = if (k + 1 < starts.size) starts[k + 1][0] else data.size
            data.copyOfRange(starts[k][1], end)
        }
    }
}
