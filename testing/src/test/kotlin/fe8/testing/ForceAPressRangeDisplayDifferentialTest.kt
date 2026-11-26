package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlaySt
import fe8.game.bm.PlayerPhase
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * PC-10d: differential verification of the forced A press during the range display in the Easy
 * tutorial (`ram_trace_newgame_easy`, `f19019..19031`): `PlayPhaseForcePressAButtonInRangeDisp`
 * swaps the PlayerPhase proc's repeat callback (`proc_idleCb`, 0x0801CD1D -> 0x0801D819 at f19021)
 * and `PlayerPhase_RangeDisplayIdle_ForceAPress` forces `newKeys` to A before the normal range
 * display idle; the proc then waits in `PlayerPhase_WaitForUnitMovement` (0x0801D64D at f19030).
 * The capture gives the callback sequence and the key state; the port is driven from the f19019
 * state.
 */
@Timeout(180)
class ForceAPressRangeDisplayDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort(
        "PlayPhaseForcePressAButtonInRangeDisp",
        "PlayerPhase_RangeDisplayIdle_ForceAPress",
        "PlayerPhase_WaitForUnitMovement",
        "Proc_SetRepeatCb",
    )
    fun `idle callback swaps to the forced A press and then the wait for movement`() {
        val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run(RUN)
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty() && run.hasWatch("procs"), "procs watch missing")
        val procsWatch = run.watch("procs")
        fun idleCb(step: Int) = ProcArrayDump.ofWatch(procsWatch, step, names)
            .find("gProcScr_PlayerPhase")!!.u32(PROC_IDLE_CB)

        // Capture: range display idle, forced A press at f19021, wait for movement at f19030.
        assertEquals(RANGE_IDLE, idleCb(19019))
        assertEquals(FORCE_A, idleCb(19021))
        assertEquals(WAIT_MOVE, idleCb(19030))

        fun ew(step: Int): ByteArray {
            val full = "f%05d".format(step)
            val key = if (run.dumps.containsKey(full)) full else "r%05d".format(step)
            return run.region(key, "ewram")
        }
        fun keysAt(step: Int) = KeyStatus().also {
            val from = KEY_STATUS - EWRAM
            it.readFrom(ew(step).copyOfRange(from, from + KeyStatus.STRUCT_SIZE))
        }
        // The player's own A is f19020; the forced press follows with no key of its own.
        assertEquals(0, keysAt(19019).newKeys)
        assertEquals(KEY_A, keysAt(19020).newKeys and KEY_A)

        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val chapters = ChapterData(rom)
        val e = ew(19019)
        val playSt = PlaySt().also { it.decode(e, PLAY_ST - EWRAM) }
        val bm = BmSt().also { it.decode(e, BM_ST - EWRAM) }
        val map = BmMap(rom, chapters, bm).also { it.initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).also { it.loadFrom(e) }
        val procs = ProcEngine()
        val keys = KeyStatus()
        val phase = PlayerPhase(
            procs, bm, playSt, map, units, CamMove(procs, bm),
            EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
            { false },
        )
        var idleCalls = 0
        var seenNew = -1
        var seenRepeated = -1
        phase.hooks.rangeDisplayIdle = ProcFunc {
            idleCalls++
            seenNew = keys.newKeys
            seenRepeated = keys.repeatedKeys
            procs.breakProc(it)
        }
        var mu = true
        phase.hooks.muExistsActive = { mu }
        phase.hooks.displayUnitMovement = ProcFunc {}
        phase.hooks.applyUnitMovement = ProcFunc {}

        // No PlayerPhase proc: nothing happens.
        phase.playPhaseForcePressAButtonInRangeDisp()

        val pp = procs.start(phase.script, 2)
        pp.scrCur = RANGE_IDLE_SCR
        pp.idleCb = phase.rangeDisplayIdleCb
        // Another callback is left alone.
        val other = ProcFunc {}
        pp.idleCb = other
        phase.playPhaseForcePressAButtonInRangeDisp()
        assertSame(other, pp.idleCb)

        // Range display idle callback: swapped for the forced A press.
        pp.idleCb = phase.rangeDisplayIdleCb
        phase.playPhaseForcePressAButtonInRangeDisp()
        assertNotNull(pp.idleCb)
        assertNotSame(phase.rangeDisplayIdleCb, pp.idleCb)

        // The next frame presses A (keys of f19019 have none) and runs the range display idle.
        keys.newKeys = 0
        keys.repeatedKeys = KEY_A
        procs.run(procs.root(2))
        assertEquals(1, idleCalls)
        assertEquals(KEY_A, seenNew)
        assertEquals(0, seenRepeated)

        // Then it waits for the unit movement until the MU is gone.
        val trail = ArrayList<Int>()
        repeat(2) {
            procs.run(procs.root(2))
            trail += pp.scrCur
        }
        mu = false
        repeat(2) {
            procs.run(procs.root(2))
            trail += pp.scrCur
        }
        // Script indices: the wait repeat (27) holds while the MU lives, then label 2 repeat (29).
        assertEquals(listOf(WAIT_SCR, WAIT_SCR, WAIT_SCR, APPLY_SCR), trail)
        assertEquals(1, idleCalls)
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val PROC_IDLE_CB = 0x0C
        const val RANGE_IDLE = 0x0801CD1D
        const val FORCE_A = 0x0801D819
        const val WAIT_MOVE = 0x0801D64D
        const val EWRAM = 0x02000000
        const val KEY_STATUS = 0x02024CC0
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val KEY_A = 1
        const val RANGE_IDLE_SCR = 25
        const val WAIT_SCR = 27
        const val APPLY_SCR = 29
    }
}
