package io.github.wailantirajoh.armrest.core

import kotlin.math.abs
import kotlin.math.hypot

/** Aksi hasil gesture, dalam dp. Diubah ke pesan biner oleh pengirim. */
sealed interface InputAction {
    data class Move(val dxDp: Float, val dyDp: Float) : InputAction
    data class Scroll(val dxDp: Float, val dyDp: Float) : InputAction
    data class Click(val button: MouseButton, val count: Int) : InputAction
    data class Button(val button: MouseButton, val down: Boolean) : InputAction
    /**
     * Cubit untuk zoom tampilan layar di HP; tidak dikirim ke komputer. `scale` relatif terhadap aksi sebelumnya,
     * `focus` = titik tengah jari, `pan` = geseran titik tengah sejak aksi sebelumnya.
     */
    data class Pinch(val scale: Float, val focusX: Float, val focusY: Float, val panX: Float, val panY: Float) : InputAction
}

/**
 * Menerjemahkan sentuhan di touchpad menjadi aksi mouse, meniru trackpad Mac:
 *
 * | Gesture                    | Aksi                                  |
 * | -------------------------- | ------------------------------------- |
 * | Geser 1 jari               | Move                                  |
 * | Tap 1 jari                 | Click kiri ×1                         |
 * | Tap 2 kali                 | Click kiri ×1, lalu ×2                |
 * | Tap 2 jari                 | Click kanan                           |
 * | Geser 2 jari               | Scroll                                |
 * | Cubit 2 jari               | Pinch (hanya kalau [zoomEnabled])     |
 * | Tap, lalu tahan dan geser  | Button kiri down, Move…, Button up    |
 *
 * Cubit dan scroll dibedakan di awal gesture: cubit kalau jarak antar jari berubah lebih jauh daripada titik
 * tengahnya bergeser. Setelah itu jenisnya tetap sampai semua jari diangkat.
 *
 * Koordinat dalam dp, waktu dalam ms. Tidak bergantung pada Android, jadi bisa dites di JVM.
 */
class GestureEngine(private val config: Config = Config()) {
    data class Config(
        val tapMaxMs: Long = 200,
        val tapSlopDp: Float = 8f,
        val doubleTapMs: Long = 300,
        val dragArmMs: Long = 250,
        val jitterDp: Float = 1f,
        val pinchSlopDp: Float = 10f,
    )

    private enum class TwoFinger { UNDECIDED, SCROLL, PINCH }

    /** Cubit dianggap zoom (mis. selama layar komputer tampil). Kalau false, cubit menjadi scroll seperti biasa. */
    var zoomEnabled = false

    private class Pointer(var x: Float, var y: Float, val startX: Float, val startY: Float) {
        var sentX = startX
        var sentY = startY
    }

    private val pointers = LinkedHashMap<Long, Pointer>()
    private var gestureStart = 0L
    private var maxPointers = 0
    private var moved = false
    private var twoFingerMoved = false
    private var twoFinger = TwoFinger.UNDECIDED
    // Jarak antar jari dan titik tengahnya: saat jari kedua turun (untuk menentukan jenis), dan saat aksi terakhir.
    private var startSpan = 0f
    private var startCenterX = 0f
    private var startCenterY = 0f
    private var lastSpan = 0f
    private var lastCenterX = 0f
    private var lastCenterY = 0f
    private var dragArmed = false
    private var dragging = false
    private var lastTapUp = NEVER

    fun down(id: Long, x: Float, y: Float, timeMs: Long): List<InputAction> {
        if (pointers.isEmpty()) {
            gestureStart = timeMs
            maxPointers = 0
            moved = false
            twoFingerMoved = false
            twoFinger = TwoFinger.UNDECIDED
            dragging = false
            dragArmed = timeMs - lastTapUp <= config.dragArmMs
        }
        pointers[id] = Pointer(x, y, x, y)
        maxPointers = maxOf(maxPointers, pointers.size)
        if (pointers.size == 2) {
            startSpan = span()
            startCenterX = centerX()
            startCenterY = centerY()
        }
        rememberShape()
        return emptyList()
    }

