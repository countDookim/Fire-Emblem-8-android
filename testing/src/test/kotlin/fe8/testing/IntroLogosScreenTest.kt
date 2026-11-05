package fe8.testing

import fe8.game.opanim.OpAnimHs
import gba.data.Bgr555
import gba.data.Tiles
import gba.extract.IndexedPng
import gba.render.Camera
import gba.render.FrameBuffer
import gba.render.RenderState
import gba.render.SoftwareRenderer
import gba.render.TileSheets
import gba.testkit.GbaMem
import gba.testkit.MgbaColorMath
import gba.testkit.ReferenceData
import gbafe.extract.ScreenExtractor
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The Nintendo and Intelligent Systems logos built from **extracted assets** (`ui/intro_nintendo.*`,
 * `ui/intro_is.*`, as the FE8 extraction step writes them) match mGBA's frames in `boot_logos` while each logo is
 * fading in, shown and fading out (f300, f360, f430, f470). Both tile sets are loaded the way
 * `GameIntroPrepareNintendofx` does (Nintendo at tile 0, IS at tile 0x100); the palettes are the PNG palettes
 * faded with [OpAnimHs.brightenPalette] by the intro proc's step and `palette_timer` (minus one: the frame shows
 * the palette synced at the start of the frame, before this frame's step ran). Only the LCD registers come
 * from the dump. ROM offsets from decomp/fireemblem8.nm.txt (pinned 04d1edb).
 */
class IntroLogosScreenTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `intro logos from extracted assets match mGBA`() {
        assumeTrue(romFile.isFile && ref.available, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val nintendo = ScreenExtractor.extract(rom, "fe8", "intro_nintendo", 0xB10630, 0xB10ABC, 1,
            listOf(ScreenExtractor.Layer(0, 0xB104D8)))
        val isLogo = ScreenExtractor.extract(rom, "fe8", "intro_is", 0xB10CA4, 0xB11864, 3,
            listOf(ScreenExtractor.Layer(1, 0xB10ADC, 0x1100)), firstTile = 0x100, firstPalette = 1)
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "ui")
        dir.mkdirs()
        for ((n, o) in listOf("intro_nintendo" to nintendo, "intro_is" to isLogo)) {
            File(dir, "$n.png").writeBytes(o.png)
            File(dir, "$n.json").writeText(o.json)
        }
        val run = ref.run("boot_logos")
        for (dump in listOf("f00300", "f00360", "f00430", "f00470")) {
            val ew = run.region(dump, "ewram")
            val procAt = checkNotNull(ProcArrayDump.ofEwram(ew).find(0x08AA7680)).offset
            val index = (u32(ew, procAt + 4) - 0x08AA7680) / 8 - 1
            val timer = u32(ew, procAt + 0x30) - 1
            // Script command -> (screen, cur, max): 11/12/13 Nintendo fade-in / delay / fade-out, 17/18/19 the IS logo.
            val (shown, cur, max) = when (index) {
                11 -> Triple(nintendo, timer, 30)
                12 -> Triple(nintendo, 30, 30)
                13 -> Triple(nintendo, 30 - timer, 30)
                17 -> Triple(isLogo, timer, 30)
                18 -> Triple(isLogo, 30, 30)
                19 -> Triple(isLogo, 40 - timer, 40)
                else -> error("$dump: intro proc at command $index")
            }
            val state = RenderState()
            val lcd = LcdControl()
            lcd.readRegisters(run.region(dump, "io"))
            lcd.applyTo(state)
            val sheets = TileSheets()
            val firstPal = if (shown === isLogo) 1 else 0
            for ((o, firstTile) in listOf(nintendo to 0, isLogo to 0x100)) {
                val png = IndexedPng.read(o.png)
                val tiles = Tiles.encode4bpp(png.image)
                for ((bg, map) in o.tilemaps) {
                    val layer = state.layers[bg]
                    val sheet = sheets[layer.sheet] ?: ByteArray(0x10000).also { sheets[layer.sheet] = it }
                    tiles.copyInto(sheet, firstTile * TileSheets.TILE_BYTES_4BPP)
                    for (y in 0 until map.height) for (x in 0 until map.width) layer.setEntry(x, y, map.entries[y * map.width + x])
                }
                if (o === shown) {
                    val src = ShortArray(png.palette.size) { Bgr555.fromArgb(png.palette[it]).toShort() }
                    val buf = ShortArray(512)
                    OpAnimHs.brightenPalette(src, buf, firstPal, src.size / 16, cur, max)
                    for (i in 0 until 256) state.palettes.bg[i] = buf[i]
                }
            }
            val fb = FrameBuffer()
            SoftwareRenderer(state, sheets, fb, MgbaColorMath).render(Camera())
            val out = File(System.getProperty("fe8.frameDiffDir") ?: "build/frame-diff", "intro_logo_$dump.png")
            assertEquals(0, HardwareScene.compare(fb, run.frameRgb(dump), out), dump)
        }
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
