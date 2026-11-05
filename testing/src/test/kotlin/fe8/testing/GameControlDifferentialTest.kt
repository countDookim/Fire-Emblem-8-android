package fe8.testing

import fe8.game.GameControl
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gba.testkit.WatchTrace
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The game control proc past the save menu against `ram_trace_prologue` (the `procs` and
 * `gplayst` watches, one record per frame; same inputs as `ram_trace_newgame`).
 *
 * The trace shows `gProcScr_GameControl` (script 0x085916D4) at these steps:
 * - step 1212: command 46 (`GameControl_SwitchPostSaveMenu`) is next, `nextAction` 0. Step 1213:
 *   the proc already ran 46, `GameCtrl_CheckNewGameAndBranch`,
 *   `GameCtrl_CheckGameCompleteAndBranch`, `GameControl_RememberChapterId` and
 *   `GameCtrlStartIntroMonologue` and yielded at command 60, a blocking `gProcScr_OpSubtitle`
 *   child (`StartIntroMonologue`) as the only child.
 * - step 4898: the monologue ended, the proc started `ProcScr_WorldMapWrapper` (command 61).
 * - step 9313: the world map ended, the proc ran `EndWM`, `sub_8009E28`, yielded (command 64).
 *
 * Each segment seeds the proc fields and `gPlaySt` from the step before, runs one frame with
 * stand-in children, and compares script position, fields, `gPlaySt` bytes and children with the
 * step after. Only chapter 0, `nextAction` 0, `save_menu_type` 0 and no state bits occur, so the
 * other branches are not covered, so `GameControl_SwitchPostSaveMenu` (only the event-return
 * branch runs), `GameCtrl_CheckNewGameAndBranch` and `GameCtrl_CheckGameCompleteAndBranch`
 * (only their fall-through) are not tagged; `GameControl_PostChapterSwitch`,
 * `GameControl_RestoreChapterId` and `GameControlHandlePostNormalOrExtraChapter` do not run
 * before the battle map and are not tagged. `sub_8009E28` only has its hook checked.
 */
class GameControlDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun traces(): Pair<WatchTrace, WatchTrace> {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        assertTrue(
            run.hasWatch("procs") && run.hasWatch("gplayst"),
            "ram_trace_prologue has no procs / gplayst watch",
        )
        return run.watch("procs") to run.watch("gplayst")
    }

    /** A game control proc seeded from step [step] of the traces, and the hook call counts. */
    private fun seed(procs: WatchTrace, playSt: WatchTrace, step: Int, child: ProcScript?): Seed {
        val dump = ProcArrayDump.ofWatch(procs, step)
        val ctrl = checkNotNull(dump.find(GAME_CONTROL)) { "no game control proc at step $step" }
        val engine = ProcEngine()
        val control = GameControl(engine, KeyStatus(), { STAND_IN }, { STAND_IN })
        val seed = Seed(engine, control)
        control.hooks.gmDataInit = ProcFunc { seed.count("GmDataInit") }
        control.hooks.startIntroMonologue = ProcFunc {
            seed.count("StartIntroMonologue")
            engine.startBlocking(ProcScript("gProcScr_OpSubtitle (stand-in)", listOf(block())), it)
        }
        control.hooks.endWorldMap = ProcFunc { seed.count("EndWM") }
        control.hooks.blendSetup = ProcFunc { seed.count("sub_8009E28") }
        control.hooks.worldMapWrapperScript =
            ProcScript("ProcScr_WorldMapWrapper (stand-in)", listOf(block()))
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
        control.playSt.decode(playSt.step(step), 0)
        if (child != null) engine.startBlocking(child, proc)
        seed.proc = proc
        return seed
    }

    /** Compares our proc after one frame with the step [step] of the traces; returns the dump. */
    private fun assertMatches(
        seed: Seed,
        procs: WatchTrace,
        playSt: WatchTrace,
        step: Int,
    ): ProcArrayDump {
        val dump = ProcArrayDump.ofWatch(procs, step)
        val ctrl = checkNotNull(dump.find(GAME_CONTROL))
        val proc = seed.proc
        val state = proc.state as GameControl.State
        assertEquals(ctrl.scrCur, proc.scrCur, "step $step scrCur")
        assertEquals(ctrl.u8(0x29), state.nextAction, "step $step nextAction")
        assertEquals(ctrl.u8(0x2A), state.nextChapter, "step $step nextChapter")
        assertEquals(ctrl.u8(0x2B), state.idleStatus, "step $step idle_status")
        assertEquals(ctrl.u8(0x2C), state.unk2C, "step $step unk2C")
        assertEquals(ctrl.u16(0x2E), state.unk2E, "step $step unk2E")
        assertEquals(ctrl.u8(0x30), state.chIndex, "step $step ch_index")
        val ours = ByteArray(PLAY_ST_SIZE)
        seed.control.playSt.encode(ours, 0)
        assertArrayEquals(playSt.step(step), ours, "step $step gPlaySt")
        return dump
    }

    @Test
    @VerifiesPort(
        "GameControl_RememberChapterId",
        "GameCtrlStartIntroMonologue",
    )
    fun `save menu return reaches the intro monologue like the game`() {
        val (procs, playSt) = traces()
        val seed = seed(procs, playSt, 1212, null)
        seed.engine.run(seed.engine.root(3))
        val dump = assertMatches(seed, procs, playSt, 1213)
        assertEquals(1, seed.calls["GmDataInit"], "GmDataInit once on event return")
        assertEquals(1, seed.calls["StartIntroMonologue"], "StartIntroMonologue once")
        val subtitle = checkNotNull(dump.find(OP_SUBTITLE)) { "no OpSubtitle proc at step 1213" }
        assertEquals(dump.find(GAME_CONTROL)!!.address, subtitle.u32(0x14), "monologue parent")
        assertNotNull(seed.proc.child, "our monologue child")
        assertEquals(1, seed.proc.lockCnt, "blocked by the monologue")
    }

    @Test
    fun `monologue end starts the world map`() {
        val (procs, playSt) = traces()
        val seed = seed(procs, playSt, 4897, ProcScript("monologue (stand-in)", listOf(block())))
        seed.engine.end(seed.proc.child)
        seed.engine.run(seed.engine.root(3))
        val dump = assertMatches(seed, procs, playSt, 4898)
        assertEquals(0, seed.calls["StartIntroMonologue"] ?: 0, "no second monologue")
        assertEquals(true, dump.find(WORLD_MAP_WRAPPER) != null, "world map proc at step 4898")
        assertNotNull(seed.proc.child, "our world map child")
    }

    @Test
    fun `world map end runs the blend setup and yields`() {
        val (procs, playSt) = traces()
        val world = ProcScript("ProcScr_WorldMapWrapper (stand-in)", listOf(block()))
        val seed = seed(procs, playSt, 9312, world)
        seed.engine.end(seed.proc.child)
        seed.engine.run(seed.engine.root(3))
        assertMatches(seed, procs, playSt, 9313)
        assertEquals(1, seed.calls["EndWM"], "EndWM once")
        assertEquals(1, seed.calls["sub_8009E28"], "sub_8009E28 once")
    }

    private companion object {
        const val GAME_CONTROL = 0x085916D4
        const val OP_SUBTITLE = 0x08AA21BC
        const val WORLD_MAP_WRAPPER = 0x08A3DD50
        const val PLAY_ST_SIZE = 0x4C
        val STAND_IN = ProcScript("stand-in", emptyList())
    }
}

/** A seeded game control proc, its engine and the hook call counts. */
private class Seed(val engine: ProcEngine, val control: GameControl) {
    lateinit var proc: Proc
    val calls = HashMap<String, Int>()

    fun count(name: String) {
        calls[name] = calls.getOrElse(name) { 0 } + 1
    }
}
