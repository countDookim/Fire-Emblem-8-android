package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.Camera
import fe8.game.bm.PlaySt
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The phase-start cursor reset and camera move (M5-46) against per-frame RAM dumps: each frame
 * the data (gBmSt, gPlaySt, units, the CamMove fields and `sCameraAnimTable`) is seeded from the
 * previous dump, the ported code runs one frame and the camera, cursor, cursor display, the
 * proc fields and script positions are compared with the next dump. The procs themselves
 * persist from frame to frame.
 *
 * - `ram_trace_ch6_phase_start` f1683..f1710: ProcFun_ResetCursorPosition moves the cursor from
 *   (25, 12) to the leader at (5, 12) and EnsureCameraOntoPosition starts a CamMove (blocking
 *   the reset proc) that scrolls the camera y 0 to 80 over 17 frames (the map is larger than
 *   the screen).
 * - `ram_trace_first_phase` f19316..f19322: the same reset on a map that fits the screen: the
 *   cursor moves, the camera does not and no CamMove starts.
 * - `ram_trace_newgame_bm` f9784..f9806: EnsureCameraOntoPosition without a parent (as the
 *   opening event's camera command does) moves the camera (96, 80) to (96, 0).
 */
class CamMoveDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "ProcFun_ResetCursorPosition", "GetPlayerStartCursorPosition", "CamMove_OnInit",
        "CamMove_OnLoop", "EnsureCameraOntoPosition",
    )
    fun `ch6 phase start resets the cursor and scrolls the camera`() {
        val sim = sim("ram_trace_ch6_phase_start", 1683, 1710)
        sim.startReset()
        val compared = sim.runWindow()
        assertEquals(28, compared, "frames compared")
        assertTrue(sim.sawCamMove && sim.camMoveFrames == 19, "CamMove frames ${sim.camMoveFrames}")
    }

    @Test
    @VerifiesPort("ProcFun_ResetCursorPosition", "GetPlayerStartCursorPosition")
    fun `first phase resets the cursor without moving the camera`() {
        val sim = sim("ram_trace_first_phase", 19316, 19322)
        sim.startReset()
        assertEquals(7, sim.runWindow(), "frames compared")
        assertTrue(!sim.sawCamMove, "no CamMove")
    }

    @Test
    @VerifiesPort("CamMove_OnInit", "CamMove_OnLoop", "EnsureCameraOntoPosition")
    fun `camera move without a parent scrolls onto the cursor`() {
        val sim = sim("ram_trace_newgame_bm", 9784, 9806)
        sim.startEnsure(14, 0)
        assertEquals(23, sim.runWindow(), "frames compared")
        assertTrue(sim.sawCamMove, "CamMove seen")
    }

    private fun sim(run: String, first: Int, last: Int): Sim {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return Sim(run, first, last)
    }

    /**
     * World map stand-in: no node holds the chapter, so a non-story chapter (Ch6) is a SKIRMISH
     * and the leader is Eirika while she is deployed, as on the story maps.
     */
    private val noNodes = object : BattleMapNodes {
        override fun chapterId(node: Int) = -1
        override fun isCleared(node: Int) = false
        override fun encounters(node: Int) = 0
        override fun placementFlag(node: Int) = 0
    }

    private inner class Sim(val run: String, val first: Int, val last: Int) {
        val data = ref.run(run)
        val names = data.dumps.entries
            .filter { File(ref.root, "$run/${it.key}.ewram.bin").isFile }
            .associate { it.value to it.key }
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val units = UnitTable(RomView.ofRom(romFile.readBytes()))
        val camMove = CamMove(engine, bm)
        val reset = ResetCursorPosition(engine, bm, playSt, units, camMove, noNodes)
        val resetAddr = 0x0859AE00
        val camAddr = 0x0859A548
        val root: Proc = engine.start(
            ProcScript("test root", listOf(ProcScripts.block(), ProcScripts.end())),
            0,
        )
        var sawCamMove = false
        var camMoveFrames = 0
        var compared = 0

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun dump(frame: Int) = ProcArrayDump.ofEwram(ewram(frame))

        /** Seeds data from the dump of [frame]; the CamMove fields only if its proc is live. */
        fun seed(frame: Int) {
            val e = ewram(frame)
            bm.decode(e, BM_ST - EWRAM)
            playSt.decode(e, PLAY_ST - EWRAM)
            units.loadFrom(e)
            e.copyInto(camMove.animTable, 0, TABLE - EWRAM, TABLE - EWRAM + 0x100)
            val cam = ProcArrayDump.ofEwram(e).find(camAddr)
            if (cam != null) readFields(cam)
        }

        fun readFields(p: ProcArrayDump.Proc) {
            val f = camMove.fields
            f.toX = s16(p.u16(0x2C)); f.toY = s16(p.u16(0x2E))
            f.fromX = s16(p.u16(0x30)); f.fromY = s16(p.u16(0x32))
            f.watchedX = s16(p.u16(0x34)); f.watchedY = s16(p.u16(0x36))
            f.calibration = s16(p.u16(0x38)); f.distance = s16(p.u16(0x3A))
            f.frame = p.u32(0x3C); f.xCalibrated = p.u8(0x40).toByte().toInt()
        }

        fun startReset() {
            seed(first - 1)
            reset.start(root)
        }

        fun startEnsure(x: Int, y: Int) {
            seed(first - 1)
            camMove.ensureCameraOntoPosition(null, x, y)
            Camera.setCursorMapPosition(bm, x, y)
            runRoots()
        }

        fun runRoots() {
            for (t in 0 until 8) engine.run(engine.root(t))
        }

        /** Compares frames [first]..[last]; returns how many were compared. */
        fun runWindow(): Int {
            for (f in first..last) {
                if (f != first) {
                    seed(f - 1)
                    runRoots()
                }
                compare(f)
            }
            return compared
        }

        fun compare(f: Int) {
            val e = ewram(f)
            val want = BmSt().also { it.decode(e, BM_ST - EWRAM) }
            val at = "f$f"
            assertEquals(want.cameraX, bm.cameraX, "$at camera.x")
            assertEquals(want.cameraY, bm.cameraY, "$at camera.y")
            assertEquals(want.playerCursorX, bm.playerCursorX, "$at cursor.x")
            assertEquals(want.playerCursorY, bm.playerCursorY, "$at cursor.y")
            assertEquals(want.cursorTargetX, bm.cursorTargetX, "$at target.x")
            assertEquals(want.cursorTargetY, bm.cursorTargetY, "$at target.y")
            assertEquals(want.playerCursorDisplayX, bm.playerCursorDisplayX, "$at display.x")
            assertEquals(want.playerCursorDisplayY, bm.playerCursorDisplayY, "$at display.y")
            val wantPlay = PlaySt().also { it.decode(e, PLAY_ST - EWRAM) }
            assertEquals(wantPlay.xCursor, playSt.xCursor, "$at gPlaySt.xCursor")
            assertEquals(wantPlay.yCursor, playSt.yCursor, "$at gPlaySt.yCursor")

            val procs = ProcArrayDump.ofEwram(e)
            val wantReset = procs.find(resetAddr)
            val gotReset = engine.find(reset.script)
            assertEquals(wantReset == null, gotReset == null, "$at reset proc live")
            if (wantReset != null) {
                assertEquals(wantReset.scrCur, gotReset!!.scrCur, "$at reset cmd")
            }

            val wantCam = procs.find(camAddr)
            val gotCam = engine.find(camMove.script)
            assertEquals(wantCam == null, gotCam == null, "$at CamMove proc live")
            if (wantCam != null) {
                sawCamMove = true
                camMoveFrames++
                assertNotNull(gotCam)
                assertEquals(wantCam.scrCur, gotCam!!.scrCur, "$at CamMove cmd")
                val g = camMove.fields
                assertEquals(s16(wantCam.u16(0x2C)), g.toX, "$at to.x")
                assertEquals(s16(wantCam.u16(0x2E)), g.toY, "$at to.y")
                assertEquals(s16(wantCam.u16(0x30)), g.fromX, "$at from.x")
                assertEquals(s16(wantCam.u16(0x32)), g.fromY, "$at from.y")
                assertEquals(s16(wantCam.u16(0x34)), g.watchedX, "$at watched.x")
                assertEquals(s16(wantCam.u16(0x36)), g.watchedY, "$at watched.y")
                if (wantCam.scrCur == 4) {
                    assertEquals(s16(wantCam.u16(0x38)), g.calibration, "$at calibration")
                    assertEquals(s16(wantCam.u16(0x3A)), g.distance, "$at distance")
                    assertEquals(wantCam.u32(0x3C), g.frame, "$at frame")
                    val xCal = wantCam.u8(0x40).toByte().toInt()
                    assertEquals(xCal, g.xCalibrated, "$at xCalibrated")
                    for (i in 0 until 0x100) {
                        assertEquals(e[TABLE - EWRAM + i], camMove.animTable[i], "$at table[$i]")
                    }
                }
            } else {
                assertNull(gotCam)
            }
            compared++
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val TABLE = 0x0202BD44

        fun s16(v: Int): Int = v.toShort().toInt()
    }
}
