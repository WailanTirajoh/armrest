package io.github.wailantirajoh.cursorcontroller.core

/**
 * Zoom dan geseran video layar komputer di area touchpad, dalam dp. Video mula-mula pas (fit) di tengah area;
 * `scale` memperbesarnya di sekitar pusat video, lalu `offsetX`/`offsetY` menggeser pusat video dari pusat area.
 * Tidak bergantung pada Android, jadi bisa dites di JVM.
 */
data class ScreenViewport(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    /** Ukuran area touchpad dan ukuran video yang sudah pas di dalamnya (skala 1). */
    data class Geometry(val areaWidth: Float, val areaHeight: Float, val videoWidth: Float, val videoHeight: Float) {
        companion object {
            /** Video dengan rasio `aspect` (lebar / tinggi) dibuat pas di area, seperti tampilan tanpa zoom. */
            fun fit(areaWidth: Float, areaHeight: Float, aspect: Float): Geometry {
                val videoWidth = minOf(areaWidth, areaHeight * aspect)
                return Geometry(areaWidth, areaHeight, videoWidth, videoWidth / aspect)
            }
        }
    }

    val isZoomed get() = scale > 1.001f

    /**
     * Cubit: perbesar `factor` kali di sekitar titik fokus (koordinat area), lalu geser sejauh `panX`/`panY`.
     * Titik video di bawah jari tetap di bawah jari.
     */
    fun pinch(geometry: Geometry, factor: Float, focusX: Float, focusY: Float, panX: Float, panY: Float): ScreenViewport {
        val newScale = (scale * factor).coerceIn(1f, MAX_SCALE)
        val ratio = newScale / scale
        val centerX = geometry.areaWidth / 2 + offsetX
        val centerY = geometry.areaHeight / 2 + offsetY
        val newCenterX = focusX + ratio * (centerX - focusX) + panX
        val newCenterY = focusY + ratio * (centerY - focusY) + panY
        return ScreenViewport(newScale, newCenterX - geometry.areaWidth / 2, newCenterY - geometry.areaHeight / 2).clamped(geometry)
    }

    /**
     * Geser seperlunya supaya kursor (0–1 dari kiri atas video) tetap terlihat, minimal [FOLLOW_MARGIN] dari tepi area.
     * Tanpa zoom tidak ada yang digeser.
     */
    fun follow(geometry: Geometry, cursorX: Float, cursorY: Float): ScreenViewport {
        if (!isZoomed) return this
        val x = pointX(geometry, cursorX)
        val y = pointY(geometry, cursorY)
        val dx = pushInside(x, geometry.areaWidth, geometry.areaWidth * FOLLOW_MARGIN)
        val dy = pushInside(y, geometry.areaHeight, geometry.areaHeight * FOLLOW_MARGIN)
        if (dx == 0f && dy == 0f) return this
        return copy(offsetX = offsetX + dx, offsetY = offsetY + dy).clamped(geometry)
    }

    /** Ukuran area atau video berubah (mis. HP diputar): pertahankan zoom, rapikan geseran. */
    fun clamped(geometry: Geometry): ScreenViewport {
        if (!isZoomed) return RESET
        val limitX = maxOf(0f, (geometry.videoWidth * scale - geometry.areaWidth) / 2)
        val limitY = maxOf(0f, (geometry.videoHeight * scale - geometry.areaHeight) / 2)
        return copy(offsetX = offsetX.coerceIn(-limitX, limitX), offsetY = offsetY.coerceIn(-limitY, limitY))
    }

    /** Posisi titik video (0–1) di koordinat area. */
    fun pointX(geometry: Geometry, x: Float) = geometry.areaWidth / 2 + offsetX + (x - 0.5f) * geometry.videoWidth * scale

    fun pointY(geometry: Geometry, y: Float) = geometry.areaHeight / 2 + offsetY + (y - 0.5f) * geometry.videoHeight * scale

    private fun pushInside(position: Float, size: Float, margin: Float): Float = when {
        position < margin -> margin - position
        position > size - margin -> size - margin - position
        else -> 0f
    }

    companion object {
        const val MAX_SCALE = 4f
        /** Jarak minimum kursor dari tepi area, sebagai bagian dari ukuran area. */
        const val FOLLOW_MARGIN = 0.15f
        val RESET = ScreenViewport()
    }
}
