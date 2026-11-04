package fe8.testing

import gba.render.Camera
import gba.render.Effects
import gba.render.FrameBuffer
import gba.render.GpuFrame
import gba.render.PackedCompositor
import gba.render.RenderState
import gba.render.SoftwareRenderer
import gba.render.SpriteBatch
import gba.render.TileLayer
import gba.render.TileSheets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Synthetic scenes for the text-mode BG compositor, with hand-computed expected pixels. */
class SoftwareRendererTest {
    private val state = RenderState()
    private val sheets = TileSheets()
    private val fb = FrameBuffer()
    private val renderer = SoftwareRenderer(state, sheets, fb)
    private val cam = Camera()

    // Sheet 0 (4bpp): tile 0 transparent, tile 1 solid index 1, tile 2 = pixel (x, y) has index 1 + x
    // only on row 0 (rest transparent) so flips are observable, tile 3 solid index 2.
    private val sheet4 = ByteArray(4 * TileSheets.TILE_BYTES_4BPP).also { s ->
        for (i in 0 until 32) s[32 + i] = 0x11
        for (x in 0 until 8 step 2) s[64 + x / 2] = ((1 + x) or ((2 + x) shl 4)).toByte()
        for (i in 0 until 32) s[96 + i] = 0x22
    }

    // Sheet 1 (8bpp): tile 0 transparent, tile 1 solid index 0x40.
    private val sheet8 = ByteArray(2 * TileSheets.TILE_BYTES_8BPP).also { s -> for (i in 0 until 64) s[64 + i] = 0x40 }

    // Sheet 2 (OBJ, 32-byte units): unit u (0..63) is a 4bpp tile filled with index (u % 15) + 1, so each
    // tile of a sprite is identifiable by colour 0x200 + (u % 15) + 1. Units 64..65 = one 8bpp tile of 0x40.
    private val objSheet = ByteArray(66 * 32).also { s ->
        for (u in 0 until 64) { val v = (u % 15) + 1; for (i in 0 until 32) s[u * 32 + i] = (v or (v shl 4)).toByte() }
        for (i in 0 until 64) s[64 * 32 + i] = 0x40
    }

    private fun objTile(u: Int) = argb(0x200 + (u % 15) + 1)

    private fun argb(bgr: Int) = FrameBuffer.bgr555ToArgb(bgr)

    @BeforeEach
    fun setUp() {
        sheets[0] = sheet4
        sheets[1] = sheet8
        val pal = state.palettes.bg
        pal[0] = 0x0001 // backdrop
        for (i in 1 until 16) pal[i] = (0x100 + i).toShort() // bank 0
        pal[0x21] = 0x7C00 // bank 2, index 1
        pal[0x40] = 0x03E0
        pal[0x41] = 0x1234
        val obj = state.palettes.obj
        for (i in 1 until 16) obj[i] = (0x200 + i).toShort()
        obj[0x31] = 0x0BBB // OBJ bank 3, index 1
        obj[0x40] = 0x0CCC
        sheets[2] = objSheet
    }

    private fun layer(bg: Int, prio: Int, fill: Int): TileLayer = state.layers[bg].also { l ->
        l.enabled = true
        l.priority = prio
        l.sheet = 0
        for (y in 0 until 32) for (x in 0 until 32) l.setEntry(x, y, fill)
    }

    @Test
    fun `empty frame shows the backdrop`() {
        renderer.render(cam)
        assertEquals(argb(0x0001), fb[0, 0])
        assertEquals(argb(0x0001), fb[239, 159])
    }

    @Test
    fun `lower priority value wins, ties go to the lower BG number`() {
        layer(0, prio = 1, fill = 1) // index 1 bank 0 → 0x101
        layer(1, prio = 0, fill = 3) // index 2 bank 0 → 0x102
        renderer.render(cam)
        assertEquals(argb(0x102), fb[10, 10])
        state.layers[1].priority = 1 // tie: BG0 in front
        renderer.render(cam)
        assertEquals(argb(0x101), fb[10, 10])
        state.layers[0].setEntry(1, 1, 0) // transparent tile at (8..15, 8..15) shows BG1 behind
        renderer.render(cam)
        assertEquals(argb(0x102), fb[10, 10])
        assertEquals(argb(0x101), fb[16, 10])
    }

