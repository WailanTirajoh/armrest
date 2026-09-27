package io.github.wailantirajoh.armrest.data

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.wailantirajoh.armrest.core.AnnexB
import io.github.wailantirajoh.armrest.core.ScreenPacket
import java.nio.ByteBuffer

/**
 * Decoder H.264 (MediaCodec) yang menggambar video layar Mac ke Surface preview.
 * Semua kerja MediaCodec berjalan di thread sendiri; method publik boleh dipanggil dari thread mana pun.
 */
class ScreenDecoder(private val listener: Listener) {
    interface Listener {
        /** Ukuran video dari paket config. Dipanggil di thread decoder. */
        fun onVideoSize(width: Int, height: Int)

        /** Frame pertama sejak decoder dibuat sudah tampil. Dipanggil di thread decoder. */
        fun onFirstFrame()

        /** Decoder butuh keyframe baru dari Mac, mis. setelah Surface diganti atau error. Dipanggil di thread decoder. */
        fun onKeyframeNeeded()
    }

    private val thread = HandlerThread("armrest-screen").apply { start() }
    private val handler = Handler(thread.looper)

    // Hanya diakses di thread decoder.
    private var surface: Surface? = null
    private var config: ScreenPacket.Config? = null
    private var codec: MediaCodec? = null
    private var waitingForKeyframe = true
    private var rendered = false
    private val pending = ArrayDeque<ScreenPacket.Frame>()
    private val freeInputs = ArrayDeque<Int>()

    fun setSurface(surface: Surface?) {
        handler.post {
            this.surface = surface
            restart()
            // Aliran sudah berjalan: decoder baru hanya bisa mulai dari keyframe.
            if (surface != null && config != null) listener.onKeyframeNeeded()
        }
    }

    fun submit(packet: ScreenPacket) {
        handler.post {
            when (packet) {
                is ScreenPacket.Config -> onConfig(packet)
                is ScreenPacket.Frame -> onFrame(packet)
            }
        }
    }

    /** Aliran berhenti (preview mati atau koneksi putus): aliran berikutnya mulai dari config baru. */
    fun reset() {
        handler.post {
            config = null
            release()
        }
    }

    fun close() {
        handler.post { release() }
        thread.quitSafely()
    }

    private fun onConfig(packet: ScreenPacket.Config) {
        if (packet == config && codec != null) return
        config = packet
        listener.onVideoSize(packet.width, packet.height)
        restart()
    }

    private fun onFrame(frame: ScreenPacket.Frame) {
        val codec = codec ?: return
        if (waitingForKeyframe) {
            if (!frame.keyframe) return
            waitingForKeyframe = false
        }
        pending.addLast(frame)
        if (pending.size > MAX_PENDING) {
            // Decoder tertinggal jauh: buang antrean dan mulai lagi dari keyframe supaya jeda tidak menumpuk.
            pending.clear()
            waitingForKeyframe = true
            listener.onKeyframeNeeded()
            return
        }
        feed(codec)
    }

    private fun feed(codec: MediaCodec) {
        try {
            while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
                val index = freeInputs.removeFirst()
                val frame = pending.removeFirst()
                val buffer = codec.getInputBuffer(index) ?: continue
                if (frame.data.size > buffer.capacity()) {
                    freeInputs.addFirst(index)
                    pending.clear()
                    waitingForKeyframe = true
                    listener.onKeyframeNeeded()
                    return
                }
                buffer.clear()
                buffer.put(frame.data)
                val flags = if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(index, 0, frame.data.size, frame.seq * FRAME_US, flags)
            }
        } catch (_: IllegalStateException) {
            // Codec sedang error; onError membuatnya ulang.
        }
    }

    private fun restart() {
        release()
        val config = config ?: return
        val surface = surface ?: return
        val units = AnnexB.split(config.parameterSets)
        val sps = units.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_SPS } ?: return
        val pps = units.firstOrNull { AnnexB.nalType(it) == AnnexB.NAL_PPS } ?: return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(AnnexB.START_CODE + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(AnnexB.START_CODE + pps))
            // Keyframe layar yang penuh teks bisa besar.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, config.width * config.height)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (_: Exception) {
            return
        }
        try {
            codec.setCallback(Callbacks(), handler)
            codec.configure(format, surface, null, 0)
            codec.start()
            this.codec = codec
        } catch (_: Exception) {
            codec.release()
        }
    }

    private fun release() {
        codec?.let {
            runCatching { it.stop() }
            it.release()
        }
        codec = null
        pending.clear()
        freeInputs.clear()
        waitingForKeyframe = true
        rendered = false
    }

    private inner class Callbacks : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== this@ScreenDecoder.codec) return
            freeInputs.addLast(index)
            feed(codec)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== this@ScreenDecoder.codec) return
            // Langsung tampilkan: yang penting jeda sekecil mungkin, bukan waktu tampil yang rata.
            try {
                codec.releaseOutputBuffer(index, true)
            } catch (_: IllegalStateException) {
                return
            }
            if (!rendered) {
                rendered = true
                listener.onFirstFrame()
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== this@ScreenDecoder.codec) return
            restart()
            listener.onKeyframeNeeded()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    private companion object {
        const val MAX_PENDING = 8
        const val FRAME_US = 33_333L
    }
}
