package io.github.wailantirajoh.cursorcontroller.core

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
}
