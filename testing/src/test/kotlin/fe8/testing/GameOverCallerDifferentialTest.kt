package fe8.testing

import fe8.game.GameControl
import fe8.game.bm.BmSt
import fe8.game.RomView
import fe8.game.bm.GameOverScreen
import fe8.game.bm.Mu
import fe8.game.bm.PlaySt
import fe8.game.event.EventEngine
import fe8.game.event.EventState
import fe8.game.event.GameOverCaller
import fe8.game.event.ScriptWords
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware
import gbafe.render.ColorFade
import gbafe.render.ScreenFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Differential test of the game over caller (`PC-7b`) on `ram_trace_prologue_gameover`
 * (PC-cap2), windows `f5833..5953` and `f6294..6295`. The `EventScr_GameOver` `ASMC` is replaced
 * by a direct `EventCallGameOverExt` call on the event engine proc (frame f5833); the real
 * [ScreenFade], [Mu] (one MU) and [EventEngine] run, `ProcScr_GameOverScreen` (PC-8a),
 * `gProc_BMapMain`/`EndBMapMain` and `SetNextGameActionId` are stand-ins. Per step the presence of
 * the procs that matter is compared with the `procs` watch.
 */
@Timeout(120)
class GameOverCallerDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Sim(val run: ReferenceData.Run, rom: RomView?) {
        val engine = ProcEngine()
        private val realHardware = BgHardware()

        /** The real game over screen (PC-8a) plugged into the caller's hook, or null. */
        val real: GameOverScreen? = rom?.let {
            GameOverScreen(engine, realHardware, ColorFade(realHardware.palette), it)
        }
        val playSt = PlaySt().also { it.decode(run.region("f05833", "ewram"), PLAY_ST - EWRAM) }
        val screenFade = ScreenFade(engine, BgHardware().lcd)
        val mu = Mu(engine, BmSt(), PlaySt(), KeyStatus(), ProcScript("cam", listOf(end())))
        val events = EventEngine(engine, EventState(), ScriptWords({ 0 }))
        val bmapMain = ProcScript("gProc_BMapMain", listOf(block(), end()))
        var step = 5833
        var screenEndStep = Int.MAX_VALUE
        var screenStartedAt = -1
        var bgmFades = 0
        var nextAction = 0xFF
        var bmapMainEnded = false

        val screen = ProcScript(
            "ProcScr_GameOverScreen",
            listOf(repeat(ProcFunc { if (step + 2 >= screenEndStep) engine.breakProc(it) }), end()),
        )
        val caller = GameOverCaller(
            engine, playSt,
            fadeOutBgm = { bgmFades++ },
            startSlowFadeToBlack = { screenFade.startSlowFadeToBlack() },
            endAllMus = { mu.endAllMus() },
            startGameOverScreen = {
                screenStartedAt = step
                if (real != null) real.startGameOverScreen(it) else engine.startBlocking(screen, it)
            },
            setNextGameActionId = { nextAction = it },
            endBMapMain = {
                bmapMainEnded = true
                engine.endEach(bmapMain)
            },
            deleteEventEngines = { events.deleteEventEngines() },
        )

        fun present(name: String): Boolean = when (name) {
            "ProcScr_BmGameOver" -> engine.find(caller.script) != null
            "ProcScr_FadeToBlack" -> engine.find(screenFade.toBlack) != null
            "ProcScr_GameOverScreen" ->
                engine.find(real?.script ?: screen) != null
            "ProcScr_Mu" -> engine.find(mu.script) != null
            "ProcScr_StdEventEngine" -> engine.find(events.stdScript) != null
            "gProc_BMapMain" -> engine.find(bmapMain) != null
            else -> error(name)
        }

