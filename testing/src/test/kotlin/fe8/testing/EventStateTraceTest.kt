package fe8.testing

import fe8.game.event.EventState
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** [EventState] load/store against the `evglobals` watch of `ram_trace_prologue`. */
class EventStateTraceTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `evglobals watch round-trips through EventState`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val w = ref.run("ram_trace_prologue").watch("evglobals")
        assertEquals(EventState.BASE_ADDRESS, w.address)
        assertEquals(EventState.BLOCK_SIZE, w.length)
        val state = EventState()
        val out = ByteArray(EventState.BLOCK_SIZE)
        var nonZero = false
        for (step in w.firstStep..w.lastStep) {
            val raw = w.step(step)
            state.load(raw, 0)
            state.store(out, 0)
            assertArrayEquals(raw, out, "step $step")
            if (state.slots.any { it != 0 } || state.slotQueue.any { it != 0 } ||
                state.callQueue.any { it != 0 } || state.activeQueue.any { it != 0 }
            ) {
                nonZero = true
            }
        }
        assertTrue(nonZero, "all steps were zero: layout unverified")
    }
}
