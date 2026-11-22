package fe8.testing

import fe8.game.GameControl
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `PC-3a` (`f9441..f9444`, resume `f9863`) of `ram_trace_prologue_end` (`PC-cap1`): the
 * `GameControl` proc after the Prologue ending event is torn down. Seeds the proc, `gPlaySt`, the
 * RN state and the win flag from `f9441` and compares script cursors every frame, the RN state,
 * `gGmMonsterRnState` and `chapterIndex` at `f9443`/`f9444`, and the save-menu handoff
 * (blocking child, lock count) at `f9444`; then the resume at `f9863`.
 * `EventEngine_OnEnd`, `EndAllMus` and `EndBMapMain` are not entered by this proc and stay
 * `Transcribed`.
 */
class PrologueTeardownDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Rig(val engine: ProcEngine, val control: GameControl) {
        val calls = HashMap<String, Int>()
        lateinit var proc: gbafe.proc.Proc
        fun count(name: String) {
            calls[name] = calls.getOrElse(name) { 0 } + 1
        }
    }

    private fun rig(run: ReferenceData.Run, step: Int, withSaveMenu: Boolean): Rig {
        val dump = ProcArrayDump.ofWatch(run.watch("procs"), step)
        val ctrl = checkNotNull(dump.find(GAME_CONTROL)) { "no GameControl at f$step" }
        val engine = ProcEngine()
        val stand = ProcScript("stand-in", emptyList())
        val control = GameControl(engine, KeyStatus(), { stand }, { stand })
        val r = Rig(engine, control)
        val rng = run.watch("rng")
        control.rng.primary.load(rng.u16(step, 0), rng.u16(step, 2), rng.u16(step, 4))
        control.playSt.decode(run.watch("gplayst").step(step), 0)
        val flags = run.watch("flags")
        control.hooks.winFlagSet = { flags.u8(step, 0) and 0x08 != 0 }
        control.hooks.endAllMus = ProcFunc { r.count("EndAllMus") }
        control.hooks.registerChapterTimeAndTurnCount = ProcFunc { r.count("RegisterTime") }
        control.hooks.computeChapterRankings = ProcFunc { r.count("Rankings") }
        control.hooks.chapterChangeUnitCleanup = ProcFunc { r.count("Cleanup") }
        control.hooks.chapterSwitch = ProcFunc { control.chapterSwitch(it) }
        control.hooks.callPostChapterSaveMenu = ProcFunc { control.callPostChapterSaveMenu(it) }
        control.hooks.startPostChapterSaveMenu = ProcFunc {
            r.count("SaveMenu")
            engine.startBlocking(ProcScript("SaveMenuPostChapter (stand-in)", listOf(block())), it)
        }
        val proc = engine.start(control.script, 3)
        proc.idleCb = null
        proc.scrCur = ctrl.scrCur
        val state = proc.state as GameControl.State
        state.nextAction = ctrl.u8(0x29)
        state.nextChapter = ctrl.u8(0x2A)
        state.idleStatus = ctrl.u8(0x2B)
        state.unk2C = ctrl.u8(0x2C)
        state.unk2E = ctrl.u16(0x2E)
        state.chIndex = ctrl.u8(0x30)
        if (withSaveMenu) engine.startBlocking(stand.let { ProcScript("menu", listOf(block())) }, proc)
        r.proc = proc
        return r
    }

    private fun frame(r: Rig) = r.engine.run(r.engine.root(3))

    private fun cursor(run: ReferenceData.Run, step: Int): Int =
        checkNotNull(ProcArrayDump.ofWatch(run.watch("procs"), step).find(GAME_CONTROL)).scrCur

    @Test
    @VerifiesPort("GameControl_CallPostChapterSaveMenu", "StoreRNState")
    fun `post chapter switch f9441 to f9444 matches the capture`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        val r = rig(run, 9441, false)
        val state = r.proc.state as GameControl.State
        val rng = run.watch("rng")
        val playSt = run.watch("gplayst")
        for (step in 9442..9444) {
            frame(r)
            assertEquals(cursor(run, step), r.proc.scrCur, "f$step scrCur")
            assertEquals(playSt.u8(step, 0x0E), r.control().playSt.chapterIndex and 0xFF, "f$step ch")
            assertEquals(
                listOf(rng.u16(step, 0), rng.u16(step, 2), rng.u16(step, 4)),
                listOf(0, 1, 2).map { r.control().rng.primary.word(it) },
                "f$step rng",
            )
        }
        val iw = run.region("f09444", "iwram")
        val expected = (0..2).map { i ->
            (iw[MONSTER_RN - IWRAM + i * 2].toInt() and 0xFF) or
                ((iw[MONSTER_RN - IWRAM + i * 2 + 1].toInt() and 0xFF) shl 8)
        }
        assertEquals(expected, r.control().gmMonsterRnState.toList(), "gGmMonsterRnState")
        assertEquals(1, r.calls["EndAllMus"], "EndAllMus once")
        assertEquals(1, r.calls["Rankings"], "ComputeChapterRankings once")
        assertEquals(1, r.calls["Cleanup"], "ChapterChangeUnitCleanup once")
        assertEquals(1, r.calls["SaveMenu"], "save menu handoff")
        assertEquals(state.nextChapter, r.control().playSt.chapterIndex)
        val gc = checkNotNull(ProcArrayDump.ofWatch(run.watch("procs"), 9444).find(GAME_CONTROL))
        assertEquals(gc.u8(0x28), r.proc.lockCnt, "f9444 GameControl lock count")
    }

    @Test
    fun `save menu return resumes GameControl at f9863`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        val r = rig(run, 9862, true)
        r.engine.end(r.proc.child)
        // f9863: the menu proc is gone and the lock released, the cursor still at the yield.
        val gc = checkNotNull(ProcArrayDump.ofWatch(run.watch("procs"), 9863).find(GAME_CONTROL))
        assertEquals(gc.u8(0x28), r.proc.lockCnt, "f9863 lock count")
        assertEquals(cursor(run, 9863), r.proc.scrCur, "f9863 scrCur")
        frame(r)
        assertEquals(cursor(run, 9864), r.proc.scrCur, "f9864 scrCur")
    }

    private fun Rig.control() = control

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val GAME_CONTROL = 0x085916D4
        const val MONSTER_RN = 0x0300534E
        const val IWRAM = 0x03000000
    }
}
