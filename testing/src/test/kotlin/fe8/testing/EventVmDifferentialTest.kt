package fe8.testing

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_END
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventConst.EV_STATE_GFXLOCKED
import fe8.game.event.EventConst.EV_STATE_0008
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import java.io.File
import java.util.TreeMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the ported core event commands and engine against the mGBA
 * `ram_trace_prologue` trace. A trace step is one emulated frame and `EventEngine_OnUpdate` runs
 * commands until one yields, so a step pair (A, B) is a run of commands. For each pair the engine
 * state of A is loaded into a real [EventEngine] (real `EventEngine_Create` then the fields
 * overwritten from A), `EventEngine.onUpdate` runs once with the ported handlers and the result
 * is compared with B: pc, `pEventIdk`, `evStateBits`, `evStallTimer`, `execType`, slots 1-13,
 * the slot queue, the slot counter, the active queue and the flag bitfields. Slot 0 is not
 * compared (`OnUpdate` zeroes it and other code may write it).
 *
 * Commands without a ported handler are test-only stubs ([STUBS]). A stub completes its command
 * only when B shows the pc moved past it, with the return code the C handler gives; when B is
 * still on the command the stub returns `EVC_STOP_YIELD` (the command is parked) and the pair is
 * compared as is. A command that is neither ported nor stubbed ends the pair as skipped, unless
 * B is still parked on it. Pairs where the engine is absent in A or where B is parked exactly
 * where A was although the commands moved the pc (the engine idled: fade or callback early
 * return) are skipped.
 *
 * Assumptions of the ported handlers (documented in `EventScrCore`): division and modulo by 0
 * give 0, shifts by 32 or more give 0, `Event09_Goto` scans at most 0x10000 words for its label,
 * slot indices above 13 address `gEventSlotQueue`. The replay reports every pair that reaches
 * one ("assumption hits" in the printed coverage); a hit that disagrees with the trace is a
 * mismatch of that handler.
 */
class EventVmDifferentialTest {
    @Test
    @VerifiesPort("EventEngine_OnUpdate")
    fun `onUpdate drives the engine state to the trace`() {
        val r = replay() ?: return
        assertTrue(r.onUpdate.pairs >= MIN_ON_UPDATE_PAIRS, "only ${r.onUpdate.pairs} pairs")
        assertTrue(r.onUpdate.mismatches.isEmpty(), "mismatches: ${r.onUpdate.mismatches.take(5)}")
    }

    @Test
    @VerifiesPort("EventEngine_Create")
    fun `created engine starts at the script with clean state`() {
        val r = replay() ?: return
        assertTrue(r.create.pairs >= MIN_CREATE_PAIRS, "only ${r.create.pairs} creations")
        assertTrue(r.create.mismatches.isEmpty(), "mismatches: ${r.create.mismatches.take(5)}")
    }

    @Test
    @VerifiesPort("Event01_End")
    fun `Event01 End`() = assertCode(0x01)

    @Test
    @VerifiesPort("Event02_EvBitAndIdMod")
    fun `Event02 EvBitAndIdMod`() = assertCode(0x02)

    // Not @VerifiesPort: only sub 0 (EvBit check) runs; a broken sub 1 (flag check) survives.
    @Test
    fun `Event03 CheckEvBitOrId`() = assertCode(0x03)

    @Test
    @VerifiesPort("Event05_SetSlot")
    fun `Event05 SetSlot`() = assertCode(0x05)

    // Not @VerifiesPort: the 3 traced calls prove the slot write only; broken ADD/SUB/MUL/DIV/MOD/
    // AND/OR/XOR/LSL/LSR all survive.
    @Test
    fun `Event06 SlotOperation`() = assertCode(0x06)

    @Test
    @VerifiesPort("Event07_SlotQueueOperations")
    fun `Event07 SlotQueueOperations`() = assertCode(0x07)

    @Test
    @VerifiesPort("Event08_Label")
    fun `Event08 Label`() = assertCode(0x08)

    @Test
    @VerifiesPort("Event09_Goto")
    fun `Event09 Goto`() = assertCode(0x09)

    @Test
    @VerifiesPort("Event0A_Call")
    fun `Event0A Call`() = assertCode(0x0A)

    @Test
    @VerifiesPort("Event0C_Branch")
    fun `Event0C Branch`() = assertCode(0x0C)

    @Test
    @VerifiesPort("Event0E_STAL")
    fun `Event0E STAL`() = assertCode(0x0E)

