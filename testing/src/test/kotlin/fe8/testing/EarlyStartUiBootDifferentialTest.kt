package fe8.testing

import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnimHs
import gbafe.core.VerifiesPort
import gbafe.input.KeyComboReset
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.LcdControl
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs `ProcScr_GameEarlyStartUI` ([EarlyStartUi]) from its start, screen loads included, against `ram_trace_boot`
 * (RAM every 10 frames; A on health & safety at f181): health & safety fade in / wait / fade out, the Nintendo
 * logo and the Intelligent Systems logo. Our proc starts on a fresh engine and is stepped until its script
 * position and palette timer equal the first dump that holds it (f20); from there each frame feeds the scripted
 * keys and runs the proc tree (skipping the lag frame after each screen load), and every dump must match: `proc_scrCur`, `ProcOpAnimHS` fields, `gPaletteBuffer`,
 * the four BG tilemap buffers, BG control / DISPCNT in `gLCDControlBuffer`, `gKeyStatusIgnoredSt` and
 * `gKeyComboResetEN`. Addresses from decomp/fireemblem8.nm.txt (pinned 04d1edb).
 */
class EarlyStartUiBootDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun procAt(ew: ByteArray): Int? {
        val a = sym["sProcArray"]
        return (0 until a.size / PROC_SIZE).map { a.address - GbaMem.EWRAM + it * PROC_SIZE }
            .firstOrNull { GbaMem.u32(ew, 0, it) == PROC_SCRIPT }
    }

    @Test
    @VerifiesPort("GameIntroPrepareNintendofx", "PrepareHealthAndSafetyScreen", "EnableKeyComboResetEN")
    fun `intro proc from its start matches the boot trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_boot")
        fun blob(at: Int) = OpAnimHs.Blob(rom, at - 0x08000000)
        fun romPal(at: Int, n: Int) = ShortArray(n) { GbaMem.u16(rom, 0x08000000, at + 2 * it).toShort() }
        val e10 = run.region("r00010", "ewram")
        val i10 = run.region("r00010", "iwram")
        val lcdAt = sym["gLCDControlBuffer"].address - GbaMem.IWRAM
        val palAt = sym["gPaletteBuffer"].address
        fun palette(ew: ByteArray) = ShortArray(512) { GbaMem.u16(ew, GbaMem.EWRAM, palAt + 2 * it).toShort() }
        val keys = KeyStatus().apply { readFrom(e10, sym["sKeyStatusBuffer"].address - GbaMem.EWRAM) }
        val combo = KeyComboReset()
        val env = EarlyStartUi.Env(
            palette(e10), ShortArray(512), LcdControl().apply { readFrom(i10, lcdAt) }, keys,
            romPal(0x08B125F4, 16), romPal(0x08B10ABC, 16), romPal(0x08B11864, 48),
            gfx = EarlyStartUi.Gfx(blob(0x08B11D0C), blob(0x08B118C4), blob(0x08B11C0C), blob(0x08B10630),
                blob(0x08B104D8), blob(0x08B10CA4), blob(0x08B10ADC)),
            keyComboReset = combo,
        )
        val hw = env.hardware
        val engine = ProcEngine()
        val ui = EarlyStartUi(engine, env)
        val parent = engine.start(ProcScript("parent", listOf(ProcScripts.block(), ProcScripts.end())), 1)
        val proc = engine.start(ui.script, parent)
        val st = proc.state as OpAnimHs.Proc

        // A screen load (SetupBackgrounds + decompression) overruns its frame on hardware, so the game loop misses one
        // VBlank and runs no step on the next frame. Detected by the BG sync request a load leaves (cleared each step,
        // standing in for the VBlank sync).
        var lag = false
        fun frame(f: Int) {
            if (lag) {
                lag = false
                return
            }
            keys.update(if (f == A_FRAME) A_BUTTON else 0)
            engine.run(engine.root(1))
            lag = hw.modifiedBgs != 0
            hw.modifiedBgs = 0
        }
        val e20 = run.region("r00020", "ewram")
        val d20 = procAt(e20)!!
        var steps = 0
        while (!(proc.scrCur == (GbaMem.u32(e20, 0, d20 + 4) - PROC_SCRIPT) / 8 && st.paletteTimer == GbaMem.u32(e20, 0, d20 + 0x30))) {
            frame(0)
            assertTrue(++steps < 20, "proc never reached its f20 state")
        }
        var checked = 0
        for (f in 21..490) {
            frame(f)
            if (f % 10 != 0) continue
            val name = "r%05d".format(f)
            val e = run.region(name, "ewram")
            val i = run.region(name, "iwram")
            val d = procAt(e)!!
            assertEquals((GbaMem.u32(e, 0, d + 4) - PROC_SCRIPT) / 8, proc.scrCur, "f$f scrCur")
            assertEquals(listOf(GbaMem.u32(e, 0, d + 0x2C), GbaMem.u32(e, 0, d + 0x30), GbaMem.u32(e, 0, d + 0x34), GbaMem.u32(e, 0, d + 0x38)),
                listOf(st.status, st.paletteTimer, st.delayTimer, st.skipFadeTimer), "f$f fields")
            assertArrayEquals(palette(e), hw.palette, "f$f gPaletteBuffer")
            for (bg in 0..3) {
                val at = sym["gBG${bg}TilemapBuffer"].address
                assertArrayEquals(ShortArray(1024) { GbaMem.u16(e, GbaMem.EWRAM, at + 2 * it).toShort() }, hw.mapBuffer(bg), "f$f BG$bg")
            }
            val lcd = LcdControl().apply { readFrom(i, lcdAt) }
            assertArrayEquals(lcd.bgCnt, hw.lcd.bgCnt, "f$f bgcnt")
            assertEquals(lcd.dispcnt.toString(16), hw.lcd.dispcnt.toString(16), "f$f dispcnt")
            assertEquals(GbaMem.u16(i, GbaMem.IWRAM, sym["gKeyStatusIgnoredSt"].address), keys.ignoreMask, "f$f gKeyStatusIgnoredSt")
            assertEquals(i[sym["gKeyComboResetEN"].address - GbaMem.IWRAM].toInt() != 0, combo.canReset(), "f$f gKeyComboResetEN")
            checked++
        }
        assertEquals(47, checked)
        assertEquals(2, st.status, "reached the Intelligent Systems logo")
    }

    private companion object {
        const val PROC_SCRIPT = 0x08AA7680 // ProcScr_GameEarlyStartUI
        const val PROC_SIZE = 0x6C
        const val A_FRAME = 181
        const val A_BUTTON = 1
    }
}
