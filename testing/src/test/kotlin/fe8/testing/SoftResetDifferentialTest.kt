package fe8.testing

import fe8.game.GameControl
import fe8.game.GameLoop
import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnimHs
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.core.time.GameClock
import gbafe.input.KeyComboReset
import gbafe.input.KeyStatus
import gbafe.input.SoftwareResetFlag
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `IsSoftwareReset` and the intro after a soft reset against `ram_trace_soft_reset` (boot to the
 * title, A+B+START+SELECT held at f1001-f1004, B held at f1020-f1022; RAM every frame f995..f1200;
 * looked up under `$FE8_ASSETS/reference/out`, skipped if absent). `gSoftwareResetFlag` is 1 from
 * f1003; the restarted intro skips the health & safety screen (`PrepareHealthAndSafetyScreen` sends
 * the proc to label 0x3E7) and shows the Nintendo logo, where B is honoured only because of the
 * flag (`TryTickHSScreenExit`): label 0 at f1020. Our intro runs from the restart with the flag
 * read from the dump; frames f1010..f1024 are compared.
 *
 * Known divergence: our `EarlyStartUi` does the Nintendo-logo load and the first fade step in one
 * frame, where the original splits them over f1007/f1008. The test absorbs this with one extra lag
 * frame, so the reset path's first frames (f1007..f1009) are not exact.
 */
class SoftResetDifferentialTest {
    private val sym = RamSymbols.fromEnvironment()
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
    private val romFile = File(assets, "baserom.gba")
    private val ref = ReferenceData(File(assets, "reference/out"))

    @Test
    @VerifiesPort("PrepareHealthAndSafetyScreen")
    fun `restarted intro skips health and safety and honours B on the logo`() {
        val runDir = File(assets, "reference/out/ram_trace_soft_reset")
        assumeTrue(
            romFile.isFile && File(runDir, "manifest.txt").isFile,
            "ROM / ram_trace_soft_reset not present",
        )
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_soft_reset")
        fun ew(f: Int) = run.region("r%05d".format(f), "ewram")
        fun iw(f: Int) = run.region("r%05d".format(f), "iwram")
        fun blob(at: Int) = OpAnimHs.Blob(rom, at - 0x08000000)
        fun romPal(at: Int, n: Int) =
            ShortArray(n) { GbaMem.u16(rom, 0x08000000, at + 2 * it).toShort() }
        val flagAt = sym["gSoftwareResetFlag"].address - GbaMem.IWRAM
        val lcdAt = sym["gLCDControlBuffer"].address - GbaMem.IWRAM
        val palAt = sym["gPaletteBuffer"].address
        fun palette(e: ByteArray) =
            ShortArray(512) { GbaMem.u16(e, GbaMem.EWRAM, palAt + 2 * it).toShort() }

        // The reset: flag clear before, set by AgbMain's WAITCNT test (SetSoftwareResetFlag).
        assertEquals(0, iw(1001)[flagAt].toInt(), "flag before the reset")
        assertEquals(1, iw(1003)[flagAt].toInt(), "flag after the reset")
        val flag = SoftwareResetFlag()
        assertFalse(flag.isSoftwareReset())
        flag.set(iw(1003)[flagAt].toInt() != 0)
        assertTrue(flag.isSoftwareReset())

        val first = 1007 // first dump holding the restarted intro proc
        assertEquals(
            null, ProcArrayDump.ofEwram(ew(first - 1)).find(PROC_SCRIPT)?.offset,
            "no proc before f$first",
        )
        val e0 = ew(first)
        val keyAt = sym["sKeyStatusBuffer"].address - GbaMem.EWRAM
        val keys = KeyStatus().apply { readFrom(ew(first - 1), keyAt) }
        val combo = KeyComboReset()
        val env = EarlyStartUi.Env(
            palette(ew(first - 1)), ShortArray(512),
            LcdControl().apply { readFrom(iw(first - 1), lcdAt) }, keys,
            romPal(0x08B125F4, 16), romPal(0x08B10ABC, 16), romPal(0x08B11864, 48),
            softwareReset = { flag.isSoftwareReset() },
            gfx = EarlyStartUi.Gfx(
                blob(0x08B11D0C), blob(0x08B118C4), blob(0x08B11C0C), blob(0x08B10630),
                blob(0x08B104D8), blob(0x08B10CA4), blob(0x08B10ADC),
            ),
            keyComboReset = combo,
        )
        val hw = env.hardware
        val engine = ProcEngine()
        val ui = EarlyStartUi(engine, env)
        val ctrl = GameControl(engine, keys, { ui.script }, { OP_ANIM_STAND_IN })
        val gameCtrl = ctrl.startGame()
        var inputFrame = 0
        val clock = GameClock()
        val loop = GameLoop(
            engine, keys, clock, keyInput = { run.inputs[inputFrame - 1] },
            overran = { if (hw.modifiedBgs != 0) 1 else 0 }, flush = { hw.flushBackgrounds() },
        )
        fun frame(f: Int) {
            inputFrame = f
            loop.frame()
        }
        // The restarted intro proc appears at f1007 and stops after the Nintendo screen load (the
        // decompression overruns the frame on hardware); our engine runs the load and the first
        // fade step in one frame, so the first comparable dump is f1008.
        assertEquals(10, ProcArrayDump.ofEwram(e0).find(PROC_SCRIPT)!!.scrCur, "f$first scrCur")
        while (gameCtrl.child == null) frame(1)
        frame(first + 1) // the lag frame after the load (f1008 already shows the finished step)
        val proc = gameCtrl.child!!
        val st = proc.state as OpAnimHs.Proc
        val d0 = ProcArrayDump.ofEwram(ew(first + 1)).find(PROC_SCRIPT)!!
        assertEquals(d0.scrCur, proc.scrCur, "f${first + 1} scrCur")
        assertEquals(
            listOf(d0.u32(0x2C), d0.u32(0x30)), listOf(st.status, st.paletteTimer),
            "f${first + 1} fields",
        )

        var checked = 0
        for (f in first + 2..1024) {
            frame(f)
            val e = ew(f)
            val i = iw(f)
            val d = ProcArrayDump.ofEwram(e).find(PROC_SCRIPT)!!
            assertEquals(d.scrCur, proc.scrCur, "f$f scrCur")
            assertEquals(
                listOf(d.u32(0x2C), d.u32(0x30), d.u32(0x34), d.u32(0x38)),
                listOf(st.status, st.paletteTimer, st.delayTimer, st.skipFadeTimer),
                "f$f fields",
            )
            assertArrayEquals(palette(e), hw.palette, "f$f gPaletteBuffer")
            val enAt = sym["gKeyComboResetEN"].address - GbaMem.IWRAM
            assertEquals(i[enAt].toInt() != 0, combo.canReset(), "f$f gKeyComboResetEN")
            checked++
        }
        assertEquals(16, checked)
        assertEquals(1, st.status, "skipped from the Nintendo logo")
        assertEquals(
            null, ProcArrayDump.ofEwram(ew(1025)).find(PROC_SCRIPT)?.offset,
            "intro proc ended at f1025",
        )
        assertTrue(combo.canReset())
    }

    private companion object {
        val OP_ANIM_STAND_IN = ProcScript(
            "ProcScr_OpAnim (not ported)", listOf(ProcScripts.block(), ProcScripts.end()),
        )
        const val PROC_SCRIPT = 0x08AA7680 // ProcScr_GameEarlyStartUI
    }
}