    @Test
    fun `palette bank comes from the map entry`() {
        layer(2, prio = 0, fill = 1 or (2 shl TileLayer.PALETTE_SHIFT))
        renderer.render(cam)
        assertEquals(argb(0x7C00), fb[0, 0])
    }

    @Test
    fun `flips mirror the tile`() {
        val l = layer(0, prio = 0, fill = 0)
        l.setEntry(0, 0, 2)
        l.setEntry(1, 0, 2 or TileLayer.HFLIP)
        l.setEntry(2, 0, 2 or TileLayer.VFLIP)
        renderer.render(cam)
        assertEquals(argb(0x101), fb[0, 0]) // index 1 + x at x = 0
        assertEquals(argb(0x108), fb[7, 0])
        assertEquals(argb(0x108), fb[8, 0]) // h-flipped: x = 0 shows source x = 7
        assertEquals(argb(0x101), fb[15, 0])
        assertEquals(argb(0x0001), fb[16, 0]) // v-flipped: row 0 shows source row 7 (transparent)
        assertEquals(argb(0x101), fb[16, 7])
    }

    @Test
    fun `scroll wraps at 256 and 512 pixels`() {
        val l = layer(0, prio = 0, fill = 0)
        l.setEntry(0, 0, 1)
        l.scrollX = 256 + 4 // 32-tile map: wraps to 4
        l.scrollY = 0
        renderer.render(cam)
        assertEquals(argb(0x0001), fb[4, 0])
        assertEquals(argb(0x101), fb[3, 0])
        l.widthTiles = 64 // 512-px map: 260 is no longer tile 0
        renderer.render(cam)
        assertEquals(argb(0x0001), fb[3, 0])
        l.scrollX = 512 - 8 // tile 0 appears at screen x 8
        renderer.render(cam)
        assertEquals(argb(0x101), fb[8, 0])
        assertEquals(argb(0x0001), fb[7, 0])
        l.scrollX = -8 // negative scroll wraps the same way
        renderer.render(cam)
        assertEquals(argb(0x101), fb[8, 0])
    }

    @Test
    fun `8bpp tiles index the whole palette`() {
        val l = layer(3, prio = 0, fill = 1 or (2 shl TileLayer.PALETTE_SHIFT)) // bank bits ignored in 8bpp
        l.sheet = 1
        l.bpp8 = true
        renderer.render(cam)
        assertEquals(argb(0x03E0), fb[100, 100])
    }

    @Test
    fun `line table scroll and palette writes apply from their line on`() {
        layer(0, prio = 0, fill = 0).setEntry(0, 0, 1)
        state.lines.setScroll(line = 3, bg = 0, x = 8, y = 0) // line 3 only: tile 0 scrolled off screen
        state.lines.writePalette(line = 5, index = 1, color = 0x0ABC)
        renderer.render(cam)
        assertEquals(argb(0x101), fb[0, 2])
        assertEquals(argb(0x0001), fb[0, 3])
        assertEquals(argb(0x101), fb[0, 4])
        assertEquals(argb(0xABC), fb[0, 5])
        assertEquals(argb(0xABC), fb[0, 7])
        assertEquals(0x101, state.palettes.bg[1].toInt(), "state palette is not modified")
    }

    /** Every scene must also draw identically through the GPU backend's packed data and shader spec. */
    @AfterEach
    fun gpuParity() {
        val soft = FrameBuffer()
        SoftwareRenderer(state, sheets, soft).render(cam)
        val gpu = FrameBuffer()
        val frame = GpuFrame(state, sheets)
        frame.pack()
        PackedCompositor(frame, sheets).render(gpu)
        val bad = (soft.pixels.indices).firstOrNull { soft.pixels[it] != gpu.pixels[it] }
        assertEquals(null, bad, "first pixel where GPU spec != software")
        val bytes = AllocationMeter.measureMin(warmup = 50, iterations = 20) { frame.pack() }
        assertEquals(0L, bytes, "GpuFrame.pack allocates")
    }

