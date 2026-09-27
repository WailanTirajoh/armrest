package io.github.wailantirajoh.armrest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {
    private val engine = GestureEngine()

    private fun MutableList<InputAction>.addAll(vararg batches: List<InputAction>) = batches.forEach { addAll(it) }

    @Test
    fun oneFingerSlideMovesCursor() {
        val out = mutableListOf<InputAction>()
        out.addAll(
            engine.down(1, 100f, 100f, 0),
            engine.move(1, 110f, 105f, 16),
            engine.move(1, 130f, 105f, 32),
            engine.up(1, 300),
        )
        assertEquals(listOf(InputAction.Move(10f, 5f), InputAction.Move(20f, 0f)), out)
    }

    @Test
    fun tinyJitterIsIgnored() {
        engine.down(1, 100f, 100f, 0)
        assertTrue(engine.move(1, 100.5f, 100.3f, 16).isEmpty())
    }

    @Test
    fun quickTapIsLeftClick() {
        engine.down(1, 100f, 100f, 0)
        assertEquals(listOf(InputAction.Click(MouseButton.LEFT, 1)), engine.up(1, 120))
    }

    @Test
    fun slowPressIsNotAClick() {
        engine.down(1, 100f, 100f, 0)
        assertTrue(engine.up(1, 450).isEmpty())
    }

    @Test
    fun twoTapsAreSingleThenDoubleClick() {
        engine.down(1, 100f, 100f, 0)
        assertEquals(listOf(InputAction.Click(MouseButton.LEFT, 1)), engine.up(1, 80))
        engine.down(1, 101f, 100f, 200)
        assertEquals(listOf(InputAction.Click(MouseButton.LEFT, 2)), engine.up(1, 260))
        // Tap ketiga setelah klik ganda kembali jadi klik tunggal.
        engine.down(1, 101f, 100f, 400)
        assertEquals(listOf(InputAction.Click(MouseButton.LEFT, 1)), engine.up(1, 450))
    }

    @Test
    fun twoFingerTapIsRightClick() {
        engine.down(1, 100f, 100f, 0)
        engine.down(2, 160f, 100f, 20)
        assertTrue(engine.up(1, 100).isEmpty())
        assertEquals(listOf(InputAction.Click(MouseButton.RIGHT, 1)), engine.up(2, 120))
    }

    @Test
    fun twoFingerSlideScrollsWithAveragedDelta() {
        engine.down(1, 100f, 100f, 0)
        engine.down(2, 160f, 100f, 10)
        assertTrue(engine.move(1, 100f, 95f, 20).isEmpty()) // belum lewat ambang 8 dp
        val out = mutableListOf<InputAction>()
        out.addAll(engine.move(1, 100f, 80f, 30), engine.move(2, 160f, 80f, 30))
        assertEquals(listOf(InputAction.Scroll(0f, -7.5f), InputAction.Scroll(0f, -10f)), out)
        assertTrue(engine.up(1, 400).isEmpty())
        assertTrue(engine.up(2, 410).isEmpty())
    }

    @Test
    fun tapThenHoldAndSlideDrags() {
        engine.down(1, 100f, 100f, 0)
        engine.up(1, 80) // tap pertama
        engine.down(1, 100f, 100f, 200) // jari turun lagi < 250 ms
        assertTrue(engine.move(1, 104f, 100f, 220).isEmpty()) // ditahan sampai jelas drag
        val out = mutableListOf<InputAction>()
        out.addAll(engine.move(1, 120f, 100f, 240), engine.move(1, 150f, 110f, 260), engine.up(1, 600))
        assertEquals(
            listOf(
                InputAction.Button(MouseButton.LEFT, down = true),
                InputAction.Move(20f, 0f),
                InputAction.Move(30f, 10f),
                InputAction.Button(MouseButton.LEFT, down = false),
            ),
            out,
        )
    }

    @Test
    fun cancelReleasesHeldButton() {
        engine.down(1, 100f, 100f, 0)
        engine.up(1, 80)
        engine.down(1, 100f, 100f, 200)
        engine.move(1, 130f, 100f, 240)
        assertEquals(listOf(InputAction.Button(MouseButton.LEFT, down = false)), engine.cancel())
    }

    private fun zoomEngine() = GestureEngine().apply { zoomEnabled = true }

    @Test
    fun pinchApartZoomsInsteadOfScrolling() {
        val engine = zoomEngine()
        val out = mutableListOf<InputAction>()
        out.addAll(
            engine.down(1, 100f, 100f, 0),
            engine.down(2, 200f, 100f, 10),
            engine.move(1, 90f, 100f, 30),
            engine.move(2, 210f, 100f, 30),
            engine.move(1, 60f, 100f, 50),
            engine.move(2, 240f, 100f, 50),
        )
        assertTrue(out.isNotEmpty() && out.all { it is InputAction.Pinch })
        // Jarak antar jari dari 100 menjadi 180 dp: total skala 1,8 di sekitar titik tengah yang sama.
        val scale = out.filterIsInstance<InputAction.Pinch>().fold(1f) { total, pinch -> total * pinch.scale }
        assertEquals(1.8f, scale, 0.001f)
        // Jari diproses satu per satu, jadi titik tengah bergeser sedikit di antaranya lalu kembali ke 150.
        val pinches = out.filterIsInstance<InputAction.Pinch>()
        assertTrue(pinches.all { it.focusY == 100f })
        assertEquals(150f, pinches.last().focusX)
        assertEquals(0f, pinches.sumOf { it.panX.toDouble() }.toFloat(), 0.001f)
        // Cubit bukan klik kanan.
        assertTrue(engine.up(1, 100).isEmpty())
        assertTrue(engine.up(2, 110).isEmpty())
    }

    @Test
    fun oneFingerStretchIsStillAPinch() {
        val engine = zoomEngine()
        engine.down(1, 100f, 100f, 0)
        engine.down(2, 200f, 100f, 10)
        // Jari 1 diam, jari 2 menjauh: titik tengah ikut bergeser, tapi jaraknya berubah dua kali lebih jauh.
        val out = engine.move(2, 212f, 100f, 30)
        val pinch = out.single() as InputAction.Pinch
        assertEquals(1.12f, pinch.scale, 0.001f)
        assertEquals(6f, pinch.panX, 0.001f)
    }

    @Test
    fun parallelTwoFingerSlideStillScrollsWhenZoomIsEnabled() {
        val engine = zoomEngine()
        val out = mutableListOf<InputAction>()
        out.addAll(
            engine.down(1, 100f, 100f, 0),
            engine.down(2, 160f, 100f, 10),
            engine.move(1, 100f, 110f, 30),
            engine.move(2, 160f, 110f, 30),
        )
        assertEquals(listOf(InputAction.Scroll(0f, 5f)), out)
    }

    @Test
    fun pinchIsScrollWhenZoomIsDisabled() {
        engine.down(1, 100f, 100f, 0)
        engine.down(2, 200f, 100f, 10)
        engine.move(1, 90f, 100f, 30)
        assertEquals(listOf(InputAction.Scroll(-5f, 0f)), engine.move(1, 80f, 100f, 40))
    }
}

