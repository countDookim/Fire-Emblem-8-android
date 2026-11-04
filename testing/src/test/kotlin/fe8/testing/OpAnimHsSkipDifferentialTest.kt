package fe8.testing

import fe8.game.opanim.OpAnimHs
import fe8.game.opanim.OpAnimHs.StepResult
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the logo skip against `ram_trace_logo_skip` (B pressed on the Intelligent Systems logo at
 * f470, RAM every frame f400..f520): f470's delay step returns [StepResult.SKIP] (`TryTickHSScreenExit`); f471
 * runs label 0 (`sub_80CBF9C` saves `gPaletteBuffer` to `gPaletteOpAnimHsBackup` = EWRAM start, then the first
 * `sub_80CBFC0` frame), f472..f474 the rest of the fade, f474 blacks the palette and breaks.
 */
class OpAnimHsSkipDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()

    private fun proc(ew: ByteArray): Int {
        val a = sym["sProcArray"]
        for (p in 0 until a.size / PROC_SIZE) {
            val o = a.address - GbaMem.EWRAM + p * PROC_SIZE
            if (u32(ew, o) == PROC_SCRIPT) return o
        }
        error("intro proc not found")
    }

    private fun palette(ew: ByteArray) = ShortArray(512) { u16(ew, sym["gPaletteBuffer"].address - GbaMem.EWRAM + 2 * it).toShort() }
    private fun backup(ew: ByteArray) = ShortArray(512) { u16(ew, 2 * it).toShort() }

    @Test
    @VerifiesPort("sub_80CBF9C", "sub_80CBFC0")
    fun `logo skip fade matches the per-frame RAM trace`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_logo_skip")
        fun ew(f: Int) = run.region("r%05d".format(f), "ewram")

        // f470: B on the IS logo's delay -> Proc_Goto(proc, 0).
        val e469 = ew(469); val e470 = ew(470)
        val o = proc(e469)
        val p = OpAnimHs.Proc().apply { status = u32(e469, o + 0x2C); paletteTimer = u32(e469, o + 0x30); delayTimer = u32(e469, o + 0x34) }
        val keys = KeyStatus().apply { readFrom(e470, sym["sKeyStatusBuffer"].address - GbaMem.EWRAM) }
        assertEquals(StepResult.SKIP, OpAnimHs.earlyStartDelay(p, keys, false))
        assertEquals(u32(e470, o + 0x34), p.delayTimer)

        // f471: backup + first fade frame.
        val pal = palette(e470)
        val bak = ShortArray(512) { 0x7C1F }
        OpAnimHs.startSkipFade(p, pal, bak)
        assertEquals(4, p.skipFadeTimer)
        assertArrayEquals(backup(ew(471)), bak, "gPaletteOpAnimHsBackup")
        for (f in 471..474) {
            val e = ew(f)
            val broke = OpAnimHs.skipFade(p, pal, bak)
            assertEquals(f == 474, broke, "f$f break")
            assertEquals(u32(e, proc(e) + 0x38), p.skipFadeTimer, "f$f unk38")
            assertArrayEquals(palette(e), pal, "f$f gPaletteBuffer")
        }
        assertEquals(true, palette(ew(472)).any { it.toInt() != 0 }, "fade frames must be non-trivial")
    }

    private companion object {
        const val PROC_SCRIPT = 0x08AA7680 // ProcScr_GameEarlyStartUI
        const val PROC_SIZE = 0x6C

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
