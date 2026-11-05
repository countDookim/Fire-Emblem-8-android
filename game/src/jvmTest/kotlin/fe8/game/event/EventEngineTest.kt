package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import gba.testkit.AllocationMeter
import gbafe.proc.ProcEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class EventEngineTest {
    private val base = ScriptWords.MOD_BASE

    // 0x0E sleep(3) [stall, len 2 words], 0x20 nop [len 1], 0x09 goto start [len 1]
    private val words = shortArrayOf(
        0x0E20, 3, // sleep 3 frames
        0x2010, // test nop (advance-continue)
        0x0910, // goto: back to 0
        0x0110, // end
        0x3010, // test command 0x30
    )
    private val procs = ProcEngine()
    private val mem = ScriptWords({ error("no rom") }, words)
    private val engine = EventEngine(procs, EventState(), mem)
    private var nops = 0

    init {
        procs.init()
        engine.register(0x0E) { p ->
            val n = mem.u16(p.pEventCurrent + 2)
            if (p.evStallTimer < n) {
                p.evStallTimer++
                EVC_STOP_YIELD
            } else {
                p.evStallTimer = 0
                EVC_ADVANCE_YIELD
            }
        }
        engine.register(0x20) { nops++; EVC_ADVANCE_CONTINUE }
        engine.register(0x09) { p -> p.pEventCurrent = base; EVC_STOP_YIELD }
    }

    @Test
    fun `sleep stalls then advances and goto loops`() {
        val ev = engine.create(base, 1)
        assertNotNull(engine.findStd())
        val pcs = IntArray(12)
        val timers = IntArray(12)
        for (f in pcs.indices) {
            procs.run(ev.proc)
            pcs[f] = ev.pEventCurrent - base
            timers[f] = ev.evStallTimer
        }
        // The proc starts parked at its first yield, so frame 0 is already an update: sleep(3)
        // stalls three frames (timer 1..3, STOP_YIELD), the fourth advances 4 bytes to the nop
        // (ADVANCE_YIELD, timer 0); the next frame runs nop (ADVANCE_CONTINUE, +2) and goto
        // (pc = 0, STOP_YIELD) in one update, then the cycle repeats.
        assertEquals(listOf(0, 0, 0, 4, 0, 0, 0, 0, 4, 0, 0, 0), pcs.toList())
        assertEquals(listOf(1, 2, 3, 0, 0, 1, 2, 3, 0, 0, 1, 2), timers.toList())
        assertEquals(2, nops)
    }

    @Test
    fun `stop continue reruns the same command in the same frame`() {
        var calls = 0
        engine.register(0x30) { _ ->
            calls++
            if (calls == 1) EventConst.EVC_STOP_CONTINUE else EVC_ADVANCE_YIELD
        }
        val ev = engine.create(base + 10, 1)
        procs.run(ev.proc) // the first update
        assertEquals(2, calls)
        assertEquals(base + 12, ev.pEventCurrent)
    }

    @Test
    fun `unknown command ends the frame without advancing`() {
        val e = EventEngine(ProcEngine().also { it.init() }, EventState(), mem)
        val ev = e.create(base + 8, 1) // 0x0110: unregistered
        e.procs.run(ev.proc)
        e.procs.run(ev.proc)
        e.procs.run(ev.proc)
        assertEquals(base + 8, ev.pEventCurrent)
    }

    @Test
    fun `end command breaks the proc`() {
        engine.register(0x01) { EventConst.EVC_END }
        val ev = engine.create(base + 8, 1)
        repeat(6) { procs.run(ev.proc) }
        assertNull(engine.findStd())
    }

    @Test
    fun `update allocates nothing over 1000 frames`() {
        assumeTrue(AllocationMeter.supported)
        val ev = engine.create(base, 1)
        procs.run(ev.proc)
        val bytes = AllocationMeter.measureMin(warmup = 1000, iterations = 1000) {
            procs.run(ev.proc)
        }
        assertEquals(0L, bytes)
    }
}
