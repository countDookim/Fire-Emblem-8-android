package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.event.EventConst
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * PC-3a WIRE: the real game loop ([Fe8Boot], whole ROM) from the Prologue's ending event to the
 * post-chapter save menu proc. The boot resumes the Prologue suspend save of
 * `ram_trace_prologue_resume`; once the player phase is idle the ending event is started the way
 * `RunPotentialWaitEvents` does (`EventEngine_Create`, `EV_EXEC_CUTSCENE`; the winning kill of
 * `ram_trace_prologue_end` is not replayed, so the state before the event is the resume save's,
 * not `f4893`) and skipped with Start. Its tail runs `MNC2`, which sets `EV_STATE_CHANGEGM`;
 * `EventEngine_OnEnd` then ends the Mus and `BMapMain`, and `GameControl` runs
 * `GameControl_PostChapterSwitch`, `GameControl_ChapterSwitch` and
 * `GameControl_CallPostChapterSaveMenu` as in `f9440..9444` of `ram_trace_prologue_end`.
 */
class PrologueEndingWireTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    private class Frames {
        var frame = 0
        var keys = 0
        var manual = false
    }

    private fun bootAtIdle(f: Frames): Fe8Boot {
        val save = ref.run("ram_trace_prologue_resume").sram("s01001")
        val storage = object : SaveStorage {
            override fun load(dest: ByteArray): Boolean {
                save.copyInto(dest)
                return true
            }

            override fun store(src: ByteArray) {}
        }
        val pulse = arrayOf<Any>(0, 1)
        val boot = Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (f.manual) f.keys else pulse[if (f.frame % 20 in 5..8) 1 else 0]
            } as () -> Int),
            RngService(),
            saveStorage = storage,
        )
        while (f.frame < 3000 && boot.engine.find(boot.playerPhaseScript)?.scrCur != 16) {
            boot.frame()
            f.frame++
        }
        assertEquals(16, boot.engine.find(boot.playerPhaseScript)?.scrCur, "idle player phase")
        f.manual = true
        return boot
    }

    private class Gc(val cursor: Int, val lock: Int)

    /** The capture's `GameControl` proc at [step]: script cursor and lock count. */
    private fun captured(run: ReferenceData.Run, step: Int): Gc {
        val gc = checkNotNull(ProcArrayDump.ofWatch(run.watch("procs"), step).find(GAME_CONTROL))
        return Gc(gc.scrCur, gc.u8(0x28))
    }

    @Test
    fun `the ending event reaches the post chapter save menu through the real game loop`() {
        assumeTrue(ref.available && File(assets, "baserom.gba").isFile, "reference data / ROM")
        val romNames = ProcArrayDump.romNames()
        assumeTrue(romNames.isNotEmpty(), "decomp nm file")
        val ending = romNames.entries.first { it.value == "EventScr_Prologue_EndingScene" }.key
        val run = ref.run("ram_trace_prologue_end")
        val capGc = ProcArrayDump.ofWatch(run.watch("procs"), 9441).find(GAME_CONTROL)!!
        val f = Frames()
        val boot = bootAtIdle(f)

        fun live() = boot.engine.procs.mapNotNull { it.script?.name }
        val gc = boot.engine.procs.first { it.script === boot.control.script }
        assertTrue("gProc_BMapMain" in live() && "gProcScr_PlayerPhase" in live())
        assertEquals(captured(run, 9440).lock, gc.lockCnt, "BMapMain holds the lock")

        boot.eventEngine.callEvent(ending, EventConst.EV_EXEC_CUTSCENE)
        var frames = 0
        // Start (skip) every 8 frames until BMapMain is gone.
        while (frames < 2000 && "gProc_BMapMain" in live()) {
            f.keys = if (frames % 8 in 2..3) START else 0
            boot.frame()
            f.frame++
            frames++
        }
        f.keys = 0
        assertTrue("gProc_BMapMain" !in live(), "BMapMain ended (EndBMapMain) within 2000 frames")
        // f9441: the teardown frame; Mu, PlayerPhase, the event engine and the DISP procs are gone.
        for (gone in listOf(
            "ProcScr_Mu", "gProcScr_PlayerPhase", "ProcScr_StdEventEngine", "gProc_MapTask",
            "sProc_BMVSync",
        )) {
            assertTrue(gone !in live(), "$gone ended with BMapMain")
        }
        assertEquals(captured(run, 9441).lock, gc.lockCnt, "f9441 lock released")
        assertEquals(captured(run, 9441).cursor, gc.scrCur, "f9441 cursor")
        val state = gc.state as GameControl.State
        assertEquals(capGc.u8(0x29), state.nextAction, "nextAction")
        assertEquals(capGc.u8(0x2A), state.nextChapter, "nextChapter")

        // f9442..f9444: PostChapterSwitch, ChapterSwitch (RN state, chapter) and the save menu.
        for (step in 9442..9444) {
            boot.frame()
            f.frame++
            val want = captured(run, step)
            assertEquals(want.cursor, gc.scrCur, "f$step cursor")
            assertEquals(want.lock, gc.lockCnt, "f$step lock")
        }
        assertEquals(state.nextChapter, boot.control.playSt.chapterIndex, "chapter switched")
        assertEquals(
            listOf(0, 1, 2).map { boot.rng.primary.word(it) },
            boot.control.gmMonsterRnState.toList(),
            "gGmMonsterRnState is the RN state ChapterSwitch stored",
        )
        assertEquals(SAVE_MENU, gc.child?.script?.name, "the save menu is GameControl's child")
        assertNull(boot.engine.find(boot.playerPhaseScript), "no player phase")
    }

    private companion object {
        const val GAME_CONTROL = 0x085916D4
        const val START = 0x08
        const val SAVE_MENU = "gProcScr_SaveMenuPostChapter"
    }
}
