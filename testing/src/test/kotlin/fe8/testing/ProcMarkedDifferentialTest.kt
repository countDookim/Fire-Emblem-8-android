package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gba.testkit.WatchTrace
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential tests of the proc.c helpers that act on whole sets of procs, against the per-frame
 * `procs` watch (the whole `sProcArray` after every emulated frame) of the Prologue runs and the
 * first RAM dump of `ram_trace_boot`.
 *
 * Each test seeds a [ProcEngine] with the array as dumped just before the game's call, runs our
 * function the way the game's caller does, and compares the affected procs with the next frame's
 * dump:
 * - `Proc_BlockEachMarked` / `Proc_UnblockEachMarked`: `BMapDispSuspend` / `BMapDispResume`
 *   (`Proc_BlockEachMarked(1)`), `ram_trace_prologue_talk`.
 * - `Proc_EndEachMarked`: `sub_80B9810` ending the world map procs (`PROC_MARK_WMSTUFF`),
 *   `ram_trace_prologue_talk`.
 * - `Proc_SetMark`: `ScriptBattleDeamon` and `EventBattle`, `ram_trace_prologue_battle`.
 * - `Proc_ForEach`: `StartFaceFadeOut` over `gProcScr_E_FACE`, `ram_trace_prologue_talk`.
 * - `Proc_Init`: the proc array, allocation list and root array in the first dump of
 *   `ram_trace_boot`.
 */
class ProcMarkedDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val names = ProcArrayDump.romNames()

    /** The array after the emulated frame [step], as the dump the call at [step] left behind. */
    private fun at(trace: WatchTrace, step: Int) = ProcArrayDump.ofWatch(trace, step, names)

    @Test
    @VerifiesPort("Proc_BlockEachMarked", "Proc_UnblockEachMarked")
    fun `BMapDispSuspend and BMapDispResume block and unblock the marked procs`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val trace = ref.run("ram_trace_prologue_talk").watch("procs")
        // Record 9321 (block) and 9496 (unblock): sProc_BMVSync and gProc_MapTask, mark 1.
        for ((index, delta) in listOf(BLOCK_RECORD to 1, UNBLOCK_RECORD to -1)) {
            val step = trace.firstStep + index
            val before = at(trace, step - 1)
            val after = at(trace, step)
            val engine = seed(before)
            val marked = before.live.filter { it.u8(MARK) == DISP_MARK }
            assertEquals(2, marked.size, "two procs carry mark 1")
            if (delta > 0) {
                engine.blockEachMarked(DISP_MARK)
            } else {
                engine.unblockEachMarked(DISP_MARK)
            }
            for (p in marked) {
                assertEquals(p.u8(LOCK) + delta, after.live.first { it.slot == p.slot }.u8(LOCK))
            }
            assertMatches(engine, before, after, "step $step") { slot ->
                marked.any { it.slot == slot }
            }
        }
    }

    @Test
    @VerifiesPort("Proc_EndEachMarked")
    fun `the world map end ends every proc marked WMSTUFF`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val trace = ref.run("ram_trace_prologue_talk").watch("procs")
        val step = trace.firstStep + WORLD_MAP_END_RECORD
        val before = at(trace, step - 1)
        val after = at(trace, step)
        val engine = seed(before)
        val marked = before.live.count { it.u8(MARK) == WMSTUFF_MARK }
        assertTrue(marked >= 10, "the world map end ends many procs, found $marked")
        engine.endEachMarked(WMSTUFF_MARK)
        assertTrue(after.live.none { it.u8(MARK) == WMSTUFF_MARK })
        val ended = before.live.filter { it.u8(MARK) == WMSTUFF_MARK }.map { it.slot }
        assertMatches(engine, before, after, "step $step") { it in ended }
    }

    @Test
    @VerifiesPort("Proc_SetMark")
    fun `the scripted battle sets and restores the event engine's mark`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        // eventscr.c: Proc_SetMark(proc, PROC_MARK_EVENT_ANIM) at record 14429 of the battle run,
        // then Proc_SetMark(proc->evtproc, PROC_MARK_EVENT) at record 15074 of the battle_end run.
        val calls = listOf(
            Triple("ram_trace_prologue_battle", SET_ANIM_RECORD, EVENT_ANIM_MARK),
            Triple("ram_trace_prologue_battle_end", SET_EVENT_RECORD, EVENT_MARK),
        )
        for ((runName, index, mark) in calls) {
            val trace = ref.run(runName).watch("procs")
            val step = trace.firstStep + index
            val before = at(trace, step - 1)
            val after = at(trace, step)
            val engine = seed(before)
            val event = before.live.first { it.script == STD_EVENT_ENGINE }
            val old = if (mark == EVENT_ANIM_MARK) EVENT_MARK else EVENT_ANIM_MARK
            assertEquals(old, event.u8(MARK))
            engine.setMark(engine.procs[event.slot], mark)
            assertEquals(mark, after.live.first { it.slot == event.slot }.u8(MARK))
            assertMatches(engine, before, after, "step $step", locks = false) { it == event.slot }
        }
    }

    @Test
    @VerifiesPort("Proc_ForEach")
    fun `fading out the faces visits exactly the face procs`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val trace = ref.run("ram_trace_prologue_talk").watch("procs")
        // StartFaceFadeOut starts gProcScr_FaceEndIn8Frames with `target` (+0x54) the face visited.
        val step = (trace.firstStep + 1..trace.lastStep).first { n ->
            at(trace, n).findAll(FACE_END).size == 2 && at(trace, n - 1).findAll(FACE_END).isEmpty()
        }
        val before = at(trace, step - 1)
        val after = at(trace, step)
        val engine = seed(before)
        val faceScript = engine.procs[before.live.first { it.script == E_FACE }.slot].script!!
        val expected = after.findAll(FACE_END).map {
            (it.u32(TARGET) - ProcArrayDump.ARRAY_ADDRESS) / PROC_SIZE
        }
        assertEquals(2, before.findAll(E_FACE).size)
        val visited = ArrayList<Int>()
        engine.forEach(faceScript, ProcFunc { visited += it.slot })
        assertEquals(expected.sorted(), visited.sorted(), "faces faded at step $step")
    }

    @Test
    @VerifiesPort("Proc_Init")
    fun `Proc_Init leaves the array, allocation list and roots as the game's boot does`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_boot")
        val ew = run.region("r00010", "ewram") // before the first proc starts at f20
        val dump = ProcArrayDump.ofEwram(ew)
        assertEquals(0, dump.live.size, "no proc has started yet")
        // Dirty an engine thoroughly, then initialise it.
        val engine = ProcEngine()
        val script = ProcScript("dirty", listOf(ProcScripts.yield(), ProcScripts.end()))
        val parent = engine.start(script, 3)
        engine.start(script, parent)
        for (p in engine.procs) {
            p.mark = 9; p.lockCnt = 2; p.sleepTime = 5; p.name = "x"; p.endCb = ProcFunc { }
            p.idleCb = ProcFunc { }
        }
        engine.init()
        for (p in engine.procs) {
            val o = ProcArrayDump.ARRAY_ADDRESS - EWRAM + p.slot * PROC_SIZE
            assertEquals(0, u32(ew, o), "script ${p.slot}")
            assertTrue(p.script == null && p.scrScript == null && p.scrCur == 0)
            assertTrue(p.endCb == null && p.idleCb == null && p.name == null)
            assertTrue(p.parent == null && p.child == null && p.next == null && p.prev == null)
            assertEquals(u16(ew, o + 0x24), p.sleepTime and 0xFFFF)
            assertEquals(ew[o + 0x26].toInt(), p.mark)
            assertEquals(ew[o + 0x27].toInt(), p.flags)
            assertEquals(ew[o + 0x28].toInt(), p.lockCnt)
        }
        for (i in 0 until 8) {
            assertEquals(0, u32(ew, ROOTS - EWRAM + 4 * i))
            assertTrue(engine.root(i) == null, "root $i")
        }
        // sProcAllocList holds &sProcArray[0..63], then NULL; the head points at its first entry.
        assertEquals(ALLOC_LIST, u32(ew, ALLOC_HEAD - EWRAM))
        for (i in 0 until 64) {
            val ptr = u32(ew, ALLOC_LIST - EWRAM + 4 * i)
            val slot = (ptr - ProcArrayDump.ARRAY_ADDRESS) / PROC_SIZE
            assertEquals(ProcArrayDump.ARRAY_ADDRESS + slot * PROC_SIZE, ptr)
            assertEquals(slot, engine.start(script, 0).slot, "allocation $i")
        }
        assertEquals(0, u32(ew, ALLOC_LIST - EWRAM + 4 * 64))
    }

    /**
     * A [ProcEngine] whose live procs mirror [dump]: script identity per ROM address, mark, lock
     * count, flags and the tree links. Ours are allocated in slot order, so the dummies of the
     * free slots are ended again.
     */
    private fun seed(dump: ProcArrayDump): ProcEngine {
        val engine = ProcEngine()
        val dummy = ProcScript("dummy", listOf(ProcScripts.yield(), ProcScripts.end()))
        val top = dump.live.maxOf { it.slot }
        val started = (0..top).map { engine.start(dummy, 0) }
        val live = dump.live.associateBy { it.slot }
        for (slot in 0..top) if (slot !in live) engine.end(started[slot])
        val scripts = HashMap<Int, ProcScript>()
        fun link(address: Int): Proc? =
            if (address < ProcArrayDump.ARRAY_ADDRESS) null
            else engine.procs[(address - ProcArrayDump.ARRAY_ADDRESS) / PROC_SIZE]
        for ((slot, d) in live) {
            val p = engine.procs[slot]
            p.script = scripts.getOrPut(d.script) {
                ProcScript("%08x".format(d.script), dummy.cmds)
            }
            p.mark = d.u8(MARK)
            p.lockCnt = d.u8(LOCK)
            p.flags = d.u8(0x27)
            p.parent = link(d.u32(PARENT))
            p.rootIndex = if (p.parent == null) d.u32(PARENT) else -1
            p.child = link(d.u32(0x18))
            p.next = link(d.u32(0x1C))
            p.prev = link(d.u32(0x20))
            p.endCb = null
            p.idleCb = null
        }
        return engine
    }

    /**
     * Compares the slots our call may touch ([touched]) with [after]; every other live slot must
     * be unchanged in ours wherever the game's dump left it unchanged too (other code runs in the
     * same frame).
     */
    private fun assertMatches(
        engine: ProcEngine,
        before: ProcArrayDump,
        after: ProcArrayDump,
        what: String,
        locks: Boolean = true,
        touched: (Int) -> Boolean,
    ) {
        val live = after.live.associateBy { it.slot }
        val was = before.live.associateBy { it.slot }
        for (p in engine.procs) {
            val d = live[p.slot]
            val b = was[p.slot]
            val same = (d == null) == (b == null) &&
                (d == null || (d.u8(MARK) == b!!.u8(MARK) && d.u8(LOCK) == b.u8(LOCK)))
            if (d != null && !locks && touched(p.slot)) {
                assertEquals(d.u8(MARK), p.mark, "$what: slot ${p.slot} mark")
                continue
            }
            if (!touched(p.slot) && !same) continue
            assertEquals(d != null, p.script != null, "$what: slot ${p.slot} live")
            if (d != null) {
                assertEquals(d.u8(MARK), p.mark, "$what: slot ${p.slot} mark")
                assertEquals(d.u8(LOCK), p.lockCnt, "$what: slot ${p.slot} lockCnt")
            }
        }
    }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    private companion object {
        const val EWRAM = 0x02000000
        const val PROC_SIZE = ProcArrayDump.PROC_SIZE
        const val MARK = 0x26
        const val LOCK = 0x28
        const val PARENT = 0x14
        const val TARGET = 0x54
        const val DISP_MARK = 1
        const val WMSTUFF_MARK = 8
        const val EVENT_MARK = 6
        const val EVENT_ANIM_MARK = 7
        const val BLOCK_RECORD = 9321
        const val UNBLOCK_RECORD = 9496
        const val WORLD_MAP_END_RECORD = 9309
        const val SET_ANIM_RECORD = 14429
        const val SET_EVENT_RECORD = 15074
        const val STD_EVENT_ENGINE = 0x08591AC0
        const val E_FACE = 0x08591154
        const val FACE_END = 0x08591234
        const val ALLOC_LIST = 0x02026968
        const val ALLOC_HEAD = 0x02026A6C
        const val ROOTS = 0x02026A70
    }
}