    fun move(id: Long, x: Float, y: Float, @Suppress("UNUSED_PARAMETER") timeMs: Long): List<InputAction> {
        val p = pointers[id] ?: return emptyList()
        val dx = x - p.x
        val dy = y - p.y
        p.x = x
        p.y = y
        val fromStart = hypot(x - p.startX, y - p.startY)

        if (maxPointers >= 2) {
            if (fromStart > config.tapSlopDp) twoFingerMoved = true
            if (twoFinger == TwoFinger.UNDECIDED) twoFinger = classify()
            return when (twoFinger) {
                TwoFinger.UNDECIDED -> emptyList()
                TwoFinger.PINCH -> pinch()
                TwoFinger.SCROLL -> {
                    if (dx == 0f && dy == 0f) return emptyList()
                    // Tiap jari menyumbang sebagian, jadi totalnya = rata-rata gerakan kedua jari.
                    val share = pointers.size.coerceAtLeast(1)
                    listOf(InputAction.Scroll(dx / share, dy / share))
                }
            }
        }

        val out = ArrayList<InputAction>(2)
        if (!moved && fromStart > config.tapSlopDp) {
            moved = true
            if (dragArmed) {
                dragging = true
                out += InputAction.Button(MouseButton.LEFT, down = true)
            }
        }
        // Sebelum lewat ambang tap: getaran kecil diabaikan, dan gerakan calon drag ditahan dulu.
        if (!moved && (dragArmed || fromStart < config.jitterDp)) return out
        val mx = x - p.sentX
        val my = y - p.sentY
        p.sentX = x
        p.sentY = y
        if (mx != 0f || my != 0f) out += InputAction.Move(mx, my)
        return out
    }

    fun up(id: Long, timeMs: Long): List<InputAction> {
        if (pointers.remove(id) == null) return emptyList()
        if (pointers.isNotEmpty()) {
            // Jari yang tersisa melanjutkan cubit dari posisinya sekarang, tanpa lompatan.
            rememberShape()
            return emptyList()
        }
        val duration = timeMs - gestureStart
        val out = ArrayList<InputAction>(1)
        var singleTap = false
        when {
            maxPointers >= 2 ->
                if (twoFinger != TwoFinger.PINCH && !twoFingerMoved && duration < config.tapMaxMs) out += InputAction.Click(MouseButton.RIGHT, 1)
            dragging -> out += InputAction.Button(MouseButton.LEFT, down = false)
            !moved && duration < config.tapMaxMs -> {
                val double = gestureStart - lastTapUp <= config.doubleTapMs
                out += InputAction.Click(MouseButton.LEFT, if (double) 2 else 1)
                singleTap = !double
            }
        }
        lastTapUp = if (singleTap) timeMs else NEVER
        dragging = false
        return out
    }

    /** Sentuhan dibatalkan sistem (mis. layar terkunci): lepas tombol yang sedang ditahan. */
    fun cancel(): List<InputAction> {
        val out = if (dragging) listOf(InputAction.Button(MouseButton.LEFT, down = false)) else emptyList()
        pointers.clear()
        dragging = false
        lastTapUp = NEVER
        return out
    }

    /**
     * Tanpa zoom, gesture 2 jari selalu scroll begitu ada jari yang bergeser (perilaku lama). Dengan zoom: cubit kalau
     * jarak antar jari berubah lebih dari [Config.pinchSlopDp] dan lebih jauh daripada geseran titik tengahnya;
     * scroll kalau titik tengah bergeser lebih dulu.
     */
    private fun classify(): TwoFinger {
        if (!zoomEnabled || pointers.size < 2) return if (twoFingerMoved) TwoFinger.SCROLL else TwoFinger.UNDECIDED
        val spanChange = abs(span() - startSpan)
        val shift = hypot(centerX() - startCenterX, centerY() - startCenterY)
        return when {
            spanChange >= config.pinchSlopDp && spanChange > shift -> {
                // Zoom dimulai dari bentuk jari saat jari kedua turun, jadi gerakan awal tidak hilang.
                lastSpan = startSpan
                lastCenterX = startCenterX
                lastCenterY = startCenterY
                TwoFinger.PINCH
            }
            shift >= config.tapSlopDp -> TwoFinger.SCROLL
            else -> TwoFinger.UNDECIDED
        }
    }

    private fun pinch(): List<InputAction> {
        if (pointers.size < 2 || lastSpan <= 0f) return emptyList()
        val span = span()
        val x = centerX()
        val y = centerY()
        val action = InputAction.Pinch(span / lastSpan, x, y, x - lastCenterX, y - lastCenterY)
        lastSpan = span
        lastCenterX = x
        lastCenterY = y
        return if (action.scale == 1f && action.panX == 0f && action.panY == 0f) emptyList() else listOf(action)
    }

    private fun rememberShape() {
        if (pointers.size < 2 || twoFinger != TwoFinger.PINCH) return
        lastSpan = span()
        lastCenterX = centerX()
        lastCenterY = centerY()
    }

    private fun centerX() = pointers.values.sumOf { it.x.toDouble() }.toFloat() / pointers.size

    private fun centerY() = pointers.values.sumOf { it.y.toDouble() }.toFloat() / pointers.size

    /** Dua kali rata-rata jarak jari ke titik tengah; untuk dua jari = jarak antar jari. */
    private fun span(): Float {
        val cx = centerX()
        val cy = centerY()
        return 2 * pointers.values.sumOf { hypot(it.x - cx, it.y - cy).toDouble() }.toFloat() / pointers.size
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2
    }
}
