package fe8.testing

import fe8.game.event.EventState
import gba.core.VerifiesPort
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

    @Test
    @VerifiesPort("SlotQueuePush", "SlotQueuePop")
    fun `slotQueue operations on trace dump state maintain queue and slot D`() {
        assumeTrue(ref.available, "mGBA reference data not present")
        val iwram = ref.run("ram_trace_prologue").region("r19320", "iwram")
        val state = EventState()
        state.load(iwram, EventState.BASE_ADDRESS - 0x03000000)

        // At r19320, 6 items were pushed into gEventSlotQueue, slot D is 6
        assertEquals(6, state.slots[EventState.QUEUE_SLOT])
        val item0 = state.slotQueue[0]
        val item1 = state.slotQueue[1]
        assertEquals(0x00018104, item0)

        // Pop should return item0 and shift remaining items down
        val popped = state.slotQueuePop()
        assertEquals(item0, popped)
        assertEquals(5, state.slots[EventState.QUEUE_SLOT])
        assertEquals(item1, state.slotQueue[0])

        // Push should append at slot D
        state.slotQueuePush(0x12345678)
        assertEquals(6, state.slots[EventState.QUEUE_SLOT])
        assertEquals(0x12345678, state.slotQueue[5])
    }

    @Test
    @VerifiesPort("SetEventSlotC")
    fun `setEventSlotC stores value in slot 0xC matching trace layout`() {
        assumeTrue(ref.available, "mGBA reference data not present")
        val iwram = ref.run("ram_trace_prologue").region("r19320", "iwram")
        val state = EventState()
        state.load(iwram, EventState.BASE_ADDRESS - 0x03000000)

        // At r19320, slot C is 1
        assertEquals(1, state.slots[0xC])

        // setEventSlotC modifies slot C
        state.setEventSlotC(0xCAFE)
        assertEquals(0xCAFE, state.slots[0xC])

        // Store back out and check slot 0xC in raw bytes matches IWRAM offset
        val out = ByteArray(EventState.BLOCK_SIZE)
        state.store(out, 0)
        val slotCOffset = EventState.SLOTS_OFFSET + 4 * 0xC
        val rawVal = (out[slotCOffset].toInt() and 0xFF) or
            ((out[slotCOffset + 1].toInt() and 0xFF) shl 8) or
            ((out[slotCOffset + 2].toInt() and 0xFF) shl 16) or
            ((out[slotCOffset + 3].toInt() and 0xFF) shl 24)
        assertEquals(0xCAFE, rawVal)
    }
}
