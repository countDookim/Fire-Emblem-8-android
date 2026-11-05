package fe8.testing

import fe8.game.savemenu.SaveDraw
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.render.BgVerticalScroll
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the menu fog scroll ([SaveDraw.scrollFogBg] with
 * [BgVerticalScroll.bufferOffset], [BgVerticalScroll.flip] and [LcdControl.setBgPosition]) against
 * the mGBA `menu_screens` dumps `m1086` and `m1087`, which are consecutive frames. The port is
 * seeded from the `m1086` dump as it is (proc fields, scroll struct, LCD buffer), except that the
 * bytes the fog call writes (the 160-line table of the half it fills and the BG2 offsets) are set
 * to a sentinel, so a no-op leaves the sentinel. It runs one fog call and must match the `m1087`
 * dump: `ProcScr_savedraw` bg_x/bg_y, the whole scroll table, `frame_flip` and the whole
 * `gLCDControlBuffer`. The flip parity is also checked against the two dumped `frame_flip` values.
 * Addresses from decomp/fireemblem8.nm.txt (pinned 04d1edb): ProcScr_savedraw 0x08A206A8,
 * gSinLookup 0x080D751C.
 */
class SaveDrawDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "SaveDraw_ScrollFogBG",
        "GetBgVerticalScrollBuffer",
        "FlipBgVerticalScroll",
        "BG_SetPosition",
    )
    fun `fog scroll turns the previous dump into the next one`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "ROM / mGBA reference data not present (run tools/setup.sh)",
        )
        val rom = romFile.readBytes()
        val sin = ShortArray(256) { u16(rom, SIN_LOOKUP - ROM + 2 * it).toShort() }
        val run = ref.run("menu_screens")
        val lcdAt = sym["gLCDControlBuffer"].address - GbaMem.IWRAM
        val prev = Dump.of(run, "m1086", sym)
        val next = Dump.of(run, "m1087", sym)
        assertEquals(prev.scroll.frameFlip xor 1, next.scroll.frameFlip, "dumped flip parity")
        assertTrue(next.bgX != 0 && next.bgY != 0, "fog position is non-zero")

        val fog = SaveDraw.Fog().apply {
            bgX = u16(prev.ew, prev.proc + 0x3E)
            bgY = u16(prev.ew, prev.proc + 0x40)
        }
        val scroll = BgVerticalScroll().apply { readFrom(prev.stMem, prev.stAddr and 0xFFFFFF) }
        val lcd = LcdControl().apply { readFrom(prev.iw, lcdAt) }
        // The call fills the half that is not shown (frame_flip + 1).
        val target = ((prev.scroll.frameFlip + 1) and 1) * 480
        assertTrue(
            (0 until 160).any { next.scroll.buf[target + it].toInt() != 0 },
            "the dumped scroll values are non-zero",
        )
        for (i in 0 until 160) scroll.buf[target + i] = SENTINEL
        lcd.bgHofs[2] = SENTINEL.toInt()
        lcd.bgVofs[2] = SENTINEL.toInt()

        SaveDraw.scrollFogBg(fog, scroll, lcd, sin)

        assertEquals(next.bgX to next.bgY, fog.bgX to fog.bgY, "bg_x/bg_y")
        assertEquals(next.scroll.frameFlip, scroll.frameFlip, "frame_flip")
        assertEquals(next.scroll.buf.toList(), scroll.buf.toList(), "scroll table")
        val expectedLcd = LcdControl().apply { readFrom(next.iw, lcdAt) }
        assertTrue(expectedLcd.bgHofs[2] != 0 && expectedLcd.bgVofs[2] != 0, "BG2 offsets set")
        assertEquals(fields(expectedLcd), fields(lcd), "gLCDControlBuffer")
    }

    /** Every modelled `gLCDControlBuffer` field. */
    private fun fields(l: LcdControl): List<Any> = listOf(l.dispcnt, l.dispstat, l.bgCnt.toList(), l.bgHofs.toList(),
        l.bgVofs.toList(), l.winH.toList(), l.winV.toList(), l.wincnt, l.mosaic, l.bldcnt, l.blendCoeffA, l.blendCoeffB,
        l.blendY, l.affine.toList(), l.colorAddition)

    private companion object {
        const val ROM = 0x08000000
        const val PROC_SCRIPT = 0x08A206A8 // ProcScr_savedraw
        const val SIN_LOOKUP = 0x080D751C // gSinLookup
        const val SENTINEL: Short = 0xAA

        fun u16(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }
}

/** One `menu_screens` dump: the save draw proc fields and the scroll struct the game points to. */
private class Dump(
    val ew: ByteArray,
    val iw: ByteArray,
    val proc: Int,
    val stMem: ByteArray,
    val stAddr: Int,
) {
    val bgX = u16(ew, proc + 0x3E)
    val bgY = u16(ew, proc + 0x40)
    val scroll = BgVerticalScroll().apply { readFrom(stMem, stAddr and 0xFFFFFF) }

    companion object {
        fun of(run: ReferenceData.Run, name: String, sym: RamSymbols): Dump {
            val ew = run.region(name, "ewram")
            val iw = run.region(name, "iwram")
            val proc = ProcArrayDump.ofEwram(ew).findAll(0x08A206A8).single().offset
            val at = sym["gpBgVerticalScrollSt"].address - GbaMem.IWRAM
            val stAddr = u16(iw, at) or (u16(iw, at + 2) shl 16)
            return Dump(ew, iw, proc, if (stAddr ushr 24 == 2) ew else iw, stAddr)
        }

        private fun u16(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }
}
