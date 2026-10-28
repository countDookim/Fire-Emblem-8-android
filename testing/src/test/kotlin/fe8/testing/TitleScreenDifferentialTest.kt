package fe8.testing

import fe8.game.opanim.OpAnimHs
import fe8.game.title.TitleScreen
import gbafe.core.VerifiesPort
import gbafe.data.Decompress
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The title screen's start after the op anim (`StartTitleScreen_FlagTrue`, mode 1) against
 * `ram_trace_opanim4` (RAM every frame to f3000): state from the frame before the title proc
 * appears, then frame by frame until it reaches the intro fx (label 0).
 */
class TitleScreenDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun procAt(ew: ByteArray): Int? {
        val a = sym["sProcArray"]
        return (0 until a.size / PROC_SIZE).map { a.address - GbaMem.EWRAM + it * PROC_SIZE }
            .firstOrNull { u32(ew, it) == TITLE_SCRIPT }
    }

    private fun shorts(ew: ByteArray, name: String, n: Int) =
        ShortArray(n) { u16(ew, sym[name].address - GbaMem.EWRAM + 2 * it).toShort() }

    @Test
    @VerifiesPort("StartTitleScreen_FlagTrue", "Title_Init", "Title_SetupMainGraphics", "Title_SetupSpecialEffectGraphics")
    fun `title screen start matches the per-frame RAM trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_opanim4")
        fun ew(f: Int) = run.region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), "ewram")
        fun iw(f: Int) = run.region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), "iwram")
        fun blob(at: Int) = OpAnimHs.Blob(rom, at - 0x08000000)
        fun pal(at: Int, n: Int) = ShortArray(n) { u16(rom, at - 0x08000000 + 2 * it).toShort() }
        val first = (2890..3000).first { procAt(ew(it)) != null }
        val e0 = ew(first - 1)
        val hw = BgHardware()
        hw.lcd.readFrom(iw(first - 1), sym["gLCDControlBuffer"].address - GbaMem.IWRAM)
        shorts(e0, "gPaletteBuffer", 512).copyInto(hw.palette)
        for (bg in 0..3) shorts(e0, "gBG${bg}TilemapBuffer", 0x400).copyInto(hw.mapBuffer(bg))
        val engine = ProcEngine()
        val title = TitleScreen(engine, hw, TitleScreen.Gfx(
            listOf(blob(0x08AA7760), blob(0x08AA9708), blob(0x08AAAE90)), pal(0x08AAB3F4, 16),
            blob(0x08AAB414) to blob(0x08AAC2A4), pal(0x08AAC58C, 16),
            blob(0x08AAC5AC), blob(0x08AACEDC), pal(0x08AADB68, 64),
            listOf(0x08AAFD14, 0x08AAFF10, 0x08AADC08, 0x08AAE61C, 0x08AAE8EC, 0x08AAF928, 0x08AB1C24, 0x08AB216C).map { blob(it) },
            listOf(pal(0x08AB0114, 16), pal(0x08AAE8CC, 16), pal(0x08AAFCF4, 16), pal(0x08AB210C, 48), pal(0x08AB21B8, 16))))
        val parent = engine.start(ProcScript("parent", listOf(block())), 3)
        title.start(parent, 1)
        var checked = 0
        var fxChecked = 0
        var vramChecked = 0
        for (f in first..3000) {
            val e = ew(f)
            val o = procAt(e) ?: break
            val scr = (u32(e, o + 4) - TITLE_SCRIPT) / 8
            // Decompressions overrun: a dump with gBmSt.main_loop_ended 0 is mid-step (compare nothing), and the
            // frame after one starts no new step.
            val lagging = ew(f - 1)[sym["gBmSt"].address - GbaMem.EWRAM].toInt() == 0
            val midStep = e[sym["gBmSt"].address - GbaMem.EWRAM].toInt() == 0
            if (!lagging) engine.run(engine.root(3))
            if (midStep) continue
            if (scr >= 14) break // the stand-in after Title_SetupSpecialEffectGraphics
            val ours = engine.find(title.script)!!
            assertEquals(scr, ours.scrCur, "f$f title scrCur")
            if (scr == 13) fxChecked++
            assertEquals(u16(e, o + 0x2A), (ours.state as TitleScreen.State).timer, "f$f timer")
            assertArrayEquals(shorts(e, "gPaletteBuffer", 512), hw.palette, "f$f gPaletteBuffer")
            for (bg in 0..1) assertArrayEquals(shorts(e, "gBG${bg}TilemapBuffer", 0x400), hw.mapBuffer(bg), "f$f BG$bg map")
            val lcd = hw.lcd
            val g = BgHardware().lcd.apply { readFrom(iw(f), sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }
            assertEquals(listOf(g.dispcnt, g.bldcnt) + g.bgCnt.toList(), listOf(lcd.dispcnt, lcd.bldcnt) + lcd.bgCnt.toList(),
                "f$f dispcnt, bldcnt, bgcnt")
            if (f % 10 == 0) { // full dump: VRAM written by the loaders so far (exact decompressed lengths)
                val v = run.region("f%05d".format(f), "vram")
                for ((at, dst) in listOf(0x08AA7760 to 0, 0x08AA9708 to 0x3000, 0x08AAB414 to 0x5000, 0x08AAC5AC to 0x10000,
                    0x08AACEDC to 0x13000, 0x08AAFD14 to 0x8000, 0x08AAFF10 to 0xB000, 0x08AADC08 to 0xC000)) {
                    val n = Decompress.into(rom, at - 0x08000000, ByteArray(0x10000), 0)
                    if (hw.vram.copyOfRange(dst, dst + n).any { it != 0.toByte() }) {
                        assertArrayEquals(v.copyOfRange(dst, dst + n), hw.vram.copyOfRange(dst, dst + n), "f$f VRAM %X".format(dst))
                        vramChecked++
                    }
                }
            }
            checked++
        }
        assertTrue(checked >= 5, "checked $checked frames from f$first")
        assertTrue(vramChecked >= 5, "VRAM ranges checked: $vramChecked")
        assertTrue(fxChecked >= 4, "Title_SetupSpecialEffectGraphics checked on $fxChecked frames")
    }

    private companion object {
        const val PROC_SIZE = 0x6C
        const val TITLE_SCRIPT = 0x08AA6A50
        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
