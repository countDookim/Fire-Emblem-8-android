package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_END
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EVC_STOP_CONTINUE
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EVC_UNK4
import fe8.game.event.EventConst.EV_EXEC_CUTSCENE
import fe8.game.event.EventConst.EV_EXEC_GAMEPLAY
import fe8.game.event.EventConst.EV_EXEC_UNK4
import fe8.game.event.EventConst.EV_EXEC_UNK5
import fe8.game.event.EventConst.EV_EXEC_WORLDMAP
import fe8.game.event.EventConst.EV_STATE_0008
import fe8.game.event.EventConst.EV_STATE_0040
import fe8.game.event.EventConst.EV_STATE_ABORT
import fe8.game.event.EventConst.EV_STATE_NOSKIP
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EventScrCoreTest {
    private val base = ScriptWords.MOD_BASE
    private var words = ShortArray(0)
    private val mem = ScriptMemory { a ->
        val i = (a - base) ushr 1
        if (i in words.indices) words[i].toInt() and 0xFFFF else 0
    }
    private val state = EventState()
    private var bPressed = false
    private var aHeld = false
    private var speed = 0
    private val input = object : EventInput {
        override fun bPressed() = bPressed
        override fun aHeld() = aHeld
        override fun gameSpeed() = speed
    }
    private val core = EventScrCore(state, mem, input)
    private val proc = EventEngineProc()

    private fun script(vararg w: Int) {
        words = ShortArray(w.size) { w[it].toShort() }
        proc.pEventStart = base
        proc.pEventIdk = base
        proc.pEventCurrent = base
    }

    private fun run(h: EventHandler, at: Int = 0): Int {
        proc.pEventCurrent = base + at * 2
        return h.run(proc)
    }

    private fun slotOp(sub: Int, dest: Int, a: Int, b: Int, x: Int, y: Int): Int {
        state.slots[a] = x
        state.slots[b] = y
        script(0x0620 or sub, dest or (a shl 4) or (b shl 8))
        assertEquals(EVC_ADVANCE_CONTINUE, run(core.event06SlotOperation))
        return state.slots[dest]
    }

    @Test
    fun `null and label advance`() {
        script(0x0010, 0x0820, 0)
        assertEquals(EVC_ADVANCE_CONTINUE, run(core.event00Null))
        assertEquals(EVC_ADVANCE_CONTINUE, run(core.event08Label, 1))
    }

    @Test
    fun `set slot writes a 32 bit value`() {
        script(0x0540, 3, 0x5678, 0x1234)
        assertEquals(EVC_ADVANCE_CONTINUE, run(core.event05SetSlot))
        assertEquals(0x12345678, state.slots[3])
        script(0x0540, 4, 0xFFFF, 0x8000)
        run(core.event05SetSlot)
        assertEquals(0x8000FFFF.toInt(), state.slots[4])
    }

    @Test
    fun `slot arithmetic and logic`() {
        assertEquals(7, slotOp(0, 3, 1, 2, 3, 4))
        assertEquals(-1, slotOp(1, 3, 1, 2, 3, 4))
        assertEquals(12, slotOp(2, 3, 1, 2, 3, 4))
        assertEquals(0x30, slotOp(5, 3, 1, 2, 0x3C, 0xF3))
        assertEquals(0xFF, slotOp(6, 3, 1, 2, 0xF0, 0x0F))
        assertEquals(0xFF, slotOp(7, 3, 1, 2, 0xF0, 0x0F))
        assertEquals(12, slotOp(8, 3, 1, 2, 3, 2))
        assertEquals(3, slotOp(9, 3, 1, 2, 12, 2))
    }

    @Test
    fun `division and modulo are unsigned`() {
        assertEquals(3, slotOp(3, 3, 1, 2, 10, 3))
        assertEquals(1, slotOp(4, 3, 1, 2, 10, 3))
        // 0xFFFFFFF0 / 16 = 0x0FFFFFFF as u32 (signed would give -1)
        assertEquals(0x0FFFFFFF, slotOp(3, 3, 1, 2, -16, 16))
        assertEquals(0x7FFFFFFF, slotOp(4, 3, 1, 2, -1, 0x80000000.toInt()))
        assertEquals(0, slotOp(3, 3, 1, 2, 10, 0))
        assertEquals(0, slotOp(4, 3, 1, 2, 10, 0))
    }

    @Test
    fun `shifts follow ARM semantics`() {
        assertEquals(0x80000000.toInt(), slotOp(8, 3, 1, 2, 1, 31))
        assertEquals(0, slotOp(8, 3, 1, 2, 1, 32))
        assertEquals(0, slotOp(8, 3, 1, 2, 1, 64))
        assertEquals(1, slotOp(8, 3, 1, 2, 1, 0x100))
        assertEquals(1, slotOp(9, 3, 1, 2, 0x80000000.toInt(), 31))
        assertEquals(0, slotOp(9, 3, 1, 2, -1, 32))
        assertEquals(0x7FFFFFFF, slotOp(9, 3, 1, 2, -1, 1))
    }

    @Test
    fun `unknown slot op is an error`() {
        script(0x0620 or 10, 0)
        assertEquals(EVC_ERROR, run(core.event06SlotOperation))
    }

    @Test
    fun `slot queue push and pop`() {
        state.slots[5] = 11
        state.slots[1] = 22
        script(0x0720, 5, 0x0721, 0, 0x0722, 6)
        run(core.event07SlotQueueOperations, 0)
        run(core.event07SlotQueueOperations, 2)
        assertEquals(2, state.slots[13])
        assertEquals(11, state.slotQueue[0])
        assertEquals(22, state.slotQueue[1])
        run(core.event07SlotQueueOperations, 4)
        assertEquals(11, state.slots[6])
        assertEquals(1, state.slots[13])
        assertEquals(22, state.slotQueue[0])
    }

    @Test
    fun `counter set check inc dec with clamping`() {
        // nibble 2 (shift 8), plus 0x1A: 0x1A % 8 = 2
        fun op(sub: Int, argument: Int): Int {
            // layout: cmd, ARGV0 (argument), word at cur + 2... ARGV0 is cur+2
            script(0x0F20 or sub, argument, 0)
            return run(core.event0fCounterOps)
        }
        // ARGV[0] is the word at cur + 2: low byte = nibble index, high byte = SET value
        assertEquals(EVC_ADVANCE_CONTINUE, op(1, 0x0502 or 0)) // set nibble 2 = 5
        assertEquals(0x500, state.slotCounter)
        op(2, 0x0002)
        assertEquals(0x600, state.slotCounter)
        op(3, 0x0002)
        op(3, 0x0002)
        assertEquals(0x400, state.slotCounter)
        op(0, 0x000A) // 0x0A % 8 = 2
        assertEquals(4, state.slots[0xC])
        state.slotCounter = 0xF00
        op(2, 0x0002)
        assertEquals(0xF00, state.slotCounter)
        state.slotCounter = 0x000
        op(3, 0x0002)
        assertEquals(0, state.slotCounter)
        op(1, 0x1207) // set nibble 7 = 0x12 low nibble 2
        assertEquals(0x20000000, state.slotCounter)
        op(1, 0x0F00)
        assertEquals(0x2000000F, state.slotCounter)
    }

    @Test
    fun `goto finds the label from pEventIdk`() {
        script(0x0920, 7, 0x0010, 0x0010, 0x0820, 6, 0x0820, 7, 0x0010)
        proc.pEventIdk = base
        assertEquals(EVC_ADVANCE_CONTINUE, run(core.event09Goto))
        assertEquals(base + 6 * 2, proc.pEventCurrent)
        // label at the very start of the scan
        script(0x0820, 7, 0x0920, 7)
        assertEquals(EVC_ADVANCE_CONTINUE, run(core.event09Goto, 2))
        assertEquals(base, proc.pEventCurrent)
    }

    @Test
    fun `goto without label errors instead of hanging`() {
        script(0x0920, 9, 0x0820, 7)
        assertEquals(EVC_ERROR, run(core.event09Goto))
    }

    @Test
    fun `branches taken and not taken`() {
        // branch sub: arg0 label, arg1 slot a, arg2 slot b; label 3 at word 4
        fun branch(sub: Int, x: Int, y: Int): Boolean {
            state.slots[1] = x
            state.slots[2] = y
            script(0x0C40 or sub, 3, 1, 2, 0x0820, 3)
            val r = run(core.event0cBranch)
            assertEquals(EVC_ADVANCE_CONTINUE, r)
            return proc.pEventCurrent == base + 8
        }
        assertEquals(listOf(true, false), listOf(branch(0, 5, 5), branch(0, 5, 6)))
        assertEquals(listOf(false, true), listOf(branch(1, 5, 5), branch(1, 5, 6)))
        assertEquals(
            listOf(true, true, false),
            listOf(branch(2, 5, 5), branch(2, 6, 5), branch(2, 4, 5)),
        )
        assertEquals(
            listOf(false, true, false),
            listOf(branch(3, 5, 5), branch(3, 6, 5), branch(3, 4, 5)),
        )
        assertEquals(
            listOf(true, true, false),
            listOf(branch(4, 5, 5), branch(4, 4, 5), branch(4, 6, 5)),
        )
        assertEquals(
            listOf(false, true, false),
            listOf(branch(5, 5, 5), branch(5, 4, 5), branch(5, 6, 5)),
        )
        // signed comparison
        assertEquals(true, branch(5, -1, 1))
        script(0x0C46, 3, 1, 2)
        assertEquals(EVC_ERROR, run(core.event0cBranch))
    }

    @Test
    fun `call pushes and end pops`() {
        // call target = base + 8, where an END sits
        script(0x0A40, 0, 0x0008, 0x0A00, 0x0120, 0)
        proc.pEventIdk = base + 2
        assertEquals(EVC_UNK4, run(core.event0aCall))
        assertEquals(base + 8, proc.pEventCurrent)
        assertEquals(base + 8, proc.pEventIdk)
        assertEquals(base + 2, state.activeQueue[0])
        assertEquals(base, state.activeQueue[1])
        assertEquals(EVC_ADVANCE_CONTINUE, run(core.event01End, 4))
        assertEquals(base + 2, proc.pEventIdk)
        assertEquals(base, proc.pEventCurrent)
        assertEquals(0, state.activeQueue[0])
    }

    @Test
    fun `call stack is eight deep and negative target uses slot 2`() {
        script(0x0A40, 0, 0xFFFF, 0xFFFF)
        state.slots[2] = base + 0x40
        for (i in 1..9) {
            proc.pEventIdk = base + i
            run(core.event0aCall)
        }
        assertEquals(base + 0x40, proc.pEventCurrent)
        assertEquals(base + 9, state.activeQueue[0])
        assertEquals(base + 2, state.activeQueue[14]) // call 1 fell off
    }

    @Test
    fun `endb clears the stack and end switches on exec type`() {
        script(0x0120, 0, 0x0121, 0)
        state.activeQueue[0] = 5
        state.activeQueue[1] = 6
        state.activeQueue[14] = 7
        proc.execType = EV_EXEC_GAMEPLAY
        assertEquals(EVC_END, run(core.event01End, 2))
        assertEquals(List(16) { 0 }, state.activeQueue.toList())

        proc.execType = EV_EXEC_WORLDMAP
        assertEquals(EVC_END, run(core.event01End))
        assertEquals(EV_EXEC_UNK4, proc.execType)

        proc.execType = EV_EXEC_CUTSCENE
        proc.evStateBits = EV_STATE_SKIPPING
        assertEquals(EVC_STOP_CONTINUE, run(core.event01End))
        assertEquals(EV_EXEC_UNK5, proc.execType)
        assertEquals(EV_STATE_NOSKIP, proc.evStateBits)
        assertEquals(EVENT_SCR_08592114, proc.pEventIdk)
        assertEquals(EVENT_SCR_08592114, proc.pEventCurrent)

        state.activeQueue[0] = 5
        proc.evStateBits = EV_STATE_ABORT
        assertEquals(EVC_END, run(core.event01End))
        assertEquals(5, state.activeQueue[0])
    }

    private fun stal(sub: Int, timer: Int = 0): Int {
        script(0x0E20 or sub, 3)
        proc.evStallTimer = timer
        return run(core.event0eStal)
    }

    @Test
    fun `stall counts down`() {
        assertEquals(EVC_STOP_YIELD, stal(0))
        assertEquals(3, proc.evStallTimer)
        assertEquals(EVC_STOP_YIELD, run(core.event0eStal))
        assertEquals(2, proc.evStallTimer)
        assertEquals(EVC_STOP_YIELD, run(core.event0eStal))
        assertEquals(1, proc.evStallTimer)
        assertEquals(EVC_ADVANCE_CONTINUE, run(core.event0eStal))
        assertEquals(0, proc.evStallTimer)
        // negative (as s16) timer restarts
        assertEquals(EVC_STOP_YIELD, stal(0, 0x8000))
        assertEquals(3, proc.evStallTimer)
    }

    @Test
    fun `stall speed up and skips`() {
        speed = 1
        assertEquals(EVC_STOP_YIELD, stal(2)) // load
        assertEquals(EVC_ADVANCE_CONTINUE, run(core.event0eStal)) // 3 - 4 <= 0
        assertEquals(EVC_STOP_YIELD, stal(0, 10)) // bit 1 clear: no speed-up
        assertEquals(9, proc.evStallTimer)
        assertEquals(EVC_STOP_YIELD, stal(2, 10))
        assertEquals(6, proc.evStallTimer)
        speed = 0
        aHeld = true
        stal(2, 10)
        assertEquals(6, proc.evStallTimer)
        proc.evStateBits = EV_STATE_0040
        stal(2, 10)
        assertEquals(9, proc.evStallTimer)
        // B and EV_STATE_0008 only with bit 0
        proc.evStateBits = 0
        bPressed = true
        assertEquals(EVC_STOP_YIELD, stal(2, 10))
        assertEquals(EVC_ADVANCE_CONTINUE, stal(1, 10))
        assertEquals(0, proc.evStallTimer)
        bPressed = false
        proc.evStateBits = EV_STATE_0008
        assertEquals(EVC_STOP_YIELD, stal(0, 10))
        assertEquals(EVC_ADVANCE_CONTINUE, stal(1, 10))
        // skipping
        proc.evStateBits = EV_STATE_SKIPPING
        assertEquals(EVC_ADVANCE_CONTINUE, stal(0, 10))
        assertEquals(0, proc.evStallTimer)
    }
}