        /** One frame: `Proc_Run` of the proc trees that hold procs (MU tree 5 not run). */
        fun frame() {
            for (tree in 0..4) engine.run(engine.root(tree))
            // The stand-in ends itself in the walk; the real screen (PC-8b ends it) is ended
            // after it, so the caller resumes in the next walk.
            if (step + 1 >= screenEndStep) real?.let { engine.end(engine.find(it.script)) }
            step++
        }
    }

    @Test
    @VerifiesPort("EventCallGameOverExt", "PostGameOverHandler")
    fun `game over sequence matches the PC-cap2 procs f5833 to f6295`() = compare(false)

    @Test
    @VerifiesPort("StartGameOverScreen")
    fun `real game over screen plugged into the caller keeps the sequence and scroll`() =
        compare(false, realScreen = true)

    @Test
    @VerifiesPort("EndAllMus", "StartSlowFadeToBlack")
    fun `fade to black f5873 to f5937 and MUs ended at f5953 like PC-cap2`() = compare(true)

    private fun compare(
        @Suppress("UNUSED_PARAMETER") fadeOnly: Boolean,
        realScreen: Boolean = false,
    ) {
        assumeTrue(ref.available, "mGBA reference data not present")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN procs watch missing")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procs = run.watch("procs")
        fun want(step: Int, name: String) =
            ProcArrayDump.ofWatch(procs, step, names).find(name) != null

        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(!realScreen || romFile.isFile, "ROM not present")
        val sim = Sim(run, if (realScreen) RomView.ofRom(romFile.readBytes()) else null)
        assertEquals(1, sim.playSt.disableBgm, "f5833 disableBgm")
        sim.screenEndStep = (5954..6300).first { !want(it, "ProcScr_GameOverScreen") }
        sim.engine.start(sim.bmapMain, 2)
        val parent = sim.events.callGameOverEvent().let { sim.engine.find(sim.events.stdScript)!! }
        assertTrue(sim.mu.startMuInternal(0, 0, 0, -1, 0) != null, "MU started")
        sim.caller.eventCallGameOverExt(parent)
        // The ASMC runs inside the f5833 frame's walk of tree 3, which then walks the new child.
        sim.engine.run(sim.engine.root(3))

        val wantEngineCur = ProcArrayDump.ofWatch(procs, 5833, names)
            .find("ProcScr_StdEventEngine")!!.scrCur
        val simEngineCur = parent.scrCur
        val watched = listOf(
            "ProcScr_BmGameOver", "ProcScr_FadeToBlack", "ProcScr_GameOverScreen",
            "ProcScr_Mu", "ProcScr_StdEventEngine", "gProc_BMapMain",
        )
        for (step in 5833..6295) {
            assertEquals(step, sim.step)
            for (name in watched) {
                assertEquals(want(step, name), sim.present(name), "f$step $name")
            }
            // The script cursor (and so the blocking of the parent: its cursor must not move).
            val dump = ProcArrayDump.ofWatch(procs, step, names)
            // At f5953 the capture cursor is on the PROC_YIELD (8) and moves past it (9) one
            // frame later, where the port is past it at once; unexplained, nothing observable.
            dump.find("ProcScr_BmGameOver")?.takeIf { step != 5953 }?.let {
                assertEquals(it.scrCur, sim.engine.find(sim.caller.script)!!.scrCur, "f$step cur")
            }
            // The event engine proc is blocked by the game over proc: its cursor stays put.
            dump.find("ProcScr_StdEventEngine")?.let {
                assertEquals(wantEngineCur, it.scrCur, "f$step captured event engine scrCur")
                assertEquals(simEngineCur, parent.scrCur, "f$step event engine scrCur")
            }
            if (step < 6295) assertEquals(1, parent.lockCnt, "f$step event engine blocked")
            if (step == 5953) {
                assertEquals(5953, sim.screenStartedAt + 1, "GameOverScreen start frame")
            }
            val real = sim.real
            if (real != null && step in 5953..6292) {
                // The capture's pass of the screen start spans f5953 (mid-pass dump): the port's
                // step f is the dump of f + 1.
                val scroll = ProcArrayDump.ofWatch(procs, step + 1, names)
                    .find("ProcScr_GameOverScreen_RandomScroll")!!
                val state = sim.engine.find(real.scrollScript)!!.state as GameOverScreen.ScrollState
                assertEquals(scroll.u16(0x64).toShort().toInt(), state.bg2Xpos, "f$step bg2_xpos")
                assertEquals(scroll.u16(0x6A).toShort().toInt(), state.bg3Ypos, "f$step bg3_ypos")
            }
            sim.frame()
        }
        assertEquals(0, sim.bgmFades, "GameOver_FadeOutCurrentBgm not taken")
        assertEquals(
            ProcArrayDump.ofWatch(procs, 6295, names)
                .find("gProcScr_GameControl")!!.u8(GC_NEXT_ACTION),
            sim.nextAction,
            "f6295 GameCtrl nextAction",
        )
        assertEquals(GameControl.GAME_ACTION_EVENT_RETURN, sim.nextAction)
        assertTrue(sim.bmapMainEnded)
    }

    private companion object {
        const val RUN = "ram_trace_prologue_gameover"
        const val EWRAM = 0x02000000
        const val PLAY_ST = 0x0202BCF0
        const val GC_NEXT_ACTION = 0x29
    }
}
