package gbafe.extract

import gbafe.data.IndexedImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.IndexColorModel
import javax.imageio.ImageIO

class IndexedPngTest {
    private val palette = IntArray(16) { (0xFF shl 24) or (it * 0x101010) }

    private fun sample() = IndexedImage(13, 7, ByteArray(13 * 7) { ((it * 7) % 16).toByte() })

    @Test
    fun `write then read preserves indices and palette`() {
        val img = sample()
        val d = IndexedPng.read(IndexedPng.write(img, palette))
        assertEquals(13, d.image.width); assertEquals(7, d.image.height)
        assertArrayEquals(img.pixels, d.image.pixels)
        assertArrayEquals(palette, d.palette)
    }

    @Test
    fun `output is a valid palette PNG for ImageIO`() {
        val img = sample()
        val bi = ImageIO.read(IndexedPng.write(img, palette).inputStream())
        val cm = bi.colorModel as IndexColorModel
        // ImageIO pads its colour model to 256 entries, so check a colour rather than the size.
        assertEquals(palette[7], cm.getRGB(7))
        assertEquals(img[5, 3], bi.raster.getSample(5, 3, 0))
    }

    @Test
    fun `reads filtered PNGs written by ImageIO`() {
        // ImageIO picks per-row filters (Sub/Up/Paeth...), exercising the unfilter path.
        val cm = IndexColorModel(8, 16, IntArray(16) { palette[it] }, 0, false, -1, java.awt.image.DataBuffer.TYPE_BYTE)
        val bi = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_BYTE_INDEXED, cm)
        for (y in 0 until 30) for (x in 0 until 40) bi.raster.setSample(x, y, 0, (x * y + x) % 16)
        val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(bi, "png", it) }.toByteArray()
        val d = IndexedPng.read(bytes)
        for (y in 0 until 30) for (x in 0 until 40) assertEquals((x * y + x) % 16, d.image[x, y], "($x,$y)")
    }

    @Test
    fun `corrupt CRC is rejected`() {
        val bytes = IndexedPng.write(sample(), palette)
        bytes[20] = (bytes[20] + 1).toByte()
        assertThrows<IllegalArgumentException> { IndexedPng.read(bytes) }
    }

    @Test
    fun `index outside palette is rejected`() {
        assertThrows<IllegalArgumentException> { IndexedPng.write(IndexedImage(1, 1, byteArrayOf(5)), IntArray(4)) }
    }
}
