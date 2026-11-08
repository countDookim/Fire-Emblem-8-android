package fe8.game

import gba.core.VerifiesPort
import gbafe.core.rng.RngService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The Prologue's brown text box (BROWNBOXTEXT 0x664, 8, 0 at 0x089EECBC) against
 * `ram_trace_newgame_bm` f09559 (hold phase): same OAM entries (position, tile, palette, shape,
 * size) and the same blend registers. Text glyph tiles are not drawn here (host stand-in).
 */
class BrownTextBoxReferenceTest {
    private val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"

    private fun boxEntries(oam: List<Triple<Int, Int, Int>>) = oam
        .filter { (a0, _, a2) ->
            (a0 shr 8) and 3 != 2 && (a2 and 0x3FF) >= 640 && (a2 shr 12) in 9..10
        }
        .sortedWith(compareBy({ it.third }, { it.second }, { it.first }))

    @Test
    fun `message 0x664 is 12 tiles wide as in the reference box`() {
        val file = File(assets, "baserom.gba")
        assumeTrue(file.isFile, "ROM not present")
        val boot = Fe8Boot(RomView.ofRom(file.readBytes()), { 0 }, RngService())
        // Reference box: right edge 8x8 at x = 96 = 8 + (width - 1) * 8 -> width 12 -> r4 = 7,
        // so the text is 56..63 px wide.
        val px = boot.brownTextBoxWidth(0x664)
        assertEquals(7, px / 8, "text width $px px")
    }

    @VerifiesPort(
        "StartBrownTextBoxCore", "BrownTextBox_Loop", "GetSpriteTextDrawDest", "DrawSpriteTextGlyph",
    )
    @Test
    fun `the Prologue box hold frame equals the reference OAM entries`() {
        val rom = File(assets, "baserom.gba")
        val ref = File(assets, "reference/out/ram_trace_newgame_bm/f09559.oam.bin")
        assumeTrue(rom.isFile && ref.isFile, "ROM or reference not present")
        val b = ByteBuffer.wrap(ref.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val expected = boxEntries(
            (0 until 128).map { Triple(b.getShort(it * 8).toInt() and 0xFFFF,
                b.getShort(it * 8 + 2).toInt() and 0xFFFF, b.getShort(it * 8 + 4).toInt() and 0xFFFF) },
        )
        assertEquals(30, expected.size)
        var frame = 0
        val boot = Fe8Boot(
            RomView.ofRom(rom.readBytes()), { if (frame % 20 in 5..8) 0x0001 else 0 }, RngService(),
        )
        var got: List<Triple<Int, Int, Int>>? = null
        var sawRamp = false
        var holdFrames = 0
        while (frame < 12000 && got == null) {
            boot.frame()
            frame++
            val o = boot.loop.oam.oam
            val e = boxEntries((0 until o.size / 4).map {
                Triple(o[it * 4].toInt() and 0xFFFF, o[it * 4 + 1].toInt() and 0xFFFF,
                    o[it * 4 + 2].toInt() and 0xFFFF)
            })
            val lcd = boot.screenFade.lcd
            if (e.size == 30 && lcd.lcdEffect() == 1) sawRamp = true
            // The OAM read here is the previous step's (SyncHiOam runs in the VBlank before the
            // step). In C the box loop runs before the fade proc (older sibling first), so the
            // step that reaches blend 16 still draws OAM0_BLEND: OAM view 1 and 2 after the effect
            // is none have the bit, view 3 (the first clear one) is the steady box.
            if (e.size == 30 && sawRamp && lcd.lcdEffect() == 0) {
                holdFrames++
                val blended = e.any { it.first and 0x400 != 0 }
                if (holdFrames <= 2) assertTrue(blended, "hold view $holdFrames lost OAM0_BLEND")
                if (holdFrames == 3) got = e
            }
        }
        assertTrue(sawRamp, "no alpha-blended fade-in seen")
        val lcd = boot.screenFade.lcd
        assertEquals(0x3F20, lcd.bldcnt)
        assertEquals(0x10, lcd.blendCoeffA)
        assertEquals(0, lcd.blendCoeffB)
        assertEquals(expected, got)
        // OBJ VRAM: the 22 box tiles in use (chr 0x5000; later tiles hold older leftovers in the dump) and the sprite text strip (0x5400,
        // 0x800 bytes) at 0x06010000 + chr.
        val refVram = File(assets, "reference/out/ram_trace_newgame_bm/f09559.vram.bin").readBytes()
        val ours = boot.ui.env.hardware.vram
        for ((name, off, len) in listOf(Triple("box tiles", 0x15000, 0x2C0),
            Triple("text tiles", 0x15400, 0x800))) {
            val diff = (off until off + len).filter { refVram[it] != ours[it] }
            assertTrue(diff.isEmpty(), "$name: ${diff.size} bytes differ, first at " +
                diff.take(8).joinToString { "0x%X(ref %02X ours %02X)".format(it, refVram[it], ours[it]) })
        }
    }

    private fun gbafe.render.LcdControl.lcdEffect(): Int = (bldcnt shr 6) and 3
}
