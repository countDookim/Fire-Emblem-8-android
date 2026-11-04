package fe8.testing

import fe8.game.savemenu.SaveDraw
import gba.core.VerifiesPort
import gbafe.render.BgVerticalScroll
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the menu fog scroll ([SaveDraw.scrollFogBg] with [BgVerticalScroll.bufferOffset],
 * [BgVerticalScroll.flip] and [LcdControl.setBgPosition]) against the mGBA `menu_screens` dumps. The dumped RAM is
 * taken right after the game step whose last fog call wrote the newest table, so we rewind the inputs by one
 * call (`bg_x - 1`, `bg_y - 2`, flip undone, target half and BG2 offset cleared), run our port and require the
 * dumped `ProcScr_savedraw` fields, the 160-line table, `frame_flip` and the whole `gLCDControlBuffer` to come out
 * byte-identical. Addresses from decomp/fireemblem8.nm.txt (pinned 04d1edb): ProcScr_savedraw 0x08A206A8,
 * gSinLookup 0x080D751C.
 */
class SaveDrawDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    @Test
    @VerifiesPort("SaveDraw_ScrollFogBG", "GetBgVerticalScrollBuffer", "FlipBgVerticalScroll", "BG_SetPosition")
    fun `fog scroll reproduces the dumped table, proc fields and LCD buffer`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val sin = ShortArray(256) { u16(rom, SIN_LOOKUP - ROM + 2 * it).toShort() }
        val run = ref.run("menu_screens")
        val procArray = sym["sProcArray"]
        val lcdAt = sym["gLCDControlBuffer"].address - GbaMem.IWRAM
        var checked = 0
        for (dump in listOf("m0962", "m1024", "m1086", "m1087")) {
            val ew = run.region(dump, "ewram")
            val iw = run.region(dump, "iwram")
            val proc = (0 until procArray.size / PROC_SIZE).map { procArray.address - GbaMem.EWRAM + it * PROC_SIZE }
                .single { u32(ew, it) == PROC_SCRIPT }
            val stAddr = u32(iw, sym["gpBgVerticalScrollSt"].address - GbaMem.IWRAM)
            val stMem = if (stAddr ushr 24 == 2) ew else iw
            val expected = BgVerticalScroll().apply { readFrom(stMem, stAddr and 0xFFFFFF) }
            val expectedLcd = fields(LcdControl().apply { readFrom(iw, lcdAt) })

            val fog = SaveDraw.Fog().apply { bgX = (u16(ew, proc + 0x3E) - 1) and 0xFFFF; bgY = (u16(ew, proc + 0x40) - 2) and 0xFFFF }
            val scroll = BgVerticalScroll().apply { readFrom(stMem, stAddr and 0xFFFFFF) }
            scroll.flip()
            val target = scroll.bufferOffset(0, 1)
            for (i in 0 until 160) scroll.buf[target + i] = 0
            val lcd = LcdControl().apply { readFrom(iw, lcdAt); setBgPosition(2, 0, 0) }

            SaveDraw.scrollFogBg(fog, scroll, lcd, sin)

            assertEquals(u16(ew, proc + 0x3E) to u16(ew, proc + 0x40), fog.bgX to fog.bgY, "$dump bg_x/bg_y")
            assertEquals(expected.frameFlip, scroll.frameFlip, "$dump frame_flip")
            assertEquals(expected.buf.toList(), scroll.buf.toList(), "$dump scroll table")
            assertEquals(expectedLcd, fields(lcd), "$dump gLCDControlBuffer")
            checked++
        }
        assertTrue(checked == 4)
    }

    /** Every modelled `gLCDControlBuffer` field. */
    private fun fields(l: LcdControl): List<Any> = listOf(l.dispcnt, l.dispstat, l.bgCnt.toList(), l.bgHofs.toList(),
        l.bgVofs.toList(), l.winH.toList(), l.winV.toList(), l.wincnt, l.mosaic, l.bldcnt, l.blendCoeffA, l.blendCoeffB,
        l.blendY, l.affine.toList(), l.colorAddition)

    private companion object {
        const val ROM = 0x08000000
        const val PROC_SCRIPT = 0x08A206A8 // ProcScr_savedraw
        const val SIN_LOOKUP = 0x080D751C // gSinLookup
        const val PROC_SIZE = 0x6C

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
