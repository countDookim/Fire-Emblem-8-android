package fe8.testing

import gbafe.render.FrameBuffer
import gbafe.render.RenderState
import gbafe.render.TileSheets
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Test helpers for frame compares against mGBA dumps: loading a dumped frame's hardware state (via
 * [gbafe.render.HardwareScene]) and comparing a rendered [FrameBuffer] with mGBA's own frame.
 */
public object HardwareScene {
    /** Sheet id of the OBJ tile area. */
    public const val OBJ_SHEET: Int = gbafe.render.HardwareScene.OBJ_SHEET

    /** True when `DISPCNT` forced blank is set (the screen shows white). */
    public fun forcedBlank(io: ByteArray): Boolean = u16(io, 0) and 0x80 != 0

    /** Fills [state] and [sheets] from the dump regions of one frame ([gbafe.render.HardwareScene.load]). */
    public fun load(state: RenderState, sheets: TileSheets, vram: ByteArray, pal: ByteArray, oam: ByteArray, io: ByteArray) {
        gbafe.render.HardwareScene.load(state, sheets, vram, pal, oam, io)
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

}
