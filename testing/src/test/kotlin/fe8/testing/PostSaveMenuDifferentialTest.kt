package fe8.testing

import fe8.game.GameControl
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.PostSaveMenu
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `PostSaveMenuHandler` and `GameControl_SwitchPostSaveMenu` against the two runs that go through
 * the save menu from a save file: `ram_trace_ch2_scroll` (`chapter2.sav`, suspend save valid) and
 * `ram_trace_ch6_start` (`chapter6.sav`, no suspend save, game slot 1). The game control proc
 * (script 0x085916D4) waits at command 46 while the save menu runs (lockCnt 1); the step after
 * the menu proc ended it is seeded, the stand-in menu result goes through the handler (which sets
 * `nextAction`), one frame runs `GameControl_SwitchPostSaveMenu`, and `nextAction` and the script
 * position are compared with the next dump:
 * - ch2_scroll r01143 -> r01144: `nextAction` 4, command 80 (label 8: inside
 *   `GameCtrl_StartResumedGame`, whose call straddles frames in the game).
 * - ch6_start r01141 -> r01142: `nextAction` 2 (slot 1 + 1), command 52 (label `EXEC_BM`).
 *
 * The handler's other branches (extras, exit, new game, resume of another slot) and the
 * `GameControl_SwitchPostSaveMenu` labels other than 7 and 8 are not reached by any run. The
 * menu selection is the stand-in ([PostSaveMenu.standInSelection]), so what is verified is the
 * handler given the selection a default A press yields, not the menu.
 */
class PostSaveMenuDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("PostSaveMenuHandler", "GameControl_SwitchPostSaveMenu")
    fun `suspend save resumes the game like ch2_scroll`() {
        val seed = seedAndRun("ram_trace_ch2_scroll", "sram_chapter2_boot", "r01143", "r01144")
        assertEquals(listOf("ReadSuspendSave(3)"), seed.effects, "handler effects")
    }

    @Test
    @VerifiesPort("PostSaveMenuHandler", "GameControl_SwitchPostSaveMenu")
    fun `last game slot restarts into the battle map like ch6_start`() {
        val seed = seedAndRun("ram_trace_ch6_start", "sram_chapter6_boot", "r01141", "r01142")
        assertEquals(listOf("ReadGameSave(1)"), seed.effects, "handler effects")
    }

    private fun seedAndRun(run: String, sramRun: String, before: String, after: String): MenuRun {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val dump = ref.run(run)
        val pre = ref.run(sramRun)
        assumeTrue(pre.hasSram, "$sramRun has no SRAM dumps")
        val from = ProcArrayDump.ofEwram(dump.region(before, "ewram"))
        val to = ProcArrayDump.ofEwram(dump.region(after, "ewram"))
        val ctrl = checkNotNull(from.find(GAME_CONTROL)) { "no game control proc in $before" }
        val want = checkNotNull(to.find(GAME_CONTROL)) { "no game control proc in $after" }
        assertEquals(46, ctrl.scrCur, "$before: waiting at SwitchPostSaveMenu")
        assertEquals(0, ctrl.u8(0x28), "$before: save menu child gone")

        val sram = CartSram().also { it.copyIn(pre.sram("s00001")) }
        val lib = BmSaveLib(sram).also { it.sramInit() }
        val engine = ProcEngine()
        val control = GameControl(engine, KeyStatus(), { STAND_IN }, { STAND_IN })
        control.playSt.decode(dump.region(before, "ewram"), GPLAYST - EWRAM)
        val effects = ArrayList<String>()
        val menu = PostSaveMenu(
            control.playSt, lib, control::setNextGameActionId,
            PostSaveMenu.Effects(
                readSuspendSave = { effects += "ReadSuspendSave($it)" },
                readGameSave = { effects += "ReadGameSave($it)" },
            ),
        )
        // The game's r01144 dump is taken inside GameCtrl_StartResumedGame (the call straddles
        // frames, scrCur already past it), so the position there is the one compared.
        var hookScr = -1
        control.hooks.startResumedGame = ProcFunc { hookScr = it.scrCur }
        val proc = engine.start(control.script, 3)
        proc.idleCb = null
        proc.scrCur = ctrl.scrCur
        val state = proc.state as GameControl.State
        state.nextAction = 0

        menu.postSaveMenuHandler(menu.standInSelection())
        engine.run(engine.root(3))

        assertEquals(want.u8(0x29), state.nextAction, "$after nextAction")
        if (hookScr >= 0) {
            assertEquals(want.scrCur, hookScr, "$after scrCur in GameCtrl_StartResumedGame")
        } else {
            assertEquals(want.scrCur, proc.scrCur, "$after scrCur")
        }
        assertEquals(want.u8(0x28), proc.lockCnt, "$after lockCnt")
        return MenuRun(proc, effects)
    }

    private companion object {
        const val GAME_CONTROL = 0x085916D4
        const val GPLAYST = 0x0202BCF0
        const val EWRAM = 0x02000000
        val STAND_IN = ProcScript("stand-in", emptyList())
    }
}

/** The seeded game control proc and the handler's recorded effects. */
private class MenuRun(val proc: Proc, val effects: List<String>)