    @Test
    fun `coverage of the replay does not silently drop`() {
        val r = replay() ?: return
        r.print()
        assertTrue(r.compared >= MIN_COMPARED, "only ${r.compared} fully compared pairs")
        for ((code, min) in MIN_STUB_PAIRS) {
            val n = r.stubCompleted[code] ?: 0
            assertTrue(n >= min, "stub %02X completed in only %d pairs".format(code, n))
        }
    }

    private fun assertCode(code: Int) {
        val r = replay() ?: return
        val s = r.codes[code]
        val min = MIN_CODE_PAIRS.getValue(code)
        val pairs = s?.pairs ?: 0
        assertTrue(s != null && pairs >= min, "code %02X in only %d pairs".format(code, pairs))
        val first = s!!.mismatches.take(5)
        assertEquals(emptyList<String>(), first, "code %02X mismatches".format(code))
    }

    private fun replay(): Result? {
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val trace = EventTrace.prologue(ref)
        assumeTrue(trace != null, "ram_trace_prologue watches not present")
        return cached ?: Replayer(romFile.readBytes(), trace!!).run().also { cached = it }
    }

    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"


    /** Mismatch list and pair count of one checked unit (a handler code or an engine function). */
    private class Stats {
        var pairs = 0
        var calls = 0
        val mismatches = ArrayList<String>()
    }

    /** Outcome of one replay over the whole trace. */
    private class Result {
        val codes = TreeMap<Int, Stats>()
        val onUpdate = Stats()
        val create = Stats()
        val stubCompleted = TreeMap<Int, Int>()
        val stubParked = TreeMap<Int, Int>()
        val unportedHist = TreeMap<Int, Int>()
        val assumptionHits = ArrayList<String>()
        var pairs = 0
        var compared = 0
        var skippedNoEngine = 0
        var skippedIdle = 0
        var skippedUnported = 0
        var skippedAmbiguous = 0
        var createSkipped = 0

        fun print() {
            println(
                "EventVm pairs=$pairs compared=$compared skippedNoEngine=$skippedNoEngine " +
                    "skippedIdle=$skippedIdle skippedUnported=$skippedUnported " +
                    "skippedAmbiguous=$skippedAmbiguous",
            )
            println(
                "EventVm engine EventEngine_OnUpdate pairs=${onUpdate.pairs} " +
                    "mismatches=${onUpdate.mismatches.size}",
            )
            println(
                "EventVm engine EventEngine_Create pairs=${create.pairs} " +
                    "skipped=$createSkipped mismatches=${create.mismatches.size}",
            )
            for ((c, s) in codes) {
                println(
                    "EventVm code %02X pairs=%d calls=%d mismatches=%d"
                        .format(c, s.pairs, s.calls, s.mismatches.size),
                )
            }
            val done = stubCompleted.map { "%02X=%d".format(it.key, it.value) }
            println("EventVm stub completed: $done")
            println("EventVm stub parked: ${stubParked.map { "%02X=%d".format(it.key, it.value) }}")
            println(
                "EventVm unported stop codes: " +
                    unportedHist.map { "%02X=%d".format(it.key, it.value) },
            )
            println("EventVm assumption hits (${assumptionHits.size}): ${assumptionHits.take(10)}")
            onUpdate.mismatches.take(10).forEach { println("EventVm mismatch $it") }
            create.mismatches.take(10).forEach { println("EventVm create mismatch $it") }
        }
    }

