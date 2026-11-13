package fe8.testing

import gba.render.FrameBuffer
import gba.render.RenderState
import gba.render.TileSheets
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Test helpers for frame compares against mGBA dumps: loading a dumped frame's hardware state (via
 * [gbafe.render.HardwareScene]) and comparing a rendered [FrameBuffer] with mGBA's own frame.
 */
public object HardwareScene {
    /** Sheet id of the OBJ tile area. */
    public const val OBJ_SHEET: Int = gbafe.render.HardwareScene.OBJ_SHEET

    /** True when `DISPCNT` bit 7 (forced blank) is set in [io]: the LCD shows white. */
    public fun forcedBlank(io: ByteArray): Boolean = u16(io, 0) and 0x80 != 0

    /**
     * Fills [state] and [sheets] from dump regions of one frame
     * ([gbafe.render.HardwareScene.load]).
     */
    public fun load(
        state: RenderState,
        sheets: TileSheets,
        vram: ByteArray,
        pal: ByteArray,
        oam: ByteArray,
        io: ByteArray,
    ) {
        gbafe.render.HardwareScene.load(state, sheets, vram, pal, oam, io)
    }

    /**
     * Compares [fb] against an mGBA `frame.rgb` dump (240×160 RGB888) at 5-bit precision.
     * Returns the number of differing pixels; when non-zero and [diffPng] is given, writes a PNG
     * with the expected frame left, ours in the middle and, on the right, differing pixels in
     * red and matching pixels as a dimmed copy of the expected frame.
     */
    public fun compare(fb: FrameBuffer, rgb: ByteArray, diffPng: File? = null): Int {
        val width = FrameBuffer.GBA_WIDTH
        val height = FrameBuffer.GBA_HEIGHT
        var diffCount = 0
        for (pixelIndex in 0 until width * height) {
            val expectedColor = expected5(rgb, pixelIndex)
            val actualColor =
                fb.pixels[(pixelIndex / width) * fb.width + pixelIndex % width] and COLOR_MASK_5BIT
            if (expectedColor != actualColor) {
                diffCount++
            }
        }
        if (diffCount > 0 && diffPng != null) {
            val img = BufferedImage(width * 3, height, BufferedImage.TYPE_INT_RGB)
            for (pixelIndex in 0 until width * height) {
                val x = pixelIndex % width
                val y = pixelIndex / width
                val expectedColor = expected5(rgb, pixelIndex)
                val actualColor = fb.pixels[y * fb.width + x] and COLOR_MASK_5BIT
                img.setRGB(x, y, expectedColor)
                img.setRGB(width + x, y, actualColor)
                img.setRGB(
                    2 * width + x,
                    y,
                    if (expectedColor != actualColor) {
                        0xFF0000
                    } else {
                        (expectedColor shr 2) and 0x3F3F3F
                    },
                )
            }
            diffPng.parentFile?.mkdirs()
            ImageIO.write(img, "png", diffPng)
        }
        return diffCount
    }

    /**
     * Keeps the top 5 bits of each 8-bit RGB channel: the GBA has 5 bits per channel, so the low
     * 3 bits of a converted RGB888 pixel carry no information and are ignored when comparing.
     */
    private const val COLOR_MASK_5BIT: Int = 0xF8F8F8

    /**
     * Extracts a 24-bit RGB pixel from [rgb] at [pixelIndex], truncated to 5-bit precision
     * per channel.
     */
    private fun expected5(rgb: ByteArray, pixelIndex: Int): Int =
        ((rgb[3 * pixelIndex].toInt() and 0xF8) shl 16) or
            ((rgb[3 * pixelIndex + 1].toInt() and 0xF8) shl 8) or
            (rgb[3 * pixelIndex + 2].toInt() and 0xF8)

    /** Reads a little-endian unsigned 16-bit integer from [bytes] at [offset]. */
    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
