package fe8.testing

import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, PC-6i-event) of the Chapter 1 opening event
 * (`EventScr_Ch1_BeginningScene`) in `ram_trace_prologue_end` (`PC-cap1`), `f10298..f25580`:
 * event-engine cursor windows, entered opcode handlers (decoded from the runtime ROM at the
 * observed cursors, no ROM bytes embedded), flag diffs, game-lock bytes and the hand-off to the
 * first player phase. Map changes, unit loads and movement belong to PC-6i-load. Results are
 * written up in docs/plans/M6C-J-breakdown.md ("PC-6i-event inventory"). The capture has no
 * function-entry trace, so entered handlers are inferred from the `pEventCurrent` cursors
 * sampled once per frame.
 */
class Chapter1OpeningEventInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun endRun(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN has no procs watch")
        return run
    }

    private fun names(): Map<Int, String> {
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        return names
    }

    private fun rom(): ByteArray {
        val assets = System.getenv("FE8_ASSETS")
        assumeTrue(assets != null, "FE8_ASSETS not set")
        val file = File(assets, "baserom.gba")
        assumeTrue(file.isFile, "baserom.gba not present")
        return file.readBytes()
    }

    private fun engines(
        procs: gba.testkit.WatchTrace,
        names: Map<Int, String>,
        step: Int,
    ): List<ProcArrayDump.Proc> =
        ProcArrayDump.ofWatch(procs, step, names).live.filter { it.name == "ProcScr_StdEventEngine" }

    private fun engine(
        procs: gba.testkit.WatchTrace,
        names: Map<Int, String>,
        step: Int,
    ): ProcArrayDump.Proc =
        checkNotNull(engines(procs, names, step).singleOrNull()) { "engine at f$step" }

    @Test
    fun `event engine cursor windows of the beginning scene match PC-cap1`() {
        val run = endRun()
        val names = names()
        val procs = run.watch("procs")

        // f10298: BMapMain@12 (CallBeginningEvents) starts the script; MUSC already executed.
        val e0 = engine(procs, names, 10298)
        assertEquals(SCRIPT_START, e0.u32(EV_START))
        assertEquals(SCRIPT_START, e0.u32(EV_IDK))
        assertEquals(SCRIPT_START + 0x04, e0.u32(EV_CURRENT))
        assertEquals(0x0002, e0.u16(EV_STATE_BITS))
        assertEquals(12, ProcArrayDump.ofWatch(procs, 10298, names).find("gProc_BMapMain")!!.scrCur)
        // 1-frame earlier there is no engine (event starts the frame BMapMain reaches @12).
        assertTrue(engines(procs, names, 10297).isEmpty())

        // Cursor checkpoints (frame -> idk, current, evStateBits, lock count).
        for ((frame, expected) in CHECKPOINTS) {
            val e = engine(procs, names, frame)
            assertEquals(expected.idk, e.u32(EV_IDK), "idk f$frame")
            assertEquals(expected.cur, e.u32(EV_CURRENT), "cur f$frame")
            assertEquals(expected.state, e.u16(EV_STATE_BITS), "state f$frame")
            assertEquals(expected.lock, e.u8(PROC_LOCK_CNT), "lock f$frame")
            assertEquals(SCRIPT_START, e.u32(EV_START), "start f$frame")
        }
    }

    @Test
    fun `entered opcode handlers decoded at the observed cursors`() {
        val run = endRun()
        val names = names()
        val procs = run.watch("procs")
        val rom = rom()

        // Distinct pEventCurrent values observed inside the beginning-scene script.
        val seen = sortedSetOf<Int>()
        var prev = 0
        for (step in 10298..25418) {
            val e = engines(procs, names, step).singleOrNull() ?: continue
            val cur = e.u32(EV_CURRENT)
            if (cur != prev && cur in SCRIPT_START until SCRIPT_START + SCRIPT_LENGTH) seen += cur
            prev = cur
        }
        // Linear decode: boundaries and opcode ids of the main script.
        val boundaries = ArrayList<Int>()
        val ids = HashMap<Int, Int>()
        var at = SCRIPT_START
        while (at < SCRIPT_START + SCRIPT_LENGTH) {
            val hword = u16(rom, at)
            boundaries += at
            ids[at] = hword shr 8
            at += ((hword shr 4) and 0xF) * 2
        }
        assertEquals(SCRIPT_START + SCRIPT_LENGTH, at)
        // Every observed cursor is an instruction boundary of the linear decode.
        assertTrue(boundaries.containsAll(seen), "cursors off boundary: ${seen - boundaries.toSet()}")
        // Handlers entered: opcode ids of instructions that start before the last sampled cursor.
        val last = seen.last()
        val entered = boundaries.filter { it < last }.map { ids.getValue(it) }.toSortedSet()
        assertEquals(ENTERED_OPCODES.keys.toSortedSet(), entered)
        // The three shared sub-scripts reached through CALL (idk values) decode as documented.
        assertEquals(0x03, ids0(rom, EV_TEXT_SHOW_WITH_FADE_IN))
        assertEquals(0x03, ids0(rom, EV_SET_BACKGROUND))
        assertEquals(0x0A, ids0(rom, EV_TEXT_WITH_BG))
    }

    private fun ids0(rom: ByteArray, address: Int) = u16(rom, address) shr 8

    private fun u16(rom: ByteArray, address: Int): Int {
        val o = address - ROM_BASE
        return (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `only four flag bytes change in the Chapter 1 opening`() {
        val run = endRun()
        val flags = run.watch("flags")
        val diffs = ArrayList<String>()
        for (step in 10298..25580) {
            val a = flags.step(step - 1)
            val b = flags.step(step)
            for (i in a.indices) {
                if (a[i] != b[i]) {
                    diffs += "f$step[$i]:%02X>%02X".format(a[i].toInt() and 0xFF, b[i].toInt() and 0xFF)
                }
            }
        }
        // [32] = gChapterFlagBits[0] bit 0 EVFLAG_BATTLE_QUOTES (ENUT at the FIGHT, ENUF after),
        // [33] bit 2 = EVFLAG_TMP(11) (ENUT near the end of the script),
        // [3] bit 7 = EVFLAG_HIDE_BLINKING_ICON (cleared by BMapMain UndeployEveryone / label 11).
        assertEquals(
            listOf("f15085[32]:00>01", "f15526[32]:01>00", "f25419[33]:00>04", "f25423[3]:80>00"),
            diffs,
        )
    }

    @Test
    fun `event end switches through the end-of-script event and returns to player phase`() {
        val run = endRun()
        val names = names()
        val procs = run.watch("procs")
        val gbm = run.watch("gbmst")
        val playSt = run.watch("gplayst")

        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)

        // ENDA at the script end: execType CUTSCENE -> EventScr_08592114 (CHECK_EVBIT(10) ...).
        val e25419 = engine(procs, names, 25419)
        assertEquals(EV_END_OF_SCRIPT, e25419.u32(EV_IDK))
        assertEquals(0x0092, e25419.u16(EV_STATE_BITS)) // NOFADE | NOSKIP | 0002
        assertEquals(5, e25419.scrCur)
        assertEquals(6, engine(procs, names, 25420).scrCur)
        // Engine ends f25422 (EventEngine_OnEnd, UnlockGame): lock byte of gBmSt drops.
        assertTrue(engines(procs, names, 25422).isEmpty())
        assertEquals(1, gbm.u8(25418, GBM_LOCK))
        assertEquals(0, gbm.u8(25422, GBM_LOCK))
        // CallNextQueuedEvent -> second engine runs EventScr_Ch1_Turn1Player (start follows ENDA).
        val e25423 = engine(procs, names, 25423)
        assertEquals(SCRIPT_START + SCRIPT_LENGTH, e25423.u32(EV_START))
        assertEquals(1, gbm.u8(25423, GBM_LOCK))
        assertTrue(engines(procs, names, 25426).isEmpty())
        assertEquals(0, gbm.u8(25426, GBM_LOCK))

        // BMapMain advances 12 (CallBeginningEvents) -> 17 (BmMain_ChangePhase) at f25423.
        assertEquals(12, dump(25422).find("gProc_BMapMain")!!.scrCur)
        assertEquals(17, dump(25423).find("gProc_BMapMain")!!.scrCur)
        assertEquals(0x40, playSt.u8(25422, PLAYST_FACTION))
        assertEquals(0, playSt.u16(25422, PLAYST_TURN))
        assertEquals(0x00, playSt.u8(25423, PLAYST_FACTION))
        assertEquals(1, playSt.u16(25423, PLAYST_TURN))
        // PhaseIntro f25429..f25501, then player phase.
        assertNotNull(dump(25429).find("ProcScr_PhaseIntro"))
        assertNull(dump(25502).find("ProcScr_PhaseIntro"))
        assertNull(dump(25506).find("gProcScr_PlayerPhase"))
        assertEquals(5, dump(25507).find("gProcScr_PlayerPhase")!!.scrCur)
        assertEquals(31, dump(25507).find("gProc_BMapMain")!!.scrCur)
        assertEquals(16, dump(25510).find("gProcScr_PlayerPhase")!!.scrCur)
        for (step in 25510..25580) {
            val d = dump(step)
            assertEquals(16, d.find("gProcScr_PlayerPhase")!!.scrCur, "idle f$step")
            assertNull(d.find("ProcScr_StdEventEngine"), "no engine f$step")
        }
    }

    private class Checkpoint(val idk: Int, val cur: Int, val state: Int, val lock: Int)

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val ROM_BASE = 0x08000000

        /** `EventScr_Ch1_BeginningScene` and its size from the symbol table. */
        const val SCRIPT_START = 0x089EF848
        const val SCRIPT_LENGTH = 0x1E0

        const val EV_TEXT_SHOW_WITH_FADE_IN = 0x089EE2C4
        const val EV_SET_BACKGROUND = 0x089EE2E8
        const val EV_TEXT_WITH_BG = 0x089EE310
        const val EV_END_OF_SCRIPT = 0x08592114

        const val PROC_LOCK_CNT = 0x28
        const val EV_START = 0x30
        const val EV_IDK = 0x34
        const val EV_CURRENT = 0x38
        const val EV_STATE_BITS = 0x3C
        const val GBM_LOCK = 1
        const val PLAYST_FACTION = 0x0F
        const val PLAYST_TURN = 0x10

        val ENTERED_OPCODES =
            mapOf(
                0x02 to "Event02_EvBitAndIdMod",
                0x05 to "Event05_SetSlot",
                0x07 to "Event07_SlotQueueOperations",
                0x0A to "Event0A_Call",
                0x0E to "Event0E_STAL",
                0x12 to "Event12_StartBGM",
                0x1A to "Event1A_TEXTSTART",
                0x1B to "Event1B_TEXTSHOW",
                0x1D to "Event1D_WaitForLockTalk",
                0x2C to "Event2C_LoadUnits",
                0x2F to "Event2F_MoveUnit",
                0x30 to "Event30_ENUN",
                0x34 to "Event34_MessWithUnitState",
                0x3B to "Event3B_DisplayCursor",
                0x3F to "Event3F_ScriptBattle",
            )

        private const val SB = SCRIPT_START

        val CHECKPOINTS =
            mapOf(
                10301 to Checkpoint(SB, SB + 0x0C, 0x0002, 0),
                10378 to Checkpoint(SB, SB + 0x10, 0x0002, 0),
                10497 to Checkpoint(EV_SET_BACKGROUND, EV_SET_BACKGROUND + 0x10, 0x0102, 1),
                10514 to Checkpoint(EV_SET_BACKGROUND, EV_SET_BACKGROUND + 0x18, 0x1102, 0),
                10517 to Checkpoint(EV_SET_BACKGROUND, EV_SET_BACKGROUND + 0x24, 0x1002, 1),
                10534 to Checkpoint(EV_TEXT_WITH_BG, EV_TEXT_WITH_BG + 0x10, 0x1002, 0),
                13006 to Checkpoint(EV_TEXT_WITH_BG, EV_TEXT_WITH_BG + 0x14, 0x1002, 0),
                13007 to Checkpoint(EV_TEXT_SHOW_WITH_FADE_IN, EV_TEXT_SHOW_WITH_FADE_IN + 0x10, 0x1102, 1),
                13032 to Checkpoint(EV_TEXT_SHOW_WITH_FADE_IN, EV_TEXT_SHOW_WITH_FADE_IN + 0x20, 0x0002, 1),
                13049 to Checkpoint(SB, SB + 0x38, 0x0002, 0),
                15085 to Checkpoint(SB, SB + 0xB8, 0x0002, 1),
                15526 to Checkpoint(SB, SB + 0xC8, 0x0002, 0),
                16006 to Checkpoint(SB, SB + 0xE4, 0x0002, 1),
                20248 to Checkpoint(SB, SB + 0x18C, 0x0002, 0),
                25406 to Checkpoint(SB, SB + 0x1B4, 0x0002, 1),
            )
    }
}
