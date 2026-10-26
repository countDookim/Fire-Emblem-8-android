package fe8.testing

import gbafe.render.Effects
import gbafe.render.FrameBuffer
import gbafe.render.RenderState
import gbafe.render.SpriteBatch
import gbafe.render.TileLayer
import gbafe.render.TileSheets
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Test-only bridge from raw GBA hardware state (an mGBA dump: VRAM, palette RAM, OAM, I/O registers) to the
 * semantic [RenderState] + [TileSheets] the [gbafe.render.SoftwareRenderer] draws. Lets frame-compare tests
 * check the compositor against mGBA's own output for any dumped frame, before game systems fill the render
 * state themselves. Text-mode backgrounds (mode 0, and BG0/BG1 of mode 1) only; affine BGs are marked
 * [TileLayer.affine] and skipped by the renderer for now.
 *
 * Sheet ids: 0..3 = BG character base blocks (VRAM 0x0000, 0x4000, 0x8000, 0xC000, each up to the end
 * of BG VRAM), 4 = OBJ tiles (VRAM 0x10000).
 */
public object HardwareScene {
    /** Sheet id of the OBJ tile area. */
    public const val OBJ_SHEET: Int = 4

    private val objSizes = arrayOf(
        intArrayOf(8, 8, 16, 16, 32, 32, 64, 64),
        intArrayOf(16, 8, 32, 8, 32, 16, 64, 32),
        intArrayOf(8, 16, 8, 32, 16, 32, 32, 64),
    )

    /** True when `DISPCNT` forced blank is set (the screen shows white). */
    public fun forcedBlank(io: ByteArray): Boolean = u16(io, 0) and 0x80 != 0

    /** Fills [state] and [sheets] from the dump regions of one frame. */
    public fun load(state: RenderState, sheets: TileSheets, vram: ByteArray, pal: ByteArray, oam: ByteArray, io: ByteArray) {
        state.reset()
        val dispcnt = u16(io, 0)
        val mode = dispcnt and 7
        for (b in 0 until 4) sheets[b] = vram.copyOfRange(b * 0x4000, 0x10000)
        sheets[OBJ_SHEET] = vram.copyOfRange(0x10000, 0x18000)
        for (i in 0 until 256) {
            state.palettes.bg[i] = u16(pal, 2 * i).toShort()
            state.palettes.obj[i] = u16(pal, 0x200 + 2 * i).toShort()
        }
        for (bg in 0 until 4) {
            val l = state.layers[bg]
            val cnt = u16(io, 0x08 + 2 * bg)
            l.enabled = dispcnt and (0x100 shl bg) != 0
            l.priority = cnt and 3
            l.sheet = (cnt shr 2) and 3
            l.mosaic = cnt and 0x40 != 0
            l.bpp8 = cnt and 0x80 != 0
            l.affine = mode == 2 || (mode == 1 && bg == 2)
            if (mode > 2 || (mode == 1 && bg == 3) || (mode == 2 && bg < 2)) l.enabled = false
            val size = (cnt shr 14) and 3
            l.widthTiles = if (size and 1 != 0) 64 else 32
            l.heightTiles = if (size and 2 != 0) 64 else 32
            l.scrollX = u16(io, 0x10 + 4 * bg) and 0x1FF
            l.scrollY = u16(io, 0x12 + 4 * bg) and 0x1FF
            val screenBase = ((cnt shr 8) and 0x1F) * 0x800
            for (ty in 0 until l.heightTiles) for (tx in 0 until l.widthTiles) {
                var block = 0
                if (tx >= 32) block += 1
                if (ty >= 32) block += if (l.widthTiles == 64) 2 else 1
                val o = screenBase + block * 0x800 + ((ty and 31) * 32 + (tx and 31)) * 2
                l.setEntry(tx, ty, if (o + 1 < 0x10000) u16(vram, o) else 0)
            }
        }
        val fx = state.effects
        fx.win0 = dispcnt and 0x2000 != 0
        fx.win1 = dispcnt and 0x4000 != 0
        fx.objWindow = dispcnt and 0x8000 != 0
        for (w in 0..1) {
            val h = u16(io, 0x40 + 2 * w)
            val v = u16(io, 0x44 + 2 * w)
            fx.winLeft[w] = h ushr 8; fx.winRight[w] = h and 0xFF
            fx.winTop[w] = v ushr 8; fx.winBottom[w] = v and 0xFF
        }
        val winin = u16(io, 0x48)
        val winout = u16(io, 0x4A)
        fx.winInside[0] = winin and 0x3F; fx.winInside[1] = (winin shr 8) and 0x3F
        fx.winOutside = winout and 0x3F; fx.winInside[2] = (winout shr 8) and 0x3F
        val mosaic = u16(io, 0x4C)
        for (i in 0 until 4) fx.mosaic[i] = ((mosaic shr (4 * i)) and 0xF) + 1
        val bldcnt = u16(io, 0x50)
        fx.blendTop = bldcnt and 0x3F
        fx.blendMode = (bldcnt shr 6) and 3 // same numbering as Effects.BLEND_*
        fx.blendBottom = (bldcnt shr 8) and 0x3F
        val bldalpha = u16(io, 0x52)
        fx.eva = bldalpha and 0x1F; fx.evb = (bldalpha shr 8) and 0x1F
        fx.evy = u16(io, 0x54) and 0x1F

        val sp = state.sprites
        sp.mapping1d = dispcnt and 0x40 != 0
        for (m in 0 until SpriteBatch.AFFINE_MATRICES) {
            sp.setAffine(m, s16(oam, m * 32 + 6), s16(oam, m * 32 + 14), s16(oam, m * 32 + 22), s16(oam, m * 32 + 30))
        }
        if (dispcnt and 0x1000 == 0) return
        for (i in 0 until 128) {
            val a0 = u16(oam, i * 8)
            val a1 = u16(oam, i * 8 + 2)
            val a2 = u16(oam, i * 8 + 4)
            val affine = a0 and 0x100 != 0
            if (!affine && a0 and 0x200 != 0) continue // disabled
            val shape = (a0 shr 14) and 3
            if (shape == 3) continue
            val size = (a1 shr 14) and 3
            val w = objSizes[shape][size * 2]
            val h = objSizes[shape][size * 2 + 1]
            val dbl = affine && a0 and 0x200 != 0
            var y = a0 and 0xFF
            if (y + (if (dbl) 2 * h else h) > 256) y -= 256
            var x = a1 and 0x1FF
            if (x >= 256) x -= 512
            var flags = 0
            if (affine) flags = flags or SpriteBatch.AFFINE
            if (dbl) flags = flags or SpriteBatch.DOUBLE_SIZE
            if (!affine && a1 and 0x1000 != 0) flags = flags or SpriteBatch.HFLIP
            if (!affine && a1 and 0x2000 != 0) flags = flags or SpriteBatch.VFLIP
            if (a0 and 0x1000 != 0) flags = flags or SpriteBatch.MOSAIC
            if (a0 and 0x2000 != 0) flags = flags or SpriteBatch.BPP8
            when ((a0 shr 10) and 3) {
                1 -> flags = flags or SpriteBatch.MODE_SEMI
                2 -> flags = flags or SpriteBatch.MODE_WINDOW
                3 -> continue // prohibited mode
            }
            sp.add(x, y, w, h, OBJ_SHEET, a2 and 0x3FF, (a2 shr 12) and 0xF, (a2 shr 10) and 3, flags, (a1 shr 9) and 0x1F)
        }
    }

