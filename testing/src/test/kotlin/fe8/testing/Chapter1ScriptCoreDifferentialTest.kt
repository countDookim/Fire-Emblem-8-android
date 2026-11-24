package fe8.testing

import fe8.game.RomView
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventFade
import fe8.game.event.EventHandler
import fe8.game.event.EventInput
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.EventUnitState
import fe8.game.event.EventUnitStateHooks
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.LcdControl
import gbafe.render.ScreenFade
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * PC-6e-a: the script-core opcodes that the Chapter 1 opening runs, against
 * `ram_trace_prologue_end` (`PC-cap1`, `f10298..f25580`).
 *
 * Each case is a frame pair (A, B) of the per-step `procs`/`evglobals`/`flags`/`gplayst` watches
 * in which the engine ran the commands between its cursor at A and its cursor at B. The engine
 * state of A seeds a real [EventEngine] with the ported handlers, `EventEngine.onUpdate` runs
 * once, and the result is compared with B: `pEventCurrent`, `pEventIdk`, `evStateBits`, stall
 * timer, the engine proc's lock count, event slots 1..13, slot queue and counter, call and
 * active queues, flag bytes and the live fade procs (speed and level). Slot 0 is not compared
 * (`OnUpdate` zeroes it). Commands this test does not verify (text, unit loads and moves,
 * cursor) are stubs that complete when the command is not the last one before B's cursor and
 * yield when it is; they change no compared state.
 *
 * Not compared: LCD registers (BLDY, BLDCNT, DISPCNT exist only in the sparse full dumps).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Chapter1ScriptCoreDifferentialTest {
    /**
     * The watches are large (hundreds of MB): one rig for this class's tests, dropped after them
     * so later classes in the same test JVM do not run with a full heap.
     */
    private var shared: Rig? = null
    private var loaded = false

    @AfterAll
    fun releaseRig() {
        shared = null
    }

    private fun rig(): Rig {
        if (!loaded) {
            shared = loadRig()
            loaded = true
        }
        val r = shared
        assumeTrue(r != null, "reference data or ROM not present")
        return r!!
    }

    @Test
    @VerifiesPort("Event03_CheckEvBitOrId")
    fun `Event03 CheckEvBitOrId EvBit check matches PC-cap1`() {
        val r = rig()
        // Bit 8 (faded in) clear at the start of each background fade chain: slot C stays 0.
        for ((a, b) in FADE_OUT_PAIRS) r.assertPair(a, b, setOf(0x03, 0x17))
        // CHECK_EVBIT(10), (8) and (7) at the end of the script: bit 7 (no skip) is set, so slot C
        // becomes 1.
        r.assertEndPair(25418, 25419, 25418, setOf(0x03, 0x19))
    }

    @Test
    @VerifiesPort("Event17_Fade")
    fun `Event17 Fade FADI and FADU match PC-cap1`() {
        val r = rig()
        for ((a, b) in FADE_OUT_PAIRS) r.assertPair(a, b, setOf(0x17))
        for ((a, b) in FADE_IN_PAIRS) r.assertPair(a, b, setOf(0x17))
    }

    @Test
    @VerifiesPort("Event34_MessWithUnitState")
    fun `Event34 DISA KILL and DISA_IF match PC-cap1`() {
        val r = rig()
        // DISA on the tile of slot B (0, 0): no unit there, the command continues and the unit
        // arrays do not change.
        r.assertUnitPair(13102, 13103, 0x34, 13100, 13100, listOf(0x41, 0x42))
        // KILL (hides the unit, starts the death fade), window f15520..f15530.
        r.assertUnitPair(15525, 15526, 0x34, 15520, 15530, listOf(0x42))
        // DISA_IF once the death fade is gone (clears the unit), window f15550..f15560.
        r.assertUnitPair(15557, 15558, 0x34, 15550, 15560, listOf(0x42))
    }

    @Test
    fun `Event06 SlotOperation SADD with slot 0 matches PC-cap1`() {
        val r = rig()
        for ((a, b) in SADD_PAIRS) r.assertPair(a, b, setOf(0x06))
    }

    @Test
    @VerifiesPort("Event19_Checks")
    fun `Event19 Checks tutorial mode matches PC-cap1`() {
        val r = rig()
        r.assertPair(16018, 16019, setOf(0x19))
        r.assertEndPair(25418, 25419, 25418, setOf(0x03, 0x19))
    }

    private class Rig(
        rom: ByteArray,
        private val trace: EventTrace,
        private val run: ReferenceData.Run,
    ) {
        private val mem = ScriptMemory { a ->
            val o = a - ROM_BASE
            (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
        }
        private val state = EventState()
        private val flags = Fe8EventFlags.create()
        private val procs = ProcEngine()
        private val engine = EventEngine(procs, state, mem, object : EventEngineHooks {})
        private val rng = RngService()
        private val playSt = PlaySt()
        private val romView = RomView.ofRom(rom)
        private val table = UnitTable(romView)
        private val loader = UnitLoader(table, romView, { 0 }, rng, { false })
        private val fade = ScreenFade(procs, LcdControl())
        private var ewram = ByteArray(0)
        private var deathFadeInB = false
        private var nextB = 0
        private var stub33 = 0
        private val ran = HashSet<Int>()
        private val dumpAt = run.dumps.entries.associate { it.value to it.key }

        private val unitHooks = object : EventUnitStateHooks {
            override fun unitAt(x: Int, y: Int): Int {
                val rows = u32(ewram, GBM_MAP_UNIT - EWRAM) - EWRAM
                return ewram[u32(ewram, rows + 4 * y) - EWRAM + x].toInt() and 0xFF
            }

            override fun muDeathFadeExists(): Boolean = deathFadeInB
        }
        private val unitState = EventUnitState(state, mem, table, loader, unitHooks)

        init {
            procs.init()
            val core = EventScrCore(
                state, mem, object : EventInput {}, flags, rng, playSt, KeyStatus(),
            )
            for (code in 0..0xFF) engine.register(code, stub(code))
            core.registerAll(engine)
            engine.register(0x03, recording(0x03, core.event03CheckEvBitOrId))
            engine.register(0x06, recording(0x06, core.event06SlotOperation))
            engine.register(0x19, recording(0x19, core.event19Checks))
            val eventFade = EventFade(mem, fade)
            eventFade.registerOn(engine)
            engine.register(0x17, recording(0x17, eventFade.event17Fade))
            engine.register(0x34, recording(0x34, unitState.event34MessWithUnitState))
        }

        /**
         * A command not verified here: stays parked when B's cursor is on it, yields when it is
         * the last before B's cursor, otherwise continues. `Event3B` (cursor) always continues:
         * the STAL after it is what leaves B's cursor there.
         */
        private fun stub(code: Int) = EventHandler { p ->
            if (p.pEventCurrent == nextB) return@EventHandler EVC_STOP_YIELD
            if (code == 0x3B) return@EventHandler EVC_ADVANCE_CONTINUE
            if (code == 0x33) state.slots[0xC] = stub33
            val next = p.pEventCurrent + 2 * ((mem.u16(p.pEventCurrent) shr 4) and 0xF)
            if (next == nextB) EVC_ADVANCE_YIELD else EVC_ADVANCE_CONTINUE
        }

        private fun recording(code: Int, h: EventHandler) = EventHandler { p ->
            ran += code
            h.run(p)
        }

        fun assertPair(aStep: Int, bStep: Int, expectedRan: Set<Int>) {
            val diffs = replay(aStep, bStep)
            assertEquals(emptyList<String>(), diffs, "f$aStep -> f$bStep")
            assertTrue(ran.containsAll(expectedRan), "f$aStep -> f$bStep ran $ran")
        }

        /**
         * Like [assertPair] for a unit command: the unit arrays come from the dump of frame
         * [seed] and the records of [ids] are compared with the dump of frame [want].
         */
        fun assertUnitPair(
            aStep: Int, bStep: Int, code: Int, seed: Int, want: Int, ids: List<Int>,
        ) {
            val diffs = replay(aStep, bStep, seed, want, ids)
            assertEquals(emptyList<String>(), diffs, "f$aStep -> f$bStep")
            assertTrue(code in ran, "f$aStep -> f$bStep ran $ran")
        }

        /**
         * The pair of the end of the script (REMA wait, `EventScr_MoveUnitS2ToLeader`, then
         * `EventScr_CallOnTutorialMode` and ENDA into the end-of-script event) with both results
         * of the stubbed `Event33` checks: the state of B does not depend on them.
         */
        fun assertEndPair(aStep: Int, bStep: Int, seed: Int, expectedRan: Set<Int>) {
            for (v in 0..1) {
                stub33 = v
                val diffs = replay(aStep, bStep, seed, seed, emptyList())
                assertEquals(emptyList<String>(), diffs, "f$aStep -> f$bStep, Event33 result $v")
                assertTrue(ran.containsAll(expectedRan), "f$aStep -> f$bStep ran $ran")
            }
        }

        private fun dumpTag(frame: Int) = dumpAt.getValue(frame)

        private fun replay(
            aStep: Int,
            bStep: Int,
            seed: Int? = null,
            want: Int = 0,
            ids: List<Int> = emptyList(),
        ): List<String> {
            val a = trace.at(aStep)
            val b = trace.at(bStep)
            val ea = checkNotNull(a.engine) { "engine at f$aStep" }
            val eb = checkNotNull(b.engine) { "engine at f$bStep" }
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
            flags.copyIn(a.permanentFlags!! + a.chapterFlags!!)
            procs.init()
            val proc = engine.create(ea.pEventStart, ea.execType)
            proc.pEventIdk = ea.pEventIdk
            proc.pEventCurrent = ea.pEventCurrent
            proc.evStateBits = ea.evStateBits
            proc.evStallTimer = ea.evStallTimer
            nextB = eb.pEventCurrent
            ran.clear()
            table.bytes.fill(0)
            if (seed != null) {
                ewram = run.region(dumpTag(seed), "ewram")
                table.loadFrom(ewram)
            }
            deathFadeInB = trace.procsAt(bStep).find("ProcScr_MuDeathFade") != null
            engine.onUpdate(proc)
            // The fade procs the engine started run once more in the same frame.
            procs.procs.filter { it.script?.name?.startsWith("ProcScr_Fade") == true }.forEach { procs.run(it) }

            val d = ArrayList<String>()
            fun chk(name: String, got: Int, want: Int) {
                if (got != want) d += "$name got %X want %X".format(got, want)
            }
            chk("pEventCurrent", proc.pEventCurrent, eb.pEventCurrent)
            chk("pEventIdk", proc.pEventIdk, eb.pEventIdk)
            chk("evStateBits", proc.evStateBits, eb.evStateBits)
            chk("evStallTimer", proc.evStallTimer, eb.evStallTimer)
            val dump = trace.procsAt(bStep)
            chk("engine lockCnt", proc.proc!!.lockCnt, dump.find("ProcScr_StdEventEngine")!!.u8(0x28))
            for (i in 1 until EventState.SLOT_COUNT) chk("slot$i", state.slots[i], b.slots[i])
            if (!state.slotQueue.contentEquals(b.slotQueue)) d += "slotQueue differs"
            chk("slotCounter", state.slotCounter, b.slotCounter)
            val out = ByteArray(0x30)
            flags.copyOut(out)
            if (!out.contentEquals(b.permanentFlags!! + b.chapterFlags!!)) d += "flags differ"
            if (seed != null) {
                val expected = UnitTable(romView).also { it.loadFrom(run.region(dumpTag(want), "ewram")) }
                for (id in ids) {
                    val o = table.unitOffset(id)
                    for (i in 0 until UnitTable.UNIT_SIZE) {
                        if (i in 0x3C..0x3F) continue
                        chk("unit %02X+%02X".format(id, i), table.bytes[o + i].toInt() and 0xFF,
                            expected.bytes[o + i].toInt() and 0xFF)
                    }
                }
            }
            val fades = dump.live.filter { it.name?.startsWith("ProcScr_Fade") == true }
            val mine = procs.procs.filter { it.script?.name?.startsWith("ProcScr_Fade") == true }
            assertEquals(
                fades.map { it.name.orEmpty() }.sorted(), mine.map { it.script!!.name }.sorted(),
                "fade procs at f$bStep",
            )
            for (f in fades) {
                val s = mine.first { it.script!!.name == f.name }.state as ScreenFade.State
                chk("${f.name} speed", s.speed, f.u16(0x64))
                chk("${f.name} level", s.level, f.u16(0x66))
            }
            return d
        }
    }

    private companion object {
        fun loadRig(): Rig? {
            val ref = ReferenceData.fromEnvironment()
            val assets = File(
                System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            )
            val rom = File(assets, "baserom.gba")
            if (!ref.available || !rom.isFile) return null
            val trace = EventTrace.prologue(ref, RUN) ?: return null
            return Rig(rom.readBytes(), trace, ref.run(RUN))
        }

        const val RUN = "ram_trace_prologue_end"
        const val ROM_BASE = 0x08000000
        const val EWRAM = 0x02000000
        const val GBM_MAP_UNIT = 0x0202E4D8

        /** (A, B) of the frames where the background chain reaches FADI (Event03, 17). */
        val FADE_OUT_PAIRS = listOf(
            10496 to 10497, 13006 to 13007, 13163 to 13164, 15006 to 15007,
            16271 to 16272, 20205 to 20206,
        )

        /** (A, B) where Event17 FADU runs alone. */
        val FADE_IN_PAIRS = listOf(
            10516 to 10517, 13031 to 13032, 13183 to 13184, 15031 to 15032,
            16292 to 16293, 20230 to 20231,
        )

        /** (A, B) where the TextWithBG script runs SADD. */
        val SADD_PAIRS = listOf(10533 to 10534, 13200 to 13201, 16309 to 16310)

        fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
