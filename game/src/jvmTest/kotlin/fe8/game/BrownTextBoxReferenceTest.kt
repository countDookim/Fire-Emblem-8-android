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
import fe8.game.scene.BrownTextBox
import fe8.game.scene.BrownTextBoxFadeState
import fe8.game.scene.BrownTextBoxState
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import org.junit.jupiter.api.Assertions.assertNull

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

    @VerifiesPort(
        "StartBrownTextBox",
        "sub_8011A1C",
        "sub_8011A48",
        "sub_8011AA0",
        "sub_8011AF4",
        "sub_8011B4C",
        "sub_8011B90",
    )
    @Test
    fun `the Prologue box lifecycle and blend match reference trace r09541 to r09675`() {
        val dir = File(assets, "reference/out/ram_trace_newgame_bm")
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile && dir.isDirectory, "ROM or reference directory missing")
        assumeTrue(File(dir, "r09541.ewram.bin").isFile, "trace files missing")

        val romBytes = romFile.readBytes()
        val engine = ProcEngine()
        val hw = BgHardware()
        val sprites = SpriteLayers()
        val romView = RomView.ofRom(romBytes)
        // Reference message 0x664 has text width 7 tiles (56 px) -> box width 12 tiles
        val brownTextBox = BrownTextBox(
            engine, hw, sprites, romView,
            textWidth = { 7 * 8 },
        )

        val parentScript = ProcScript("parent", listOf(repeat { }, end()))
        val parent = engine.start(parentScript, 0)

        assertNull(engine.find(brownTextBox.boxScript), "box proc not started before call")
        assertNull(engine.find(brownTextBox.fadeScript), "fade proc not started before call")

        brownTextBox.startBrownTextBox(0x0664, 8, 0, parent)
        assertEquals(1, parent.lockCnt, "parent blocked by startBrownTextBox")

        val ewramProcBase = 0x024E68
        val iwramLcdBase = 0x3080
        val procSize = 0x6C
        val procBrownTextBox = 0x085924D8.toInt()
        val procFade = 0x08592530.toInt()

        for (frame in 9541..9675) {
            sprites.clear()
            engine.run(parent)

            val ewramFile = File(dir, "r%05d.ewram.bin".format(frame)).takeIf { it.isFile }
                ?: File(dir, "f%05d.ewram.bin".format(frame))
            val iwramFile = File(dir, "r%05d.iwram.bin".format(frame)).takeIf { it.isFile }
                ?: File(dir, "f%05d.iwram.bin".format(frame))
            val ewram = ewramFile.readBytes()
            val iwram = iwramFile.readBytes()

            var refBrownFound = false
            var refFadeFound = false
            var refX = 0
            var refY = 0
            var refChr = 0
            var refPal = 0
            var refTextId = 0
            var refWidth = 0
            var refOam0Attr = 0
            var refBlendVal = 0

            for (slot in 0 until 64) {
                val off = ewramProcBase + slot * procSize
                val script = (ewram[off].toInt() and 0xFF) or
                    ((ewram[off + 1].toInt() and 0xFF) shl 8) or
                    ((ewram[off + 2].toInt() and 0xFF) shl 16) or
                    ((ewram[off + 3].toInt() and 0xFF) shl 24)
                if (script == procBrownTextBox) {
                    refBrownFound = true
                    refX = (ewram[off + 0x30].toInt() and 0xFF) or (ewram[off + 0x31].toInt() shl 8)
                    refY = (ewram[off + 0x34].toInt() and 0xFF) or (ewram[off + 0x35].toInt() shl 8)
                    refChr = (ewram[off + 0x38].toInt() and 0xFF) or
                        ((ewram[off + 0x39].toInt() and 0xFF) shl 8) or
                        ((ewram[off + 0x3A].toInt() and 0xFF) shl 16) or
                        ((ewram[off + 0x3B].toInt() and 0xFF) shl 24)
                    refPal = (ewram[off + 0x3C].toInt() and 0xFF) or (ewram[off + 0x3D].toInt() shl 8)
                    refTextId = (ewram[off + 0x40].toInt() and 0xFF) or
                        ((ewram[off + 0x41].toInt() and 0xFF) shl 8)
                    refWidth = (ewram[off + 0x44].toInt() and 0xFF) or (ewram[off + 0x45].toInt() shl 8)
                    refOam0Attr = (ewram[off + 0x48].toInt() and 0xFF) or
                        ((ewram[off + 0x49].toInt() and 0xFF) shl 8)
                } else if (script == procFade) {
                    refFadeFound = true
                    refBlendVal = (ewram[off + 0x4C].toInt() and 0xFF) or
                        (ewram[off + 0x4D].toInt() shl 8)
                }
            }

            val refBldcnt = (iwram[iwramLcdBase + 0x3C].toInt() and 0xFF) or
                ((iwram[iwramLcdBase + 0x3D].toInt() and 0xFF) shl 8)
            val refCoeffA = iwram[iwramLcdBase + 0x44].toInt() and 0xFF
            val refCoeffB = iwram[iwramLcdBase + 0x45].toInt() and 0xFF
            val refBlendY = iwram[iwramLcdBase + 0x46].toInt() and 0xFF

            val boxProc = engine.find(brownTextBox.boxScript)
            val fadeProc = engine.find(brownTextBox.fadeScript)

            assertEquals(refBrownFound, boxProc != null, "frame $frame box proc presence")
            assertEquals(refFadeFound, fadeProc != null, "frame $frame fade proc presence")
            assertEquals(if (refFadeFound) 1 else 0, parent.lockCnt, "frame $frame parent lockCnt")

            if (boxProc != null) {
                val boxState = boxProc.state as BrownTextBoxState
                assertEquals(refX, boxState.x, "frame $frame boxState.x")
                assertEquals(refY, boxState.y, "frame $frame boxState.y")
                assertEquals(refChr, boxState.chr, "frame $frame boxState.chr")
                assertEquals(refPal, boxState.pal, "frame $frame boxState.pal")
                assertEquals(refTextId, boxState.textId, "frame $frame boxState.textId")
                assertEquals(refWidth, boxState.width, "frame $frame boxState.width")
                assertEquals(refOam0Attr, boxState.oam0Attr, "frame $frame boxState.oam0Attr")

                // Check OAM output of brownTextBoxLoop
                sprites.clear()
                brownTextBox.brownTextBoxLoop(boxProc)
                val pushed = ArrayList<Int>()
                for (layer in 0 until 13) {
                    sprites.pushLayerObjects(layer) { pushed.add(it.oam0) }
                }
                assertTrue(pushed.isNotEmpty(), "frame $frame sprites pushed")
                if (refOam0Attr != 0) {
                    assertTrue(pushed.all { (it and 0x0400) != 0 }, "frame $frame OAM0_BLEND set in all sprites")
                } else {
                    assertTrue(pushed.all { (it and 0x0400) == 0 }, "frame $frame OAM0_BLEND clear in all sprites")
                }
            }

            if (fadeProc != null) {
                val fadeState = fadeProc.state as BrownTextBoxFadeState
                assertEquals(refBlendVal, fadeState.blendVal, "frame $frame fadeState.blendVal")
            }

            val lcd = hw.lcd
            assertEquals(refBldcnt, lcd.bldcnt, "frame $frame lcd.bldcnt")
            assertEquals(refCoeffA, lcd.blendCoeffA, "frame $frame lcd.blendCoeffA")
            assertEquals(refCoeffB, lcd.blendCoeffB, "frame $frame lcd.blendCoeffB")
            assertEquals(refBlendY, lcd.blendY, "frame $frame lcd.blendY")
        }
    }

    private fun gbafe.render.LcdControl.lcdEffect(): Int = (bldcnt shr 6) and 3
}
