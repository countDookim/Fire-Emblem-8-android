package fe8.testing

import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.Mu
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.MuUnit
import fe8.game.bm.PlaySt
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The MU walk of `mu.c` stepped one frame at a time against per-frame RAM dumps.
 *
 * The MU is started by [Mu.startMu] (verified by [MuStartDifferentialTest]) from the unit as dumped
 * at the start frame. Each later frame runs `Mu_OnLoop` once and compares the `ProcScr_Mu` fields
 * (`x_q4`, `y_q4`, `move_clock_q4`, `state`, `facing`, `step_sound_clock`, `moveConfig`) and the
 * `sMuConfig[0]` cursor (`pc`) with that frame's dump, plus `gBmSt.camera` (cam_b is off in the
 * dumps, so mu.c must leave the camera alone). The dumps are taken after the frame's procs ran,
 * and no frame in these windows is a lag frame (every dump advances `move_clock_q4` by exactly
 * one step), so
 * dump N is compared with step N. `ram_trace_newgame_bm` has `r*` dumps every frame except the
 * full-dump frames f9679/f9709/f9739/f9769, where the `f*` dump of the same frame is used.
 *
 * Inputs that come from outside mu.c and are not compared: the event code's `SetMuMoveScript` call
 * (made on the frame the dump first shows `MU_STATE_MOVEMENT` after `MU_STATE_INACTIVE`, with the
 * script bytes of `sMuConfig[0].movescr` in that frame's dump), the key state of that frame,
 * `cam_b` at the start of a leg (set by event code), the class data (read from the ROM at test
 * time) and the initial `gBmSt`/`gPlaySt`. The end of the proc (`EndMu`, which the event code
 * calls) is stepped when the dump has no `ProcScr_Mu`.
 */
class MuMoveDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "RunMuMoveScript",
        "Mu_OnStateMovement",
        "GetMuQ4MovementSpeed",
        "Mu_OnLoop",
        "EndMu",
        "MU_OnEnd",
        "ApplyMoveScriptToCoordinates",
    )
    fun `first MU walk of the new game matches every per-frame dump f9675 to f9782`() {
        assumeAvailable()
        val walk = Walk("ram_trace_newgame_bm", 9674, 9782)
        walk.run()
        assertEquals(3, walk.legs, "three scripts (the second and third start after a HALT)")
        assertEquals(108, walk.compared, "frames compared")
        walk.assertEndsOnUnit()
    }

    @Test
    @VerifiesPort("RunMuMoveScript", "Mu_OnStateMovement", "Mu_OnLoop", "EndMu", "MU_OnEnd")
    fun `MU walk of the prologue talk run matches f10580 to f10621 and f10624 to f10633`() {
        assumeAvailable()
        val first = Walk("ram_trace_prologue_talk", 10579, 10621)
        first.run()
        assertEquals(1, first.legs, "one script")
        assertEquals(42, first.compared, "frames compared")
        first.assertEndsOnUnit()
        val second = Walk("ram_trace_prologue_talk", 10623, 10633)
        second.run()
        assertEquals(1, second.legs, "one script")
        assertEquals(10, second.compared, "frames compared")
        second.assertEndsOnUnit()
    }

    private fun assumeAvailable() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
    }

    private inner class Walk(val run: String, val seed: Int, val last: Int) {
        val data = ref.run(run)
        val rom = romFile.readBytes()
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val keys = KeyStatus()
        val camMove = CamMove(engine, bm)
        val mu = Mu(engine, bm, playSt, keys, camMove.script, ClassHooks())
        var legs = 0
        var compared = 0
        val unitAddr: Int

        init {
            val e = ewram(seed)
            bm.decode(e, BM_ST - GbaMem.EWRAM)
            playSt.decode(e, PLAY_ST - GbaMem.EWRAM)
            unitAddr = ProcArrayDump.ofEwram(e).find(PROC_SCR_MU)!!.u32(0x2C)
        }

        fun name(frame: Int): String {
            val r = "r%05d".format(frame)
            return if (File(ref.root, "$run/$r.ewram.bin").isFile) r else "f%05d".format(frame)
        }

        fun ewram(frame: Int): ByteArray = data.region(name(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(name(frame), "iwram")

        fun muDump(frame: Int) = ProcArrayDump.ofEwram(ewram(frame)).find(PROC_SCR_MU)

        fun unit(e: ByteArray): MuUnit {
            fun b(o: Int) = e[unitAddr - GbaMem.EWRAM + o].toInt() and 0xFF
            val classPtr = GbaMem.u32(e, GbaMem.EWRAM, unitAddr + 4)
            return MuUnit(
                address = unitAddr,
                xPos = b(0x10).toByte().toInt(),
                yPos = b(0x11).toByte().toInt(),
                classId = (classPtr - GCLASS_DATA) / CLASS_SIZE + 1,
                state = GbaMem.u32(e, GbaMem.EWRAM, unitAddr + 0x0C),
                spritePalette = iwram(seed)[CFG - GbaMem.IWRAM + 1].toInt() and 0xFF,
            )
        }

        fun script(frame: Int): IntArray {
            val iw = iwram(frame)
            return IntArray(0x40) { iw[CFG - GbaMem.IWRAM + 5 + it].toInt() }
        }

        fun run() {
            val proc = checkNotNull(mu.startMu(unit(ewram(seed)))) { "StartMu" }
            val s = proc.state as MuProc
            var prevState = s.state
            var legEnd = IntArray(2)
            for (f in seed + 1..last) {
                val d = muDump(f)
                if (d == null) {
                    mu.endMu(s)
                    assertNull(engine.find(mu.script), "f$f: proc ended")
                    assertEquals(
                        iwram(f)[CFG - GbaMem.IWRAM].toInt() and 0xFF,
                        mu.configs[0].slot,
                        "f$f: config.slot after MU_OnEnd",
                    )
                    compared++
                    return
                }
                keys.readFrom(ewram(f), KEY_STATUS - GbaMem.EWRAM)
                if (prevState == Mu.MU_STATE_INACTIVE && d.u8(0x3F) == Mu.MU_STATE_MOVEMENT) {
                    // The event code starts the next leg (SetMuMoveScript) before the MU proc.
                    legs++
                    val legScript = script(f)
                    mu.setMuMoveScript(s, legScript)
                    // The event code turns the MU camera off (not in mu.c's walk path).
                    if (d.u8(0x3E) == 0) mu.disableMuCamera(s)
                    legEnd = intArrayOf(s.xQ4 shr 8, s.yQ4 shr 8)
                    mu.applyMoveScriptToCoordinates(legEnd, legScript)
                }
                mu.onLoop(s)
                compare(f, d, s)
                if (prevState == Mu.MU_STATE_MOVEMENT && s.state == Mu.MU_STATE_INACTIVE) {
                    assertEquals(legEnd[0], s.xQ4 shr 8, "f$f: script end tile x")
                    assertEquals(legEnd[1], s.yQ4 shr 8, "f$f: script end tile y")
                }
                prevState = s.state
                compared++
            }
        }

        fun compare(f: Int, d: ProcArrayDump.Proc, s: MuProc) {
            assertEquals(d.u8(0x3F), s.state, "f$f: state")
            assertEquals(d.u8(0x42), s.facing, "f$f: facing")
            assertEquals(d.u8(0x43), s.stepSoundClock, "f$f: step_sound_clock")
            assertEquals(d.u16(0x48), s.moveClockQ4, "f$f: move_clock_q4")
            assertEquals(d.u16(0x4A), s.moveConfig and 0xFFFF, "f$f: moveConfig")
            assertEquals(d.u16(0x4C), s.xQ4 and 0xFFFF, "f$f: x_q4")
            assertEquals(d.u16(0x4E), s.yQ4 and 0xFFFF, "f$f: y_q4")
            assertEquals(d.u8(0x3E), if (s.camB) 1 else 0, "f$f: cam_b")
            val pc = iwram(f)[CFG - GbaMem.IWRAM + 4].toInt() and 0xFF
            assertEquals(pc, mu.configs[0].pc, "f$f: pc")
            val want = BmSt().also { it.decode(ewram(f), BM_ST - GbaMem.EWRAM) }
            assertEquals(want.cameraX, bm.cameraX, "f$f: camera.x")
            assertEquals(want.cameraY, bm.cameraY, "f$f: camera.y")
        }

        /** The MU's final tile is the unit's tile in the dump of the frame the proc ended. */
        fun assertEndsOnUnit() {
            val end = ewram(last)
            val x = end[unitAddr - GbaMem.EWRAM + 0x10].toInt()
            val y = end[unitAddr - GbaMem.EWRAM + 0x11].toInt()
            val s = mu.configs[0].mu!!
            assertEquals(x, s.xQ4 shr 8, "unit x")
            assertEquals(y, s.yQ4 shr 8, "unit y")
            assertTrue(s.state == Mu.MU_STATE_INACTIVE, "walk ended halted")
        }

        /** Class data from the ROM (read at test time, not copied). */
        inner class ClassHooks : MuHooks {
            private fun classByte(jid: Int, o: Int): Int =
                rom[GCLASS_DATA - ROM_BASE + (jid - 1) * CLASS_SIZE + o].toInt() and 0xFF

            override fun classAttributes(jid: Int): Int = classByte(jid, 0x28) or
                (classByte(jid, 0x29) shl 8) or (classByte(jid, 0x2A) shl 16) or
                (classByte(jid, 0x2B) shl 24)

            override fun classSlowWalking(jid: Int): Int = classByte(jid, 0x07)
        }
    }

    private companion object {
        const val PROC_SCR_MU = 0x089A2C48
        const val GCLASS_DATA = 0x08807164
        const val CLASS_SIZE = 0x54
        const val CFG = 0x03001900
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val KEY_STATUS = 0x02024CC0
        const val ROM_BASE = 0x08000000
    }
}
