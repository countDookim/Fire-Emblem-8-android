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

    private class Rec : EventEngineHooks {
        var blend = 0
        var chapter = 0
        var locks = 0
        override fun blendY(): Int = blend
        override fun chapterIndex(): Int = chapter
        override fun lockGame() {
            locks++
        }
    }

    private fun fresh(rec: Rec = Rec()): Triple<ProcEngine, EventEngine, EventState> {
        val p = ProcEngine()
        p.init()
        val st = EventState()
        return Triple(p, EventEngine(p, st, mem, rec), st)
    }

    @Test
    fun `callEvent enqueues while a std engine runs`() {
        val (p, e, st) = fresh()
        e.callEvent(base, 3)
        e.callEvent(base + 4, 2)
        assertEquals(1, p.count(e.stdScript))
        assertEquals(base + 4, st.callQueue[0])
        assertEquals(0x102, st.callQueue[1])
        assertEquals(0, st.callQueue[2])
    }

    @Test
    fun `engine end starts the queued event and shifts the queue`() {
        val (p, e, st) = fresh()
        val first = e.create(base, 3)
        e.callEvent(base + 4, 3)
        e.callEvent(base + 6, 3)
        p.end(first.proc)
        assertEquals(1, p.count(e.stdScript))
        assertEquals(base + 4, (e.findStd()!!.state as EventEngineProc).pEventStart)
        assertEquals(base + 6, st.callQueue[0])
        assertEquals(0x103, st.callQueue[1])
        assertEquals(0, st.callQueue[2])
        assertEquals(0, st.callQueue[3])
    }

    @Test
    fun `abort bit keeps the queue`() {
        val (p, e, st) = fresh()
        val first = e.create(base, 3)
        e.callEvent(base + 4, 3)
        first.evStateBits = first.evStateBits or EventConst.EV_STATE_ABORT
        p.end(first.proc)
        assertNull(e.findStd())
        assertEquals(base + 4, st.callQueue[0])
        assertEquals(0x103, st.callQueue[1])
    }

    @Test
    fun `create sets the C field values`() {
        val rec = Rec().apply { blend = 0x10; chapter = 7 }
        val (_, e, _) = fresh(rec)
        val a = e.create(base, EventConst.EV_EXEC_CUTSCENE)
        assertEquals(-1, a.overwrittenTextSpeed)
        assertEquals(7, a.chapterIndex)
        assertEquals(base, a.pEventIdk)
        assertEquals(EventConst.EV_STATE_FADEDIN, a.evStateBits)
        assertEquals(1, rec.locks)
        val b = e.create(base, EventConst.EV_EXEC_WORLDMAP)
        assertEquals(0xFF, b.chapterIndex)
        assertEquals(1, rec.locks)
        rec.blend = 0
        assertEquals(0, e.create(base, EventConst.EV_EXEC_QUIET).evStateBits)
    }

    @Test
    fun `createBattle sets the C field values`() {
        val (_, e, _) = fresh()
        val b = e.createBattle(base)
        assertEquals(-1, b.overwrittenTextSpeed)
        assertEquals(EventConst.EV_EXEC_QUIET, b.execType)
        assertEquals(EventConst.EV_STATE_ABORT, b.evStateBits)
        assertEquals(base, b.pEventCurrent)
        assertEquals(true, e.battleEventEngineExists())
        assertEquals(false, e.eventEngineExists())
    }

    @Test
    fun `deleteEventEngines ends both`() {
        val (_, e, _) = fresh()
        e.create(base, 3)
        e.createBattle(base)
        e.deleteEventEngines()
        assertEquals(false, e.eventEngineExists())
        assertEquals(false, e.battleEventEngineExists())
    }
}
