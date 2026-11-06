package fe8.testing

import fe8.game.RomView
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_END
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_0008
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventConst.EV_STATE_GFXLOCKED
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.EventUnitState
import fe8.game.event.EventUnitStateHooks
import fe8.game.event.ScriptMemory
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gba.testkit.ReferenceData.Run
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
 * the slot queue, the slot counter, the active queue, the call queue, the flag bitfields, the
 * tutorial fields of `gPlaySt` and, on pairs that ran `Event04_CheckRandom`, `gRNState`.
 * Slot 0 is not compared (`OnUpdate` zeroes it and other code may write it).
 *
 * Commands without a ported handler are test-only stubs ([STUBS]). A stub completes its command
 * only when B shows the pc moved past it, with the return code the C handler gives; when B is
 * still on the command the stub returns `EVC_STOP_YIELD` (the command is parked) and the pair is
 * compared as is. A command that is neither ported nor stubbed ends the pair as skipped, unless
 * B is still parked on it. Pairs where the engine is absent in A or where B is parked exactly
 * where A was although the commands moved the pc (the engine idled: fade or callback early
 * return) are skipped.
 *
 * Unit windows: the unit arrays are seeded from a memory dump and compared with the next dump
 * after the pairs in between ran (655 windows, of which only 8 touch units: the floors are on
 * touched windows, with subs 5, 10 and 15 each in at least one). Only units that `Event34`
 * names (or, for CLEA/CLEN/CLEE, the faction range) are compared, plus any unit whose bytes
 * changed in a window without stubbed unit commands. Masks: +0x3C..0x3F (`pMapSpriteHandle`,
 * written by the sprite refresh) and `US_HIDDEN` (MU code and the sprite refresh toggle it),
 * which hides CLEA's main effect. A window that contains a completed or parked unit stub
 * (0x2C, 0x2F, 0x30, 0x3F: loads, moves, battles) is partial: position bytes (+0x10, +0x11)
 * are not compared and other units are not compared. A window with an unported or ambiguous
 * command is skipped. The `Event34` handler is not credited with `@VerifiesPort` (see the
 * comments at the unit test).
 *
 * `midFrameParking` for 0x34 (B parked on the command means it has not run yet) is a test-side
 * assumption; its reason (a lag frame, since the command refreshes sprites) is unconfirmed.
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

    // Not @VerifiesPort: Event34_MessWithUnitState: only subs 5 (SET_HP), 10 (CLEA) and 15 (DISA)
    // are compared; subs 0-4, 6-8 and 11-14 never reach a compared window, and breaking the CLEA
    // hide / clear-bits loop (US_HIDDEN is masked) survives. SET_HP hp+1, CLEA without
    // clearCutsceneUnits and DISA without clearUnit do fail. GetUnitStructFromEventParameter: only
    // pid > 0 is reached, the special ids are unverified. ClearCutsceneUnits: the US_DEAD branch
    // survives breaks (always clearUnit).
    @Test
    fun `unit arrays match the dumps between replayed event commands`() {
        val r = replay() ?: return
        r.print()
        // Only 8 of the 655 windows touch units; the floor is on those, not on all windows.
        assertTrue(
            r.unitWindowsTouched >= MIN_TOUCHED_UNIT_WINDOWS,
            "only ${r.unitWindowsTouched} windows touched units",
        )
        for (sub in MIN_SUB_WINDOWS) {
            val n = r.subWindows[sub] ?: 0
            assertTrue(n >= 1, "Event34 sub $sub compared in only $n windows")
        }
        assertEquals(emptyList<String>(), r.unitWindows.mismatches.take(5))
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

    // Not @VerifiesPort: only args 0 and 1 run; the skipping branch and args 2-4 survive breaks.
    @Test
    fun `Event10 ModifyEvBit`() = assertCode(0x10)

    // Event12_StartBGM (below Event10): the result code is compared, the song start (sound state)
    // is not, so it is not tagged.
    // Not @VerifiesPort: the skipping branch and the SetDispEnable call survive breaks (the
    // trace has no skipping pair and the display state is not compared); FADU/FADI/FAWx bits and
    // the YIELD result do fail.
    @Test
    fun `Event12 StartBGM`() = assertCode(0x12)

    @Test
    fun `Event17 Fade`() = assertCode(0x17)

    // Not @VerifiesPort: only sub 7 of the 11 subs runs on data.
    @Test
    fun `Event19 Checks`() = assertCode(0x19)

    // Event02 sub 1 sets a permanent flag once in the trace (step 9290); flag bytes are compared
    // on every pair, so this covers SetFlag -> SetPermanentFlag. Check*Flag (Event03 sub 1) and
    // ClearFlag never run on data, and no flag below 100 or 0x84 changes: those stay untagged.
    @Test
    @VerifiesPort("SetFlag", "SetPermanentFlag")
    fun `flag bits changed by Event02 match the trace`() {
        val r = replay() ?: return
        assertTrue(r.flagChange.pairs >= 1, "no pair changed the flag bytes")
        assertEquals(emptyList<String>(), r.flagChange.mismatches.take(5))
    }

    @Test
    @VerifiesPort("Event0A_Call")
    fun `Event0A Call`() = assertCode(0x0A)

    @Test
    @VerifiesPort("Event0C_Branch")
    fun `Event0C Branch`() = assertCode(0x0C)

    @Test
    @VerifiesPort("Event0E_STAL")
    fun `Event0E STAL`() = assertCode(0x0E)

    // Not tagged: CallEvent, EnqueueEventCall, CallNextQueuedEvent, EventEngine_OnEnd. The call
    // queue is compared on every pair but is all zero in the whole trace (no call is ever queued
    // while an engine runs), and the engine ends (steps 9285, 9292, 19312) with an empty queue, so
    // breaking the queue shift in CallNextQueuedEvent, dropping its call from OnEnd, or turning
    // CallEvent into a no-op all survive.
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
        return cached ?: Replayer(romFile.readBytes(), trace!!, ref.run("ram_trace_prologue"))
            .run()
            .also { cached = it }
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
        val flagChange = Stats()
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

        /** Unit-array windows (dump to next dump) compared, skipped as unsafe, and mismatching. */
        val unitWindows = Stats()
        var unitWindowsSkipped = 0
        var unitWindowsTouched = 0
        var unitWindowsPartial = 0
        val skippedWithSubs = ArrayList<String>()
        var unitsCompared = 0

        /** Event34 subcode -> calls in compared pairs, windows compared and windows mismatching. */
        val subCalls = TreeMap<Int, Int>()
        val subRan = TreeMap<Int, Int>()
        val subWindows = TreeMap<Int, Int>()
        val subMismatches = TreeMap<Int, Int>()

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
            println(
                "EventVm unit windows compared=${unitWindows.pairs} " +
                    "partial(stubs)=$unitWindowsPartial withTouchedUnits=$unitWindowsTouched " +
                    "unitsCompared=$unitsCompared " +
                    "skippedUnitStub=$unitWindowsSkipped mismatches=${unitWindows.mismatches.size}",
            )
            println(
                "EventVm Event34 subcode ran(all pairs)=$subRan calls(compared)=$subCalls " +
                    "windows=$subWindows mismatchWindows=$subMismatches",
            )
            println("EventVm unit windows skipped with Event34: $skippedWithSubs")
            unitWindows.mismatches.take(10).forEach { println("EventVm unit mismatch $it") }
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
    private class Replayer(
        rom: ByteArray,
        private val trace: EventTrace,
        private val run: Run,
    ) {
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
        private val rng = gbafe.core.rng.RngService()
        private val playSt = fe8.game.bm.PlaySt()
        private val keys = gbafe.input.KeyStatus()
        private val result = Result()
        private val romView = RomView.ofRom(rom)
        private val table = UnitTable(romView)
        private val loader = UnitLoader(table, romView, { 0 }, rng, { false })
        private val cursorCamera = fe8.game.event.EventCursorCamera(
            state, mem, fe8.game.bm.BmSt(),
        )
        private val unitState = EventUnitState(
            state, mem, table, loader, object : EventUnitStateHooks {},
        )

        // Unit-array window: seeded from a dump, compared against the next one.
        private var windowStubbed = false
        private var windowUnknown = false
        private val windowSubs = HashSet<Int>()
        private val windowTouched = HashSet<Int>()
        private val windowSeed = ByteArray(UnitTable.TOTAL_SIZE)
        private val pairSeed = ByteArray(UnitTable.TOTAL_SIZE)

        // Per-pair scratch, reset before each onUpdate.
        private var curB: EventSnapshot? = null
        private val ran = ArrayList<Int>()
        private val ranSubs = ArrayList<Int>()
        private val completedStubs = ArrayList<Int>()
        private var parkedStub = -1
        private var unportedCode = -1
        private var ambiguous = false
        private var idleStub = false
        private var ended = false

        init {
            procs.init()
            val core = EventScrCore(
                state, mem, object : fe8.game.event.EventInput {}, flags, rng, playSt, keys,
            )
            core.registerAll(engine)
            val fade = fe8.game.event.EventFade(
                mem, gbafe.render.ScreenFade(procs, gbafe.render.LcdControl()),
            )
            val bgm = fe8.game.event.EventBgm(
                state, mem, fe8.game.SoundWrapper(procs, null) { 0 },
            )
            val text = fe8.game.event.EventText(state, mem, NoTextHost)
            val ported = mapOf(
                0x1A to text.event1aTextStart,
                0x1B to text.event1bTextShow,
                0x1C to text.event1dTalkContinue,
                0x00 to core.event00Null,
                0x04 to core.event04CheckRandom,
                0x0B to core.event0bEnqueueCall,
                0x10 to core.event10ModifyEvBit,
                0x11 to core.event11SetIgnoredKeys,
                0x19 to core.event19Checks,
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
                0x12 to bgm.event12StartBgm,
                0x17 to fade.event17Fade,
                0x26 to cursorCamera.event26CameraControl,
                0x34 to unitState.event34MessWithUnitState,
                0x3B to cursorCamera.event3bDisplayCursor,
                0x3C to cursorCamera.event3cMoveCursor,
            )
            for (code in 0..0xFF) {
                val h = ported[code]
                val stub = STUBS[code]
                engine.register(
                    code,
                    when {
                        h != null && code == 0x34 -> midFrameParking(code, h)
                        h != null && code in IDLE_AWARE -> idleAware(code, recording(code, h))
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

        /**
         * A ported command whose frame the trace sampled in the middle of the command loop (assumed
         * a lag frame, as `Event34` refreshes the map and sprites; unconfirmed): when B
         * is parked on the command, the command has not run yet, as with a stubbed command.
         */
        private fun midFrameParking(code: Int, h: EventHandler) = EventHandler { p ->
            val eb = curB?.engine
            if (eb != null && eb.pEventCurrent == p.pEventCurrent) {
                parkedStub = code
                EVC_STOP_YIELD
            } else {
                recording(code, h).run(p)
            }
        }

        private fun recording(code: Int, h: EventHandler) = EventHandler { p ->
            ran += code
            if (code == 0x34) {
                val sub = mem.u16(p.pEventCurrent) and 0xF
                ranSubs += sub
                result.subRan.merge(sub, 1, Int::plus)
                windowSubs += sub
                val arg = mem.u16(p.pEventCurrent + 2).toShort().toInt()
                when (sub) {
                    0xA -> windowTouched += 1 until 0x40
                    0xB -> windowTouched += 0x41 until 0x80
                    0xC -> windowTouched += 0x81 until 0xC0
                    else -> unitState.getUnitStructFromEventParameter(arg).let {
                        if (it >= 0) windowTouched += it
                    }
                }
            }
            checkAssumptions(code, p)
            val r = h.run(p)
            if (r == EVC_END) ended = true
            r
        }

        /**
         * A ported command that the C handler never parks: when B is still on it, the engine did
         * not run it this frame (idle frame, not modelled), so the pair is skipped like a stub's.
         */
        private fun idleAware(code: Int, h: EventHandler) = EventHandler { p ->
            val eb = curB?.engine
            when {
                eb == null -> {
                    ambiguous = true
                    EVC_STOP_YIELD
                }
                eb.pEventCurrent == p.pEventCurrent -> {
                    idleStub = true
                    EVC_STOP_YIELD
                }
                else -> h.run(p)
            }
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
            val dumpSteps = run.dumps.keys.filter { it.startsWith("r") || it.startsWith("f") }
                .associateBy { it.drop(1).toInt() }
            var windowStart = -1
            for (step in trace.steps) {
                val b = trace.at(step)
                val a = prev
                prev = b
                if (a != null) {
                    result.pairs++
                    val ea = a.engine
                    if (ea == null || ea.pEventCurrent == 0) {
                        if (ea == null && b.engine != null) replayCreate(b)
                        result.skippedNoEngine++
                    } else {
                        when (replayUpdate(a, ea, b)) {
                            UNIT_UNKNOWN -> windowUnknown = true
                            UNIT_STUBBED -> windowStubbed = true
                        }
                    }
                }
                val dump = dumpSteps[step] ?: continue
                val ewram = run.region(dump, "ewram")
                if (windowStart >= 0) {
                    if (windowUnknown) {
                        result.unitWindowsSkipped++
                        if (windowSubs.isNotEmpty()) {
                            result.skippedWithSubs += "$windowStart..$step subs $windowSubs"
                        }
                    } else {
                        compareUnits(windowStart, step, ewram, windowStubbed)
                    }
                }
                table.loadFrom(ewram)
                table.bytes.copyInto(windowSeed)
                windowTouched.clear()
                windowStart = step
                windowStubbed = false
                windowUnknown = false
                windowSubs.clear()
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

        /** Replays one pair; [UNIT_OK], [UNIT_STUBBED] or [UNIT_UNKNOWN] for the units' state. */
        private fun replayUpdate(a: EventSnapshot, ea: EngineState, b: EventSnapshot): Int {
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
            ranSubs.clear()
            completedStubs.clear()
            parkedStub = -1
            unportedCode = -1
            ambiguous = false
            idleStub = false
            ended = false
            curB = b
            table.bytes.copyInto(pairSeed)
            val subsBefore = HashSet(windowSubs)
            val touchedBefore = HashSet(windowTouched)
            engine.onUpdate(proc)
            val eb = b.engine
            if (ambiguous) {
                result.skippedAmbiguous++
                return UNIT_UNKNOWN
            }
            if (idleStub) {
                result.skippedIdle++
                // The commands ran here but not in the game: undo their unit effects.
                pairSeed.copyInto(table.bytes)
                windowSubs.retainAll(subsBefore)
                windowTouched.retainAll(touchedBefore)
                return UNIT_OK
            }
            if (unportedCode >= 0 && (eb == null || eb.pEventCurrent != proc.pEventCurrent)) {
                result.skippedUnported++
                result.unportedHist.merge(unportedCode, 1, Int::plus)
                return UNIT_UNKNOWN
            }
            // B parked exactly where A was although the commands moved the pc: the engine did not
            // run that frame (fade/callback early return in OnUpdate, not modelled), so skip.
            if (eb != null && eb.pEventCurrent == ea.pEventCurrent &&
                eb.pEventIdk == ea.pEventIdk && proc.pEventCurrent != ea.pEventCurrent
            ) {
                result.skippedIdle++
                // The commands ran here but not in the game: undo their unit effects.
                pairSeed.copyInto(table.bytes)
                windowSubs.retainAll(subsBefore)
                windowTouched.retainAll(touchedBefore)
                return UNIT_OK
            }
            result.compared++
            val diffs = compare(proc, b)
            val flagsChanged = !(a.permanentFlags!! + a.chapterFlags!!)
                .contentEquals(b.permanentFlags!! + b.chapterFlags!!)
            val tag = "step ${b.step} ran ${ran.map { "%02X".format(it) }} stubs " +
                "${completedStubs.map { "%02X".format(it) }} at %08X".format(ea.pEventCurrent) +
                " parked=%d unported=%d".format(parkedStub, unportedCode)
            result.onUpdate.pairs++
            if (diffs.isNotEmpty()) result.onUpdate.mismatches += "$tag: $diffs"
            if (flagsChanged) {
                result.flagChange.pairs++
                if (diffs.isNotEmpty()) result.flagChange.mismatches += "$tag: $diffs"
            }
            for (code in (ran + completedStubs).toSet()) {
                val s = result.codes.getOrPut(code) { Stats() }
                s.pairs++
                s.calls += ran.count { it == code }
                if (diffs.isNotEmpty()) s.mismatches += "$tag: $diffs"
            }
            for (code in completedStubs.toSet()) result.stubCompleted.merge(code, 1, Int::plus)
            if (parkedStub >= 0) result.stubParked.merge(parkedStub, 1, Int::plus)
            for (sub in ranSubs) result.subCalls.merge(sub, 1, Int::plus)
            val stubbed = completedStubs.any { it in UNIT_STUBS } || parkedStub in UNIT_STUBS
            return if (stubbed) UNIT_STUBBED else UNIT_OK
        }

        /**
         * Compares the unit arrays after the window of pairs from dump [from] to dump [to] with
         * the dump [ewram]. Masked: +0x3C..0x3F (`pMapSpriteHandle`, `RefreshUnitSprites`) and
         * `US_HIDDEN` (+0x0C bit 0, which MU code and the map sprite refresh toggle).
         */
        private fun compareUnits(from: Int, to: Int, ewram: ByteArray, partial: Boolean) {
            val want = UnitTable(romView).also { it.loadFrom(ewram) }
            val d = ArrayList<String>()
            val touched = HashSet<Int>()
            for (id in windowTouched) {
                // An id without a slot maps to slot 0 by accident: skip it.
                val o = table.unitOffset(id)
                if (o >= 0) touched += o / UnitTable.UNIT_SIZE
            }
            // A window with stubbed unit commands (loads, moves, battles) changes other units too:
            // only the units the Event34 commands name are compared, without their position.
            if (!partial) {
                for (i in 0 until UnitTable.TOTAL_SIZE) {
                    if (table.bytes[i] != windowSeed[i]) touched += i / UnitTable.UNIT_SIZE
                }
            }
            for (i in 0 until UnitTable.TOTAL_SIZE) {
                if (i / UnitTable.UNIT_SIZE !in touched) continue
                val rel = i % UnitTable.UNIT_SIZE
                if (rel in 0x3C..0x3F) continue
                if (partial && (rel == 0x10 || rel == 0x11)) continue
                val mask = if (rel == 0x0C) 1.inv() else -1
                val got = table.bytes[i].toInt() and mask
                val exp = want.bytes[i].toInt() and mask
                if (got != exp) {
                    d += "unit@%X+%X got %02X want %02X".format(
                        i / UnitTable.UNIT_SIZE, rel, got and 0xFF, exp and 0xFF,
                    )
                }
            }
            result.unitWindows.pairs++
            if (partial) result.unitWindowsPartial++
            if (touched.isNotEmpty()) result.unitWindowsTouched++
            result.unitsCompared += touched.size
            for (sub in windowSubs) {
                result.subWindows.merge(sub, 1, Int::plus)
                if (d.isNotEmpty()) result.subMismatches.merge(sub, 1, Int::plus)
            }
            if (d.isNotEmpty()) {
                result.unitWindows.mismatches += "steps $from..$to subs $windowSubs: ${d.take(6)}"
            }
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
            playSt.decode(a.playSt!!, 0)
            rng.primary.load(a.rngState!![0], a.rngState[1], a.rngState[2])
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
            b.callQueue.forEachIndexed { i, e ->
                val meta = state.callQueue[2 * i + 1]
                if (state.callQueue[2 * i] != e.events || meta and 0xFF != e.execType and 0xFF ||
                    (meta ushr 8) and 0xFF != e.isUsed and 0xFF
                ) {
                    d += "callQueue[$i] got %X,%X want %X,%X".format(
                        state.callQueue[2 * i], meta and 0xFFFF, e.events,
                        (e.execType and 0xFF) or ((e.isUsed and 0xFF) shl 8),
                    )
                }
            }
            val bp = fe8.game.bm.PlaySt().also { it.decode(b.playSt!!, 0) }
            if (playSt.tutorialCounter != bp.tutorialCounter ||
                playSt.tutorialExecType != bp.tutorialExecType
            ) {
                d += "tutorial got %d,%d want %d,%d".format(
                    playSt.tutorialCounter, playSt.tutorialExecType, bp.tutorialCounter,
                    bp.tutorialExecType,
                )
            }
            for (i in 0 until if (0x04 in ran) 3 else 0) {
                if (rng.primary.word(i) != b.rngState!![i]) {
                    d += "rng[$i] got %X want %X".format(rng.primary.word(i), b.rngState[i])
                }
            }
            return d
        }
    }

    private companion object {
        const val MIN_ON_UPDATE_PAIRS = 12000
        const val MIN_CREATE_PAIRS = 2
        const val MIN_COMPARED = 12000
        
        /** Windows (of 655) in which units were touched; only 8 are. */
        const val MIN_TOUCHED_UNIT_WINDOWS = 8

        /** Event34 subcodes SET_HP, CLEA and DISA must each be compared in a window. */
        val MIN_SUB_WINDOWS = listOf(5, 10, 15)

        /** Minimum compared pairs per ported handler code. */
        val MIN_CODE_PAIRS = mapOf(
            0x01 to 14, 0x02 to 3, 0x10 to 2, 0x19 to 1, 0x03 to 8, 0x05 to 7, 0x06 to 3, 0x07 to 3,
            0x08 to 7,
            0x09 to 1, 0x12 to 3, 0x17 to 20, 0x0A to 9, 0x0C to 8, 0x0E to 2400, 0x3B to 24,
        )

        /** Minimum pairs in which each stub completed its command. */
        val MIN_STUB_PAIRS = mapOf(
            0x15 to 2, 0x1D to 28, 0x30 to 20, 0xAC to 3,
            0xBA to 7, 0x2C to 8, 0x2F to 15, 0x3F to 1,
        )

        private var cached: Result? = null

        const val UNIT_OK = 0
        const val UNIT_STUBBED = 1
        const val UNIT_UNKNOWN = 2

        /** Stubbed codes that change units (loads, moves, battles): the window is not compared. */
        val UNIT_STUBS = setOf(0x2C, 0x2F, 0x30, 0x3F)

        /** Stub codes whose C handler can return `EVC_STOP_YIELD` (the command waits). */
        val CAN_PARK = setOf(0x1D, 0x2F, 0x30, 0xA5, 0xAC, 0xB1, 0xB3, 0xBA, 0xBE)

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
         * - 0x15 Event15_BgmVolume: same.
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
         * - 0x2F Event2F_MoveUnit: CONTINUE (a move that cannot start returns STOP_YIELD, which
         *   is the parked case).
         * - 0x30 Event30_ENUN: YIELD (STOP_YIELD while a MU moves is the parked case).
         * - 0x3A Event3A_DisplayPopup: skipping: CONTINUE, else YIELD.
         * - 0x3F Event3F_ScriptBattle: subcode 2 (FIGHT_SCRIPT) CONTINUE, else YIELD.
         * - World map codes 0x9E A0 A3 A7 A8 AF B4 B6 B8 B9 BC BD C1 C4 C5: CONTINUE. The waiting
         *   ones 0xA5 (UnitFadeWait), 0xAC (UnitMoveWait), 0xB1, 0xBA, 0xBE and 0xC6 C7 C9:
         *   CONTINUE when skipping, else YIELD (their STOP_YIELD is the parked case). 0xB2, 0xB3:
         *   YIELD.
         */
        private object NoTextHost : fe8.game.event.EventTextHost {
            override fun endTalk() {}
            override fun endCgText() {}
            override fun endBoxDialogue() {}
            override fun changeUnitSpritePalette(id: Int) {}
            override fun disableMapDisp(proc: EventEngineProc): Int {
                proc.evStateBits = proc.evStateBits or EV_STATE_GFXLOCKED
                return YIELD
            }
            override fun clearTalkBubble() {}
            override fun resetAllFaces() {}
            override fun faceExists() = false
            override fun fadeOutFacesAndWait(proc: EventEngineProc) {}
            override fun resumeTalk() {}
            override fun isTalkRunning() = false
            override fun cgTextExists() = false
            override fun boxDialogueExists() = false
            override fun talkChoiceResult() = 0
            override fun setBlendAlpha(a: Int, b: Int) {}
            override var textSpeed = 0
            override fun initTalk(chr: Int, lines: Int, unpackBubble: Boolean) {}
            override fun startTalkMsg(x: Int, y: Int, msg: Int) {}
            override fun setTalkFlag(flag: Int) {}
            override fun startCgText(msg: Int, flags: Int) {}
            override fun boxDialogueSize(msg: Int) = 0 to 0
            override fun startBoxDialogue(x: Int, y: Int, msg: Int, flags: Int) {}
        }

        val IDLE_AWARE = setOf(0x1A, 0x1B, 0x1C)

        val STUBS: Map<Int, (EventEngineProc, ScriptMemory, EventState) -> Int> = buildMap {
            fun stub(code: Int, fn: (EventEngineProc, ScriptMemory, EventState) -> Int) {
                put(code, fn)
            }
            val skipOrYield =
                intArrayOf(0x15, 0x21, 0x3A, 0xA5, 0xAC, 0xB1, 0xBA, 0xBE, 0xC6, 0xC7, 0xC9)
            for (c in skipOrYield) {
                stub(c) { p, _, _ -> if (skipping(p)) CONT else YIELD }
            }
            for (c in intArrayOf(0x1D, 0x25, 0x2C, 0x30, 0xB2, 0xB3)) {
                stub(c) { _, _, _ -> YIELD }
            }
            for (c in intArrayOf(
                0x2F, 0x9E, 0xA0, 0xA3, 0xA7, 0xA8, 0xAF, 0xB4, 0xB6, 0xB8, 0xB9, 0xBC, 0xBD, 0xC1,
                0xC4, 0xC5,
            )) {
                stub(c) { _, _, _ -> CONT }
            }
            stub(0x22) { p, _, _ ->
                p.evStateBits = p.evStateBits and EV_STATE_GFXLOCKED.inv()
                YIELD
            }
            stub(0x3F) { p, m, _ -> if ((m.u16(p.pEventCurrent) and 0xF) == 2) CONT else YIELD }
        }
    }
}