    /** Replays the trace pairs on a real [EventEngine] with ported handlers and stubs. */
    private class Replayer(rom: ByteArray, private val trace: EventTrace) {
        private val mem = ScriptMemory { a ->
            val o = a - 0x08000000
            (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
        }
        private val state = EventState()
        private val flags = fe8.game.flags.Fe8EventFlags.create()
        private val hooks = object : EventEngineHooks {
            var fakeBlendY = 0
            override fun blendY(): Int = fakeBlendY
        }
        private val procs = ProcEngine()
        private val engine = EventEngine(procs, state, mem, hooks)
        private val result = Result()

        // Per-pair scratch, reset before each onUpdate.
        private var curB: EventSnapshot? = null
        private val ran = ArrayList<Int>()
        private val completedStubs = ArrayList<Int>()
        private var parkedStub = -1
        private var unportedCode = -1
        private var ambiguous = false
        private var idleStub = false
        private var ended = false

        init {
            procs.init()
            val core = EventScrCore(state, mem, object : fe8.game.event.EventInput {}, flags)
            val ported = mapOf(
                0x00 to core.event00Null,
                0x01 to core.event01End,
                0x02 to core.event02EvBitAndIdMod,
                0x03 to core.event03CheckEvBitOrId,
                0x05 to core.event05SetSlot,
                0x06 to core.event06SlotOperation,
                0x07 to core.event07SlotQueueOperations,
                0x08 to core.event08Label,
                0x09 to core.event09Goto,
                0x0A to core.event0aCall,
                0x0C to core.event0cBranch,
                0x0E to core.event0eStal,
                0x0F to core.event0fCounterOps,
            )
            for (code in 0..0xFF) {
                val h = ported[code]
                val stub = STUBS[code]
                engine.register(
                    code,
                    when {
                        h != null -> recording(code, h)
                        stub != null -> stubbed(code, stub)
                        else -> EventHandler {
                            unportedCode = code
                            EVC_STOP_YIELD
                        }
                    },
                )
            }
        }

        private fun recording(code: Int, h: EventHandler) = EventHandler { p ->
            ran += code
            checkAssumptions(code, p)
            val r = h.run(p)
            if (r == EVC_END) ended = true
            r
        }

        private fun stubbed(code: Int, fn: (EventEngineProc, ScriptMemory, EventState) -> Int) =
            EventHandler { p ->
                val eb = curB?.engine
                when {
                    eb == null -> {
                        ambiguous = true
                        EVC_STOP_YIELD
                    }
                    eb.pEventCurrent == p.pEventCurrent -> {
                        // A command whose C handler never returns STOP_YIELD cannot be parked:
                        // the engine did not run it this frame (idle frame, not modelled).
                        if (code in CAN_PARK) parkedStub = code else idleStub = true
                        EVC_STOP_YIELD
                    }
                    else -> {
                        completedStubs += code
                        fn(p, mem, state)
                    }
                }
            }

        private fun checkAssumptions(code: Int, p: EventEngineProc) {
            val cur = p.pEventCurrent
            val w = mem.u16(cur)
            val arg = mem.u16(cur + 2)
            fun hit(what: String) {
                result.assumptionHits += "%02X at %08X: %s".format(code, cur, what)
            }
            when (code) {
                0x06 -> {
                    val b = state.slots.getOrElse((arg shr 8) and 0xF) { 0 }
                    val sub = w and 0xF
                    if ((sub == 3 || sub == 4) && b == 0) hit("div/mod by 0")
                    if ((sub == 8 || sub == 9) && (b and 0xFF) >= 32) hit("shift >= 32")
                    val idx = intArrayOf(arg and 0xF, (arg shr 4) and 0xF, (arg shr 8) and 0xF)
                    if (idx.any { it > 13 }) hit("slot index > 13")
                }
                0x05, 0x07 -> if (arg > 13) hit("slot index > 13")
            }
        }

        fun run(): Result {
            var prev: EventSnapshot? = null
            for (step in trace.steps) {
                val b = trace.at(step)
                val a = prev
                prev = b
                if (a == null) continue
                result.pairs++
                val ea = a.engine
                if (ea == null || ea.pEventCurrent == 0) {
                    if (ea == null && b.engine != null) replayCreate(b)
                    result.skippedNoEngine++
                    continue
                }
                replayUpdate(a, ea, b)
            }
            return result
        }

        /** The creation frame: B holds the fresh proc before its first update. */
        private fun replayCreate(b: EventSnapshot) {
            val eb = b.engine!!
            if (eb.pEventCurrent != eb.pEventStart || eb.pEventStart == 0) {
                result.createSkipped++
                return
            }
            procs.init()
            hooks.fakeBlendY = if (eb.evStateBits and EV_STATE_FADEDIN != 0) 0x10 else 0
            val p = engine.create(eb.pEventStart, eb.execType)
            result.create.pairs++
            val d = ArrayList<String>()
            fun chk(name: String, got: Int, want: Int) {
                if (got != want) d += "$name got %X want %X".format(got, want)
            }
            chk("pEventStart", p.pEventStart, eb.pEventStart)
            chk("pEventIdk", p.pEventIdk, eb.pEventIdk)
            chk("pEventCurrent", p.pEventCurrent, eb.pEventCurrent)
            chk("evStateBits", p.evStateBits, eb.evStateBits)
            chk("evStallTimer", p.evStallTimer, eb.evStallTimer)
            chk("execType", p.execType, eb.execType)
            if (d.isNotEmpty()) result.create.mismatches += "step ${b.step}: $d"
            hooks.fakeBlendY = 0
        }

        private fun replayUpdate(a: EventSnapshot, ea: EngineState, b: EventSnapshot) {
            loadState(a)
            flags.copyIn(a.permanentFlags!! + a.chapterFlags!!)
            procs.init()
            hooks.fakeBlendY = 0
            val proc = engine.create(ea.pEventStart, ea.execType)
            proc.pEventIdk = ea.pEventIdk
            proc.pEventCurrent = ea.pEventCurrent
            proc.evStateBits = ea.evStateBits
            proc.evStallTimer = ea.evStallTimer
            ran.clear()
            completedStubs.clear()
            parkedStub = -1
            unportedCode = -1
            ambiguous = false
            idleStub = false
            ended = false
            curB = b
            engine.onUpdate(proc)
            val eb = b.engine
            if (ambiguous) {
                result.skippedAmbiguous++
                return
            }
            if (idleStub) {
                result.skippedIdle++
                return
            }
            if (unportedCode >= 0 && (eb == null || eb.pEventCurrent != proc.pEventCurrent)) {
                result.skippedUnported++
                result.unportedHist.merge(unportedCode, 1, Int::plus)
                return
            }
            // B parked exactly where A was although the commands moved the pc: the engine did not
            // run that frame (fade/callback early return in OnUpdate, not modelled), so skip.
            if (eb != null && eb.pEventCurrent == ea.pEventCurrent &&
                eb.pEventIdk == ea.pEventIdk && proc.pEventCurrent != ea.pEventCurrent
            ) {
                result.skippedIdle++
                return
            }
            result.compared++
            val diffs = compare(proc, b)
            val tag = "step ${b.step} ran ${ran.map { "%02X".format(it) }} stubs " +
                "${completedStubs.map { "%02X".format(it) }} at %08X".format(ea.pEventCurrent) +
                " parked=%d unported=%d".format(parkedStub, unportedCode)
            result.onUpdate.pairs++
            if (diffs.isNotEmpty()) result.onUpdate.mismatches += "$tag: $diffs"
            for (code in (ran + completedStubs).toSet()) {
                val s = result.codes.getOrPut(code) { Stats() }
                s.pairs++
                s.calls += ran.count { it == code }
                if (diffs.isNotEmpty()) s.mismatches += "$tag: $diffs"
            }
            for (code in completedStubs.toSet()) result.stubCompleted.merge(code, 1, Int::plus)
            if (parkedStub >= 0) result.stubParked.merge(parkedStub, 1, Int::plus)
        }

        private fun loadState(a: EventSnapshot) {
            a.slots.copyInto(state.slots)
            a.slotQueue.copyInto(state.slotQueue)
            state.slotCounter = a.slotCounter
            a.callQueue.forEachIndexed { i, e ->
                state.callQueue[2 * i] = e.events
                state.callQueue[2 * i + 1] = (e.execType and 0xFF) or ((e.isUsed and 0xFF) shl 8)
            }
            a.activeQueue.forEachIndexed { i, e ->
                state.activeQueue[2 * i] = e.evt1
                state.activeQueue[2 * i + 1] = e.evt2
            }
        }

        private fun compare(proc: EventEngineProc, b: EventSnapshot): List<String> {
            val d = ArrayList<String>()
            val out = ByteArray(0x30)
            flags.copyOut(out)
            if (!out.contentEquals(b.permanentFlags!! + b.chapterFlags!!)) d += "flags differ"
            val eb = b.engine
            if (eb == null) {
                // After EVC_END the proc is broken out of its repeat loop; it is removed a frame
                // or two later, so an absent engine is only an error when the run did not end.
                if (!ended) d += "engine gone in B, not ended"
            } else {
                fun chk(name: String, got: Int, want: Int) {
                    if (got != want) d += "$name got %X want %X".format(got, want)
                }
                chk("pEventCurrent", proc.pEventCurrent, eb.pEventCurrent)
                chk("pEventIdk", proc.pEventIdk, eb.pEventIdk)
                chk("evStateBits", proc.evStateBits, eb.evStateBits)
                chk("evStallTimer", proc.evStallTimer, eb.evStallTimer)
                chk("execType", proc.execType, eb.execType)
            }
            for (i in 1 until EventState.SLOT_COUNT) {
                if (state.slots[i] != b.slots[i]) {
                    d += "slot$i got %X want %X".format(state.slots[i], b.slots[i])
                }
            }
            if (!state.slotQueue.contentEquals(b.slotQueue)) d += "slotQueue differs"
            if (state.slotCounter != b.slotCounter) {
                d += "counter got %X want %X".format(state.slotCounter, b.slotCounter)
            }
            b.activeQueue.forEachIndexed { i, e ->
                if (state.activeQueue[2 * i] != e.evt1 || state.activeQueue[2 * i + 1] != e.evt2) {
                    d += "activeQueue[$i] got %X,%X want %X,%X".format(
                        state.activeQueue[2 * i], state.activeQueue[2 * i + 1], e.evt1, e.evt2,
                    )
                }
            }
            return d
        }
    }

