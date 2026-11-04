package fe8.testing

import gba.data.Bgr555
import gba.data.Tiles
import gbafe.extract.IndexedPng
import gbafe.extract.ScreenExtractor
import gbafe.render.Camera
import gbafe.render.FrameBuffer
import gbafe.render.LcdControl
import gbafe.render.RenderState
import gbafe.render.SoftwareRenderer
import gbafe.render.TileSheets
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * M3-06 acceptance: the health & safety screen built from **extracted assets** (tile-set PNG, palette and
 * TSA tilemaps from [ScreenExtractor], the `ui/health_safety.*` files), not from VRAM, matches mGBA's frame.
 * Only the LCD registers (BG setup and the BLDY pulse; the screen's proc logic is not ported yet) come from the
 * dump's I/O. ROM offsets from decomp/fireemblem8.nm.txt (pinned 04d1edb): Img_OpAnimHealthSafetyScreen
 * 0x08B11D0C, Pal_ 0x08B125F4, Tsa_..BG1 0x08B118C4, Tsa_..BG0 0x08B11C0C. Dumps `testing/build/extracted/ui/`.
 */
class HealthSafetyScreenTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `health and safety screen from extracted assets matches mGBA`() {
        assumeTrue(romFile.isFile && ref.available, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val o = ScreenExtractor.extract(romFile.readBytes(), "fe8", "health_safety", 0xB11D0C, 0xB125F4, 1,
            listOf(ScreenExtractor.Layer(1, 0xB118C4), ScreenExtractor.Layer(0, 0xB11C0C)))
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "ui")
        dir.mkdirs()
        File(dir, "health_safety.png").writeBytes(o.png)
        File(dir, "health_safety.json").writeText(o.json)

        val run = ref.run("boot_idle")
        val dump = "f00600"
        val state = RenderState()
        val lcd = LcdControl()
        lcd.readRegisters(run.region(dump, "io"))
        lcd.applyTo(state)
        // Assets -> render state, the way PrepareHealthAndSafetyScreen loads them (tiles at char base 0).
        val png = IndexedPng.read(o.png)
        val sheets = TileSheets()
        val tiles = Tiles.encode4bpp(png.image)
        for ((bg, map) in o.tilemaps) {
            val layer = state.layers[bg]
            sheets[layer.sheet] = tiles
            for (y in 0 until map.height) for (x in 0 until map.width) layer.setEntry(x, y, map.entries[y * map.width + x])
        }
        for (i in 0 until 16) state.palettes.bg[i] = Bgr555.fromArgb(png.palette[i]).toShort()
        val fb = FrameBuffer()
        SoftwareRenderer(state, sheets, fb, MgbaColorMath).render(Camera())
        val out = File(System.getProperty("fe8.frameDiffDir") ?: "build/frame-diff", "health_safety_assets.png")
        assertEquals(0, HardwareScene.compare(fb, run.frameRgb(dump), out))
    }
}
