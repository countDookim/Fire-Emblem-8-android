package fe8.game.event

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EventStateTest {
    @Test
    fun `push appends at slot D and pop shifts down`() {
        val s = EventState()
        s.slotQueuePush(11)
        s.slotQueuePush(22)
        s.slotQueuePush(33)
        assertEquals(3, s.slots[0xD])
        assertEquals(11, s.slotQueue[0])
        assertEquals(11, s.slotQueuePop())
        assertEquals(2, s.slots[0xD])
        assertEquals(22, s.slotQueue[0])
        assertEquals(33, s.slotQueue[1])
        assertEquals(22, s.slotQueuePop())
        assertEquals(33, s.slotQueuePop())
        assertEquals(0, s.slots[0xD])
    }

    @Test
    fun `push past the queue overwrites the counter and padding`() {
        val s = EventState()
        s.slots[0xD] = 30
        s.slotQueuePush(0x1234)
        assertEquals(0x1234, s.getEventSlotCounter())
        s.slotQueuePush(0x55)
        assertEquals(0x55, s.padding)
        s.slotQueuePush(0x66)
        assertEquals(0x66, s.activeQueue[0])
        assertEquals(33, s.slots[0xD])
    }

    @Test
    fun `pop on an empty queue wraps slot D without shifting`() {
        val s = EventState()
        s.slotQueue[0] = 7
        assertEquals(7, s.slotQueuePop())
        assertEquals(-1, s.slots[0xD])
    }

    @Test
    fun `counter and slot C accessors`() {
        val s = EventState()
        s.setEventSlotCounter(-5)
        assertEquals(-5, s.getEventSlotCounter())
        s.setEventSlotC(9)
        assertEquals(9, s.slots[0xC])
    }

    @Test
    fun `store of load reproduces the bytes`() {
        val m = ByteArray(EventState.BLOCK_SIZE + 8) { (it * 37 + 5).toByte() }
        val s = EventState()
        s.load(m, 4)
        val out = m.copyOf()
        out.fill(0, 4, 4 + EventState.BLOCK_SIZE)
        s.store(out, 4)
        assertArrayEquals(m, out)
    }
}
