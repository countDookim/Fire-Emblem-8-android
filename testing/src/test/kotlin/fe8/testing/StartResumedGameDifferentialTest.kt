package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [BattleMapStart.startResumedGame] against `ram_trace_prologue_resume` r01149, the first frame
 * after the prologue's `GameCtrl_StartResumedGame` (`gBmSt.just_resumed` 0 to 1, cursor 5,5).
 * The state before the call is `gPlaySt` from the dump and a `gBmSt` of 0xFF bytes but `lock`.
 *
 * Compared: all 0x40 bytes of `gBmSt` (cleared, cursor, cursor target and display positions,
 * camera, map size limits, `just_resumed`, semaphore). `gActionData.suspendPointType` and the
 * `MapMain_ResumeFrom*` variants are stand-ins (M6-10), so the suspend point switch has no
 * ground truth here; the prologue's camera is at 0 (map smaller than the screen).
 */
class StartResumedGameDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("GameCtrl_StartResumedGame")
    fun `gBmSt after the resumed game start matches the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val view = RomView.ofRom(romFile.readBytes())
        val ew = ref.run("ram_trace_prologue_resume").region("r01149", "ewram")
        val playSt = PlaySt().apply { decode(ew, PLAY_ST - GbaMem.EWRAM) }
        val post = ByteArray(BM_ST_SIZE).also {
            ew.copyInto(it, 0, BM_ST - GbaMem.EWRAM, BM_ST - GbaMem.EWRAM + BM_ST_SIZE)
        }
        val bmSt = BmSt()
        bmSt.decode(ByteArray(BM_ST_SIZE) { -1 }, 0)
        bmSt.lock = post[1].toInt()

        val engine = ProcEngine()
        val control = engine.start(CONTROL, 3)
        val hw = BgHardware()
        val chapters = ChapterData(view)
        val map = BmMap(view, chapters, bmSt)
        val start = BattleMapStart(
            engine, playSt, bmSt, hw, chapters, map, ChapterMapGfx(view, chapters, hw, playSt),
            BmMapRender(bmSt, map, hw), Fe8EventFlags.create(), RngService(), GameClock(),
        )
        start.startResumedGame(control)

        val got = ByteArray(BM_ST_SIZE).also { bmSt.encode(it, 0) }
        for (i in got.indices) assertEquals(post[i], got[i], "gBmSt+0x${i.toString(16)}")
        assertEquals(1, control.lockCnt, "game control lockCnt")
    }

    private companion object {
        const val PLAY_ST = 0x0202BCF0
        const val BM_ST = 0x0202BCB0
        const val BM_ST_SIZE = 0x40

        /** A proc standing for `gProcScr_GameControl`, which calls the start. */
        val CONTROL = ProcScript(
            "gProcScr_GameControl",
            listOf(mark(0x0B), label(0), sleep(0), goto(0)),
        )
    }
}
