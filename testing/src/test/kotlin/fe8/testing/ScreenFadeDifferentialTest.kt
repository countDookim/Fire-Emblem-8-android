package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.LcdControl
import gbafe.render.ScreenFade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The blend fades of [ScreenFade] against `ram_trace_newgame_bm` r09522..r09540: the Prologue's
 * `ProcScr_FadeFromBlack` (speed 0x10). Seeded from the LCD buffer of r09522, the fade is started
 * as a blocking child and stepped once per frame; compared per frame are `bldcnt`, the blend
 * coefficients, `wincnt`, the proc's `unk64`/`unk66` and whether the proc exists.
 * ToBlack, ToWhite and FromWhite do not occur in the trace (not tagged).
 */
class ScreenFadeDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()

    /** Dump name of frame [f]: the full dumps every 30 frames from f09319 are `f`, the rest `r`. */
    private fun name(f: Int) = (if ((f - 9319) % 30 == 0) "f" else "r") + "%05d".format(f)

    @Test
    // Not @VerifiesPort: FadeFromCommon_OnLoop (speed 0x10 keeps the level a multiple of 16, so a
    // rounding error in `level >> 4` survives; audit 2026-11-06).
    @VerifiesPort("StartLockingFadeFromBlack", "FadeFromBlack_OnInit", "FadeExists")
    fun `fade from black follows the per-frame RAM trace`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_newgame_bm")
        fun lcdOf(f: Int) = LcdControl().apply {
            readFrom(
                run.region(name(f), "iwram"),
                sym["gLCDControlBuffer"].address - GbaMem.IWRAM,
            )
        }
        val start = 9522
        val lcd = lcdOf(start)
        val engine = ProcEngine().also { it.init() }
        val fade = ScreenFade(engine, lcd)
        val parent = engine.start(ProcScript("parent", listOf(block())), 3)
        fade.startLockingFadeFromBlack(0x10, parent)
        var withProc = 0
        for (f in start + 1..9540) {
            engine.run(engine.root(3))
            val want = lcdOf(f)
            assertEquals(want.bldcnt, lcd.bldcnt, "f$f bldcnt")
            assertEquals(want.blendCoeffA, lcd.blendCoeffA, "f$f blendCoeffA")
            assertEquals(want.blendCoeffB, lcd.blendCoeffB, "f$f blendCoeffB")
            assertEquals(want.blendY, lcd.blendY, "f$f blendY")
            assertEquals(want.wincnt, lcd.wincnt, "f$f wincnt")
            val ew = run.region(name(f), "ewram")
            val dump = ProcArrayDump.ofEwram(ew).find(FADE_FROM_BLACK)
            val ours = engine.find(fade.fromBlack)
            assertEquals(dump != null, ours != null, "f$f proc exists")
            assertEquals(dump != null, fade.fadeExists(), "f$f FadeExists")
            if (dump != null && ours != null) {
                val st = ours.state as ScreenFade.State
                assertEquals(dump.u16(0x64).toShort().toInt(), st.speed, "f$f unk64")
                assertEquals(dump.u16(0x66).toShort().toInt(), st.level, "f$f unk66")
                withProc++
            }
        }
        assertTrue(withProc >= 15, "fade proc seen on $withProc frames")
    }

    private companion object {
        const val FADE_FROM_BLACK = 0x0859A044
    }
}