    /**
     * Compares [fb] against an mGBA `frame.rgb` dump (240×160 RGB888) at 5-bit precision.
     * Returns the number of differing pixels; when non-zero and [diffPng] is given, writes a PNG with the
     * expected frame left, ours in the middle and differing pixels in red on the right.
     */
    public fun compare(fb: FrameBuffer, rgb: ByteArray, diffPng: File? = null): Int {
        val w = FrameBuffer.GBA_WIDTH
        val h = FrameBuffer.GBA_HEIGHT
        var diffs = 0
        for (i in 0 until w * h) {
            if (expected5(rgb, i) != (fb.pixels[(i / w) * fb.width + i % w] and 0xF8F8F8)) diffs++
        }
        if (diffs > 0 && diffPng != null) {
            val img = BufferedImage(w * 3, h, BufferedImage.TYPE_INT_RGB)
            for (i in 0 until w * h) {
                val x = i % w
                val y = i / w
                val exp = expected5(rgb, i)
                val ours = fb.pixels[y * fb.width + x] and 0xF8F8F8
                img.setRGB(x, y, exp)
                img.setRGB(w + x, y, ours)
                img.setRGB(2 * w + x, y, if (exp != ours) 0xFF0000 else (exp shr 2) and 0x3F3F3F)
            }
            diffPng.parentFile?.mkdirs()
            ImageIO.write(img, "png", diffPng)
        }
        return diffs
    }

    private fun expected5(rgb: ByteArray, i: Int): Int =
        ((rgb[3 * i].toInt() and 0xF8) shl 16) or ((rgb[3 * i + 1].toInt() and 0xF8) shl 8) or (rgb[3 * i + 2].toInt() and 0xF8)

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
}
