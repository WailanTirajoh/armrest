package io.github.wailantirajoh.cursorcontroller.core

import kotlin.math.hypot

/** Aksi hasil gesture, dalam dp. Diubah ke pesan biner oleh pengirim. */
sealed interface InputAction {
    data class Move(val dxDp: Float, val dyDp: Float) : InputAction
    data class Scroll(val dxDp: Float, val dyDp: Float) : InputAction
    data class Click(val button: MouseButton, val count: Int) : InputAction
    data class Button(val button: MouseButton, val down: Boolean) : InputAction
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
 * | Tap, lalu tahan dan geser  | Button kiri down, Move…, Button up    |
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
    )

    private class Pointer(var x: Float, var y: Float, val startX: Float, val startY: Float) {
        var sentX = startX
        var sentY = startY
    }

    private val pointers = LinkedHashMap<Long, Pointer>()
    private var gestureStart = 0L
    private var maxPointers = 0
    private var moved = false
    private var twoFingerMoved = false
    private var dragArmed = false
    private var dragging = false
    private var lastTapUp = NEVER

    fun down(id: Long, x: Float, y: Float, timeMs: Long): List<InputAction> {
        if (pointers.isEmpty()) {
            gestureStart = timeMs
            maxPointers = 0
            moved = false
            twoFingerMoved = false
            dragging = false
            dragArmed = timeMs - lastTapUp <= config.dragArmMs
        }
        pointers[id] = Pointer(x, y, x, y)
        maxPointers = maxOf(maxPointers, pointers.size)
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
            if (!twoFingerMoved || (dx == 0f && dy == 0f)) return emptyList()
            // Tiap jari menyumbang sebagian, jadi totalnya = rata-rata gerakan kedua jari.
            val share = pointers.size.coerceAtLeast(1)
            return listOf(InputAction.Scroll(dx / share, dy / share))
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
        if (pointers.remove(id) == null || pointers.isNotEmpty()) return emptyList()
        val duration = timeMs - gestureStart
        val out = ArrayList<InputAction>(1)
        var singleTap = false
        when {
            maxPointers >= 2 ->
                if (!twoFingerMoved && duration < config.tapMaxMs) out += InputAction.Click(MouseButton.RIGHT, 1)
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

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2
    }
}
