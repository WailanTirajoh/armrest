package io.github.wailantirajoh.cursorcontroller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenViewportTest {
    // Area 400 × 300 dp, video 16:10 → 400 × 250 dp di tengah (ada sisa 25 dp di atas dan bawah).
    private val geometry = ScreenViewport.Geometry.fit(400f, 300f, 1.6f)

    @Test
    fun videoFitsTheArea() {
        assertEquals(ScreenViewport.Geometry(400f, 300f, 400f, 250f), geometry)
        assertEquals(ScreenViewport.Geometry(400f, 300f, 300f * 0.5f, 300f), ScreenViewport.Geometry.fit(400f, 300f, 0.5f))
    }

    @Test
    fun pinchKeepsThePointUnderTheFingers() {
        val zoomed = ScreenViewport().pinch(geometry, 2f, 100f, 150f, 0f, 0f)
        assertEquals(2f, zoomed.scale)
        // Titik di bawah jari (x = 100 dp, yaitu 0,25 lebar video) tetap di x = 100 dp.
        assertEquals(100f, zoomed.pointX(geometry, 0.25f), 0.001f)
        assertEquals(150f, zoomed.pointY(geometry, 0.5f), 0.001f)
    }

    @Test
    fun zoomStaysWithinLimitsAndNeverShowsBeyondTheVideo() {
        val max = ScreenViewport().pinch(geometry, 10f, 200f, 150f, 0f, 0f)
        assertEquals(ScreenViewport.MAX_SCALE, max.scale)
        val pannedTooFar = max.pinch(geometry, 1f, 200f, 150f, 5_000f, 5_000f)
        // Video 1600 × 1000 dp di area 400 × 300: geseran paling jauh 600 dan 350 dp.
        assertEquals(600f, pannedTooFar.offsetX)
        assertEquals(350f, pannedTooFar.offsetY)
        assertEquals(ScreenViewport.RESET, max.pinch(geometry, 0.01f, 0f, 0f, 30f, 30f))
    }

    @Test
    fun zoomedViewFollowsTheCursor() {
        val zoomed = ScreenViewport().pinch(geometry, 2f, 200f, 150f, 0f, 0f)
        assertTrue(zoomed.isZoomed)
        // Kursor di tengah: tidak perlu digeser.
        assertEquals(zoomed, zoomed.follow(geometry, 0.5f, 0.5f))
        // Kursor keluar ke kanan: tampilan bergeser ke kiri sampai kursor 15% dari tepi kanan area.
        val followed = zoomed.follow(geometry, 0.75f, 0.5f)
        assertEquals(400f * (1 - ScreenViewport.FOLLOW_MARGIN), followed.pointX(geometry, 0.75f), 0.001f)
        // Di ujung video tampilan berhenti di tepi video, jadi kursor boleh lebih dekat ke tepi area.
        val edge = zoomed.follow(geometry, 1f, 0.5f)
        assertEquals(400f, edge.pointX(geometry, 1f), 0.001f)
    }

    @Test
    fun withoutZoomNothingMoves() {
        val viewport = ScreenViewport()
        assertFalse(viewport.isZoomed)
        assertEquals(viewport, viewport.follow(geometry, 1f, 1f))
    }
}