    private companion object {
        const val MIN_ON_UPDATE_PAIRS = 12000
        const val MIN_CREATE_PAIRS = 2
        const val MIN_COMPARED = 12000

        /** Minimum compared pairs per ported handler code. */
        val MIN_CODE_PAIRS = mapOf(
            0x01 to 14, 0x02 to 3, 0x03 to 8, 0x05 to 7, 0x06 to 3, 0x07 to 3, 0x08 to 7,
            0x09 to 1, 0x0A to 9, 0x0C to 8, 0x0E to 2400,
        )

        /** Minimum pairs in which each stub completed its command. */
        val MIN_STUB_PAIRS = mapOf(
            0x12 to 3, 0x15 to 2, 0x1B to 20, 0x1D to 28, 0x30 to 20, 0x3B to 24, 0xAC to 3,
            0xBA to 7,
        )

        private var cached: Result? = null

        /** Stub codes whose C handler can return `EVC_STOP_YIELD` (the command waits). */
        val CAN_PARK = setOf(0x1D, 0x2F, 0x30, 0x34, 0xA5, 0xAC, 0xB1, 0xB3, 0xBA, 0xBE)

        private const val CONT = EVC_ADVANCE_CONTINUE
        private const val YIELD = EVC_ADVANCE_YIELD

        private fun skipping(p: EventEngineProc) = p.evStateBits and EV_STATE_SKIPPING != 0

        /**
         * Test-only stubs for unported commands. A stub runs only when B's pc moved past the
         * command (otherwise it parks with `EVC_STOP_YIELD`); its return code is the one the C
         * handler gives for a command that completes, and it applies the `evStateBits` effect
         * where the C handler has one. No stub writes slots, queues, flags, `evStallTimer`,
         * `execType` or `pEventIdk`; a pair where the C handler does is a mismatch.
         *
         * - 0x12 Event12_StartBGM: CONTINUE when skipping, else YIELD (starts a song).
         * - 0x15 Event15_BgmVolume: same.
         * - 0x17 Event17_Fade: skipping: CONTINUE; else YIELD, with FADEDIN cleared for
         *   subcodes 0/2 (FADU, FAWU) and set for 1/3 (FADI, FAWI).
         * - 0x1A Event1A_TEXTSTART: subcodes 0/3/4/5 YIELD; 1/2 run Event23_DisaleMapDisp, which
         *   sets GFXLOCKED and yields. The text teardown the C does when the subcode changes
         *   (EndTalk, face fade) touches no compared state.
         * - 0x1B Event1B_TEXTSHOW: for subcodes 0/1 an argument of 0 (slot 2 when negative)
         *   gives CONTINUE; otherwise subcode 0/1 CONTINUE (subcode 0 first clears bit 0x0008)
         *   and subcode 2 (REMA) clears 0x0008 and YIELDs.
         * - 0x1C Event1D_TalkContinue: YIELD in both branches.
         * - 0x1D Event1D_WaitForLockTalk: completes only with YIELD (STOP_YIELD while talk is
         *   active is the parked case). It also writes `gEventSlots[0xC]` (0 when skipping, else
         *   `GetTalkChoiceResult()`); the stub leaves slot 0xC alone, so a pair where the trace
         *   changes slot 0xC mismatches.
         * - 0x21 Event21_TextBg: skipping: CONTINUE (the sub-case that calls Event22_ClearScreen
         *   and would clear GFXLOCKED is not modelled); else YIELD.
         * - 0x22 Event22_ClearScreen: runs Event24_EnableMapDisp: clears GFXLOCKED, YIELD.
         * - 0x25 Event25_ChangeMap, 0x2C Event2C_LoadUnits: YIELD in every branch.
         * - 0x26 Event26_CameraControl: skipping or FADEDIN: CONTINUE, else YIELD.
         * - 0x2F Event2F_MoveUnit: CONTINUE (a move that cannot start returns STOP_YIELD, which
         *   is the parked case).
         * - 0x30 Event30_ENUN: YIELD (STOP_YIELD while a MU moves is the parked case).
         * - 0x34 Event34_MessWithUnitState: subcode 0xD (KILL) YIELD unless skipping, else
         *   CONTINUE.
         * - 0x3A Event3A_DisplayPopup: skipping: CONTINUE, else YIELD.
         * - 0x3B Event3B_DisplayCursor: YIELD for subcode&3 == 2 (CURE) unless skipping, else
         *   CONTINUE (the cursor proc is started).
         * - 0x3F Event3F_ScriptBattle: subcode 2 (FIGHT_SCRIPT) CONTINUE, else YIELD.
         * - World map codes 0x9E A0 A3 A7 A8 AF B4 B6 B8 B9 BC BD C1 C4 C5: CONTINUE. The waiting
         *   ones 0xA5 (UnitFadeWait), 0xAC (UnitMoveWait), 0xB1, 0xBA, 0xBE and 0xC6 C7 C9: CONTINUE
         *   when skipping, else YIELD (their STOP_YIELD is the parked case). 0xB2, 0xB3: YIELD.
         */
        val STUBS: Map<Int, (EventEngineProc, ScriptMemory, EventState) -> Int> = buildMap {
            fun stub(code: Int, fn: (EventEngineProc, ScriptMemory, EventState) -> Int) {
                put(code, fn)
            }
            val skipOrYield =
                intArrayOf(0x12, 0x15, 0x21, 0x3A, 0xA5, 0xAC, 0xB1, 0xBA, 0xBE, 0xC6, 0xC7, 0xC9)
            for (c in skipOrYield) {
                stub(c) { p, _, _ -> if (skipping(p)) CONT else YIELD }
            }
            for (c in intArrayOf(0x1C, 0x1D, 0x25, 0x2C, 0x30, 0xB2, 0xB3)) {
                stub(c) { _, _, _ -> YIELD }
            }
            for (c in intArrayOf(
                0x2F, 0x9E, 0xA0, 0xA3, 0xA7, 0xA8, 0xAF, 0xB4, 0xB6, 0xB8, 0xB9, 0xBC, 0xBD, 0xC1,
                0xC4, 0xC5,
            )) {
                stub(c) { _, _, _ -> CONT }
            }
            stub(0x17) { p, m, _ ->
                if (skipping(p)) {
                    CONT
                } else {
                    when (m.u16(p.pEventCurrent) and 0xF) {
                        0, 2 -> p.evStateBits = p.evStateBits and EV_STATE_FADEDIN.inv()
                        else -> p.evStateBits = p.evStateBits or EV_STATE_FADEDIN
                    }
                    YIELD
                }
            }
            stub(0x1A) { p, m, _ ->
                if ((m.u16(p.pEventCurrent) and 0xF) in 1..2) {
                    p.evStateBits = p.evStateBits or EV_STATE_GFXLOCKED
                }
                YIELD
            }
            stub(0x1B) { p, m, s ->
                val sub = m.u16(p.pEventCurrent) and 0xF
                var a = m.u16(p.pEventCurrent + 2).toShort().toInt()
                if (sub != 2) {
                    if (a < 0) a = s.slots[2]
                    if (a == 0) return@stub CONT
                }
                if (sub != 1) p.evStateBits = p.evStateBits and EV_STATE_0008.inv()
                if (sub == 2) YIELD else CONT
            }
            stub(0x22) { p, _, _ ->
                p.evStateBits = p.evStateBits and EV_STATE_GFXLOCKED.inv()
                YIELD
            }
            stub(0x26) { p, _, _ ->
                if (skipping(p) || p.evStateBits and EV_STATE_FADEDIN != 0) CONT else YIELD
            }
            stub(0x34) { p, m, _ ->
                if (m.u16(p.pEventCurrent) and 0xF == 0xD && !skipping(p)) YIELD else CONT
            }
            stub(0x3B) { p, m, _ ->
                if (!skipping(p) && (m.u16(p.pEventCurrent) and 3) == 2) YIELD else CONT
            }
            stub(0x3F) { p, m, _ -> if ((m.u16(p.pEventCurrent) and 0xF) == 2) CONT else YIELD }
        }
    }
}
