package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.ActiveEventRegistry
import fe8.game.event.EventChangeAi
import fe8.game.event.EventEngine
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ReleaseEventStubs
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The boss's flag-gated turn event of `ram_trace_prologue_clear` (EP-11a): its start at step 2964
 * and its AI change (opcode 0x39) at step 3719.
 *
 * Start: the phase switch of step 2964 (`RunPhaseSwitchEvents`) is seeded from the step 2963
 * state (flags and registry; `gPlaySt` of step 2964, as `SwitchPhases` runs earlier in the
 * same step) and compared with step 2964: the script and exec type handed to `CallEvent`, the registry bytes, the flags and the
 * tutorial queue.
 *
 * AI change: the event engine of step 3718 (parked before the command run that ends the event)
 * is loaded into a real [EventEngine] with the ported commands (everything unported is the
 * release stub) and one frame runs; the engine fields, slots, flags and the whole red unit array
 * are compared with step 3719 (the boss's `ai1`, `ai_a_pc`, `ai2`, `ai_b_pc` go 6, 1, 3, 1 to
 * 0, 0, 0, 0). Talk, faces, text, the UI restore and the polls of the event are EP-11b..h; the
 * engine state at 3718 is already past them.
 */
class BossTurnEventDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("ClearActiveEventRegistry", "RegisterEventActivation", "RunTutorialEvent")
    fun `the turn event start at f2964 reproduces the registry, flags and tutorial queue`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run(RUN)
        val rom = RomView.ofRom(romFile.readBytes())
        val dumps = run.dumps.entries.associate { it.value to it.key }
        val trace = EventTrace.prologue(ref, RUN)
        assumeTrue(trace != null, "$RUN event watches not present")

        // `SwitchPhases` (turn and faction) runs in the same step before the events (BmMain_ChangePhase,
        // verified by PhaseStartEndDifferentialTest), so the play state is that of step 2964.
        val playSt = PlaySt().also { it.decode(run.watch("gplayst").step(AT_START), 0) }
        val flags = Fe8EventFlags.create().also {
            it.copyIn(run.watch("flags").step(BEFORE_START))
        }
        val infos = EventInfos(scriptMemory(rom), flags, playSt)
        val chapters = ChapterData(rom)
        val calls = ArrayList<Pair<Int, Int>>()
        infos.hooks = object : EventInfoHooks {
            override fun callEvent(script: Int, execType: Int) {
                calls += script to execType
            }

            override fun chapterEventGroup(chapter: Int): Int =
                chapters.getChapterEventDataPointer(chapter)
        }
        val before = run.region(dumps.getValue(BEFORE_START), "iwram")
        infos.registry.load(before, REGISTRY - IWRAM)
        assertEquals(1, infos.registry.idx, "seed: the earlier event's entry is registered")

        assertTrue(infos.runPhaseSwitchEvents(), "the turn event starts")

        val engine = trace!!.at(AT_START).engine!!
        assertEquals(listOf(engine.pEventStart to engine.execType), calls, "CallEvent")
        val after = run.region(dumps.getValue(AT_START), "iwram")
        val got = ByteArray(ActiveEventRegistry.BYTES)
        infos.registry.store(got, 0)
        assertEquals(
            after.copyOfRange(REGISTRY - IWRAM, REGISTRY - IWRAM + ActiveEventRegistry.BYTES)
                .toList(),
            got.toList(),
            "gActiveEventRegistry",
        )
        val flagBytes = ByteArray(0x30).also { flags.copyOut(it) }
        assertEquals(run.watch("flags").step(AT_START).toList(), flagBytes.toList(), "flags")
        val want = PlaySt().also { it.decode(run.watch("gplayst").step(AT_START), 0) }
        assertEquals(want.tutorialCounter, playSt.tutorialCounter, "tutorial_counter")
        assertEquals(want.tutorialExecType, playSt.tutorialExecType, "tutorial_exec_type")
    }

    @Test
    @VerifiesPort("Event39_ChangeAiScript", "ChangeAiForCharacter", "ChangeUnitAi")
    fun `opcode 39 at f3719 sets the boss AI bytes and positions to the captured values`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run(RUN)
        val rom = RomView.ofRom(romFile.readBytes())
        val dumps = run.dumps.entries.associate { it.value to it.key }
        val trace = EventTrace.prologue(ref, RUN)
        assumeTrue(trace != null, "$RUN event watches not present")
        val a = trace!!.at(BEFORE_AI)
        val b = trace.at(AT_AI)
        val ea = a.engine!!
        val red = run.watch("red")

        // The capture's boss: the one red unit whose AI bytes change at this step.
        var changed = 0
        for (i in 0 until UnitTable.RED_COUNT) {
            val o = i * UnitTable.UNIT_SIZE + AI_BYTES
            val was = (0 until 4).map { red.u8(BEFORE_AI, o + it) }
            val now = (0 until 4).map { red.u8(AT_AI, o + it) }
            if (was != now) {
                changed++
                assertEquals(listOf(6, 1, 3, 1), was, "captured ai1/pc/ai2/pc before")
                assertEquals(listOf(0, 0, 0, 0), now, "captured ai1/pc/ai2/pc after")
            }
        }
        assertEquals(1, changed, "units whose AI changed in the capture")

        val mem = scriptMemory(rom)
        val table = UnitTable(rom).also {
            it.loadFrom(run.region(dumps.getValue(BEFORE_AI), "ewram"))
        }
        val state = EventState()
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
        val playSt = PlaySt().also { it.decode(a.playSt!!, 0) }
        val flags = Fe8EventFlags.create().also {
            it.copyIn(a.permanentFlags!! + a.chapterFlags!!)
        }
        val procs = ProcEngine().also { it.init() }
        val engine = EventEngine(procs, state, mem)
        EventScrCore(
            state, mem, flags = flags, rng = RngService(), playSt = playSt, keys = KeyStatus(),
        ).registerAll(engine)
        EventChangeAi(state, mem, table).registerOn(engine)
        val stubbed = ArrayList<Int>()
        ReleaseEventStubs.registerMissing(engine) { stubbed += it }

        val proc = engine.create(ea.pEventStart, ea.execType)
        proc.pEventIdk = ea.pEventIdk
        proc.pEventCurrent = ea.pEventCurrent
        proc.evStateBits = ea.evStateBits
        proc.evStallTimer = ea.evStallTimer
        engine.onUpdate(proc)

        val d = ArrayList<String>()
        val eb = b.engine!!
        fun chk(name: String, got: Int, want: Int) {
            if (got != want) d += "$name got %X want %X".format(got, want)
        }
        chk("pEventCurrent", proc.pEventCurrent, eb.pEventCurrent)
        chk("pEventIdk", proc.pEventIdk, eb.pEventIdk)
        chk("evStateBits", proc.evStateBits, eb.evStateBits)
        chk("evStallTimer", proc.evStallTimer, eb.evStallTimer)
        chk("execType", proc.execType, eb.execType)
        for (i in 1 until EventState.SLOT_COUNT) chk("slot$i", state.slots[i], b.slots[i])
        if (!state.slotQueue.contentEquals(b.slotQueue)) d += "slotQueue differs"
        val flagBytes = ByteArray(0x30).also { flags.copyOut(it) }
        if (!flagBytes.contentEquals(b.permanentFlags!! + b.chapterFlags!!)) d += "flags differ"
        val redOffset = UnitTable.BLUE_COUNT * UnitTable.UNIT_SIZE
        val wantRed = red.step(AT_AI)
        for (i in 0 until UnitTable.RED_COUNT * UnitTable.UNIT_SIZE) {
            if (table.bytes[redOffset + i] != wantRed[i]) {
                d += "red unit %d byte %02X got %02X want %02X".format(
                    i / UnitTable.UNIT_SIZE, i % UnitTable.UNIT_SIZE,
                    table.bytes[redOffset + i].toInt() and 0xFF, wantRed[i].toInt() and 0xFF,
                )
            }
        }
        assertEquals(emptyList<String>(), d, "step $AT_AI (stubs $stubbed)")
    }

    private fun scriptMemory(rom: RomView): ScriptMemory = ScriptMemory { a -> rom.u16(a) }

    private companion object {
        const val RUN = "ram_trace_prologue_clear"
        const val IWRAM = 0x03000000

        /** `gActiveEventRegistry`. */
        const val REGISTRY = 0x03001C80

        /** Step before and of `RunPhaseSwitchEvents` starting the boss turn event. */
        const val BEFORE_START = 2963
        const val AT_START = 2964

        /** Step before and of the opcode 0x39 frame. */
        const val BEFORE_AI = 3718
        const val AT_AI = 3719

        /** `ai1` is at +0x42 of `struct Unit`, followed by `ai_a_pc`, `ai2`, `ai_b_pc`. */
        const val AI_BYTES = 0x42
    }
}