    @Test
    fun `render does not allocate`() {
        layer(0, prio = 1, fill = 1)
        layer(1, prio = 0, fill = 2 or TileLayer.HFLIP)
        state.lines.setScroll(10, 1, 3, 4)
        state.sprites.add(x = 5, y = 5, width = 32, height = 32, sheet = 2, tile = 0, palette = 1, priority = 0)
        state.sprites.add(x = 50, y = 5, width = 16, height = 16, sheet = 2, tile = 0, palette = 1, priority = 2,
            flags = SpriteBatch.AFFINE or SpriteBatch.DOUBLE_SIZE)
        state.sprites.add(x = 90, y = 5, width = 8, height = 8, sheet = 2, tile = 0, palette = 1, priority = 0, flags = SpriteBatch.MODE_WINDOW)
        state.effects.apply {
            win0 = true; winLeft[0] = 20; winRight[0] = 60; winTop[0] = 10; winBottom[0] = 100; winInside[0] = 0x3F
            objWindow = true; winInside[2] = 0x13; winOutside = 0x33
            blendMode = Effects.BLEND_ALPHA; blendTop = 0x3; blendBottom = 0x3C; eva = 9; evb = 7
            mosaic[0] = 2
        }
        state.layers[1].mosaic = true
        val bytes = AllocationMeter.measure(warmup = 500, iterations = 200) { renderer.render(cam) }
        assertEquals(0L, bytes)
    }

    @Test
    fun `sprite tiles follow 2D and 1D mapping and flips`() {
        state.sprites.add(x = 10, y = 20, width = 16, height = 16, sheet = 2, tile = 1, palette = 0, priority = 0)
        renderer.render(cam)
        assertEquals(argb(0x0001), fb[9, 20])
        assertEquals(objTile(1), fb[10, 20])
        assertEquals(objTile(2), fb[18, 20])
        assertEquals(objTile(33), fb[10, 28]) // 2D: next row is 32 units on
        assertEquals(argb(0x0001), fb[26, 20])
        state.sprites.mapping1d = true
        renderer.render(cam)
        assertEquals(objTile(3), fb[10, 28]) // 1D: next row follows the 2 tiles of row 0
        state.sprites.flags[0] = (SpriteBatch.HFLIP or SpriteBatch.VFLIP).toShort()
        renderer.render(cam)
        assertEquals(objTile(4), fb[10, 20]) // bottom-right tile now top-left
        assertEquals(objTile(1), fb[25, 35])
    }

    @Test
    fun `sprite palette bank and 8bpp`() {
        state.sprites.add(x = 0, y = 0, width = 8, height = 8, sheet = 2, tile = 0, palette = 3, priority = 0)
        state.sprites.add(x = 8, y = 0, width = 8, height = 8, sheet = 2, tile = 64, palette = 3, priority = 0, flags = SpriteBatch.BPP8)
        renderer.render(cam)
        assertEquals(argb(0x0BBB), fb[0, 0])
        assertEquals(argb(0x0CCC), fb[8, 0])
    }

    @Test
    fun `sprite versus BG and sprite versus sprite priority`() {
        layer(0, prio = 1, fill = 1) // BG0 colour 0x101
        state.sprites.add(x = 0, y = 0, width = 8, height = 8, sheet = 2, tile = 0, palette = 0, priority = 2) // behind BG0
        state.sprites.add(x = 0, y = 8, width = 8, height = 8, sheet = 2, tile = 0, palette = 0, priority = 1) // equal: OBJ wins
        state.sprites.add(x = 16, y = 0, width = 8, height = 8, sheet = 2, tile = 0, palette = 0, priority = 0) // index 2 ...
        state.sprites.add(x = 16, y = 0, width = 8, height = 8, sheet = 2, tile = 5, palette = 0, priority = 0) // ... beats 3
        state.sprites.add(x = 32, y = 0, width = 8, height = 8, sheet = 2, tile = 0, palette = 0, priority = 3) // index 4 ...
        state.sprites.add(x = 32, y = 0, width = 8, height = 8, sheet = 2, tile = 5, palette = 0, priority = 0) // ... loses to lower prio value
        renderer.render(cam)
        assertEquals(argb(0x101), fb[0, 0])
        assertEquals(objTile(0), fb[0, 8])
        assertEquals(objTile(0), fb[16, 0])
        assertEquals(objTile(5), fb[32, 0])
    }

