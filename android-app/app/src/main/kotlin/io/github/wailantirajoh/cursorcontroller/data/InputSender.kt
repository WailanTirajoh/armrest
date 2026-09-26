package io.github.wailantirajoh.cursorcontroller.data

import android.view.Choreographer
import io.github.wailantirajoh.cursorcontroller.core.AgentConnection
import io.github.wailantirajoh.cursorcontroller.core.DeltaAccumulator
import io.github.wailantirajoh.cursorcontroller.core.InputAction
import io.github.wailantirajoh.cursorcontroller.core.InputMessage

/**
 * Mengirim aksi gesture ke agent. Gerakan dikumpulkan dan dikirim maksimal satu paket per frame;
 * kalau antrean kirim menumpuk (jaringan lambat), gerakan digabung ke frame berikutnya.
 * Klik dan tombol dikirim segera dan tidak pernah dibuang. Dipanggil dari main thread.
 */
class InputSender(private val connection: () -> AgentConnection?) : Choreographer.FrameCallback {
    private val moves = DeltaAccumulator()
    private val scrolls = DeltaAccumulator()
    private var scheduled = false

    fun submit(actions: List<InputAction>) {
        if (actions.isEmpty()) return
        for (action in actions) {
            when (action) {
                is InputAction.Move -> moves.add(action.dxDp, action.dyDp)
                is InputAction.Scroll -> scrolls.add(action.dxDp, action.dyDp)
                is InputAction.Click -> {
                    flush(force = true)
                    connection()?.sendInput(InputMessage.Click(action.button, action.count))
                }
                is InputAction.Button -> {
                    flush(force = true)
                    connection()?.sendInput(InputMessage.Button(action.button, action.down))
                }
            }
        }
        schedule()
    }

    fun reset() {
        moves.reset()
        scrolls.reset()
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!flush(force = false)) schedule()
    }

    /** true kalau semua gerakan sudah terkirim. */
    private fun flush(force: Boolean): Boolean {
        val target = connection() ?: run {
            reset()
            return true
        }
        if (!force && target.queueSize() > BACKLOG_BYTES) return false
        moves.drain()?.let { (dx, dy) -> target.sendInput(InputMessage.Move(dx, dy)) }
        scrolls.drain()?.let { (dx, dy) -> target.sendInput(InputMessage.Scroll(dx, dy)) }
        // Sisa yang melebihi batas satu paket dikirim di frame berikutnya.
        return !moves.hasPending && !scrolls.hasPending
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private companion object {
        // ± 800 paket move; di atas ini jaringan jelas tertinggal.
        const val BACKLOG_BYTES = 4_096L
    }
}