    @Test
    fun `affine sprites scale about their centre and double size enlarges the box`() {
        // Tile 0 of a 16x16 sprite at units 0,1,32,33 → four distinct colours per quadrant.
        state.sprites.setAffine(0, pa = 0x80, pb = 0, pc = 0, pd = 0x80) // 2x magnification
        state.sprites.add(x = 100, y = 50, width = 16, height = 16, sheet = 2, tile = 0, palette = 0, priority = 0,
            flags = SpriteBatch.AFFINE or SpriteBatch.DOUBLE_SIZE, affineIndex = 0)
        renderer.render(cam)
        // Box is 32x32 at (100, 50); centre (116, 66) maps to texel (8, 8) = unit 33.
        assertEquals(objTile(33), fb[116, 66])
        assertEquals(objTile(0), fb[100, 50]) // texel (0, 0)
        assertEquals(objTile(1), fb[131, 50]) // dx = 15 → texel x = 7 + 8 = 15
        assertEquals(argb(0x0001), fb[132, 50])
        state.sprites.flags[0] = SpriteBatch.AFFINE.toShort() // no double size: 16x16 box, centre region only
        renderer.render(cam)
        assertEquals(objTile(0), fb[100, 50]) // dx = -8 → texel 4
        assertEquals(objTile(0), fb[107, 57])
        assertEquals(objTile(33), fb[108, 58])
        assertEquals(argb(0x0001), fb[116, 66])
    }

    @Test
    fun `OBJ palette writes from the line table only affect sprites`() {
        state.sprites.add(x = 0, y = 0, width = 8, height = 8, sheet = 2, tile = 0, palette = 0, priority = 0)
        layer(1, prio = 0, fill = 0).setEntry(1, 0, 1) // BG colour index 1 at x 8..15
        state.lines.writePalette(line = 4, index = 256 + 1, color = 0x0DDD)
        renderer.render(cam)
        assertEquals(objTile(0), fb[0, 3])
        assertEquals(argb(0x0DDD), fb[0, 4])
        assertEquals(argb(0x101), fb[8, 4])
    }

    /** BG0 (front) solid red 0x001F (16 in the effect tests below), BG1 solid blue 0x7C00. */
    private fun twoLayers(red: Int = 0x1F) {
        state.palettes.bg[1] = red.toShort()
        state.palettes.bg[2] = 0x7C00
        layer(0, prio = 0, fill = 1)
        layer(1, prio = 1, fill = 3)
    }

    @Test
    fun `alpha blend mixes the top two targets`() {
        twoLayers()
        val fx = state.effects
        fx.blendMode = Effects.BLEND_ALPHA; fx.blendTop = 1; fx.blendBottom = 2; fx.eva = 8; fx.evb = 8
        renderer.render(cam)
        assertEquals(argb(15 or (15 shl 10)), fb[0, 0])
        fx.blendBottom = 4 // BG1 is not a second target: no blend
        renderer.render(cam)
        assertEquals(argb(0x1F), fb[0, 0])
        fx.blendBottom = 2; fx.eva = 16; fx.evb = 16 // saturates at 31
        renderer.render(cam)
        assertEquals(argb(0x1F or (0x1F shl 10)), fb[0, 0])
    }

    @Test
    fun `brightness up and down`() {
        twoLayers(red = 16)
        val fx = state.effects
        fx.blendMode = Effects.BLEND_BRIGHTEN; fx.blendTop = 1; fx.evy = 8
        renderer.render(cam)
        assertEquals(argb(23 or (15 shl 5) or (15 shl 10)), fb[0, 0])
        fx.blendMode = Effects.BLEND_DARKEN
        renderer.render(cam)
        assertEquals(argb(8), fb[0, 0])
        fx.blendTop = Effects.BACKDROP // BG0 not a first target
        renderer.render(cam)
        assertEquals(argb(16), fb[0, 0])
    }

    @Test
    fun `windows select layers and colour effects per pixel`() {
        twoLayers(red = 16)
        val fx = state.effects
        fx.win0 = true
        fx.winLeft[0] = 10; fx.winRight[0] = 20; fx.winTop[0] = 0; fx.winBottom[0] = 160
        fx.winInside[0] = 2 // BG1 only, no effects
        fx.winOutside = 0x3F
        fx.blendMode = Effects.BLEND_DARKEN; fx.blendTop = 1 or 2; fx.evy = 16
        renderer.render(cam)
        assertEquals(argb(0x7C00), fb[15, 0]) // BG0 hidden, BG1 not darkened
        assertEquals(argb(0), fb[5, 0]) // outside: BG0 darkened to black
        fx.winLeft[0] = 230; fx.winRight[0] = 10 // wraps: 230..239 and 0..9
        renderer.render(cam)
        assertEquals(argb(0x7C00), fb[235, 0])
        assertEquals(argb(0x7C00), fb[5, 0])
        assertEquals(argb(0), fb[100, 0])
        state.lines.setWindowX(line = 3, win = 0, left = 100, right = 110) // per-line override
        renderer.render(cam)
        assertEquals(argb(0x7C00), fb[105, 3])
        assertEquals(argb(0), fb[5, 3])
    }

    @Test
    fun `OBJ window sprites gate layers and are not drawn`() {
        twoLayers()
        state.sprites.add(x = 0, y = 0, width = 8, height = 8, sheet = 2, tile = 0, palette = 0, priority = 0, flags = SpriteBatch.MODE_WINDOW)
        val fx = state.effects
        fx.objWindow = true; fx.winInside[2] = 2; fx.winOutside = 0x3F
        renderer.render(cam)
        assertEquals(argb(0x7C00), fb[3, 3])
        assertEquals(argb(0x1F), fb[8, 3])
    }

    @Test
    fun `semi-transparent sprites blend over a second target in any mode`() {
        twoLayers()
        state.palettes.obj[1] = 0x03E0 // green
        state.sprites.add(x = 0, y = 0, width = 8, height = 8, sheet = 2, tile = 15, palette = 0, priority = 0, flags = SpriteBatch.MODE_SEMI)
        val fx = state.effects
        fx.blendBottom = 1; fx.eva = 8; fx.evb = 8 // blendMode stays NONE
        renderer.render(cam)
        assertEquals(argb(15 or (15 shl 5)), fb[0, 0])
        state.lines.setBlend(line = 2, eva = 16, evb = 0, evy = 0)
        renderer.render(cam)
        assertEquals(argb(0x03E0), fb[0, 2])
    }

    @Test
    fun `BG mosaic samples the top-left pixel of each block`() {
        val l = layer(0, prio = 0, fill = 0)
        l.setEntry(0, 0, 2) // row 0: index 1 + x
        l.mosaic = true
        state.effects.mosaic[0] = 4
        state.effects.mosaic[1] = 2
        renderer.render(cam)
        assertEquals(argb(0x101), fb[3, 0])
        assertEquals(argb(0x105), fb[5, 0])
        assertEquals(argb(0x105), fb[7, 1]) // line 1 samples line 0
        assertEquals(argb(0x0001), fb[0, 2])
    }

    private fun affineLayer(): TileLayer = state.layers[2].also { l ->
        l.enabled = true; l.affine = true; l.bpp8 = true; l.sheet = 1
        l.widthTiles = 16; l.heightTiles = 16
        l.setEntry(0, 0, 0x101) // only the low byte (tile 1) counts on affine maps
    }

    @Test
    fun `affine BG samples with the reference point and matrix`() {
        val l = affineLayer()
        val green = argb(0x03E0)
        val back = argb(0x0001)
        renderer.render(cam)
        assertEquals(green, fb[0, 0]); assertEquals(green, fb[7, 7]); assertEquals(back, fb[8, 0])
        l.refX = -8 shl 8 // shift right by 8 px; no wrap: outside is transparent
        renderer.render(cam)
        assertEquals(back, fb[0, 0]); assertEquals(green, fb[8, 0])
        l.wrap = true; l.setEntry(15, 0, 1) // wrap: x = -8 is tile 15
        renderer.render(cam)
        assertEquals(green, fb[0, 0])
        l.refX = 0; l.wrap = false; l.pa = 0x80 // 2x horizontal magnification
        renderer.render(cam)
        assertEquals(green, fb[15, 0]); assertEquals(back, fb[16, 0])
    }

    @Test
    fun `affine BG reference advances by pb and pd per line and line overrides reload it`() {
        val l = affineLayer()
        val green = argb(0x03E0)
        val back = argb(0x0001)
        l.pd = 0x200 // each line steps two texels down
        renderer.render(cam)
        assertEquals(green, fb[0, 3]); assertEquals(back, fb[0, 4])
        state.lines.setScroll(line = 4, bg = 2, x = 0, y = 0) // reload the reference on line 4
        renderer.render(cam)
        assertEquals(green, fb[0, 4]); assertEquals(green, fb[0, 7]); assertEquals(back, fb[0, 8])
        state.lines.clear()
        l.pd = 0x100; l.pb = 0x100 // shear: line y starts y texels to the right
        renderer.render(cam)
        assertEquals(green, fb[0, 7]); assertEquals(back, fb[1, 7])
    }
}
