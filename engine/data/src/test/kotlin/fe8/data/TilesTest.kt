package fe8.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TilesTest {
    @Test
    fun `4bpp low nibble is the left pixel`() {
        val tile = ByteArray(32).also { it[0] = 0x21; it[4] = 0xF3.toByte() }
        val img = Tiles.decode4bpp(tile, 0, 1, 1)
        assertEquals(8, img.width)
        assertEquals(1, img[0, 0]); assertEquals(2, img[1, 0])
        assertEquals(3, img[0, 1]); assertEquals(15, img[1, 1])
        assertArrayEquals(tile, Tiles.encode4bpp(img))
    }

    @Test
    fun `tiles are laid out row-major and round-trip`() {
        val src = ByteArray(5 * 32) { (it * 37 + 11).toByte() }
        val img = Tiles.decode4bpp(src, 0, 5, 2)
        assertEquals(16, img.width); assertEquals(24, img.height)
        // Tile 3 = second column, second row; its first byte's low nibble is pixel (8, 8).
        assertEquals(src[3 * 32].toInt() and 0xF, img[8, 8])
        assertArrayEquals(src, Tiles.encode4bpp(img, 5))
    }

    @Test
    fun `8bpp round-trips`() {
        val src = ByteArray(3 * 64) { (it * 101).toByte() }
        val img = Tiles.decode8bpp(src, 0, 3, 3)
        assertEquals(src[64 + 9].toInt() and 0xFF, img[8 + 1, 1])
        assertArrayEquals(src, Tiles.encode8bpp(img))
    }

    @Test
    fun `4bpp encode rejects indices above 15`() {
        val img = IndexedImage(8, 8).also { it[3, 3] = 16 }
        assertThrows<IllegalArgumentException> { Tiles.encode4bpp(img) }
    }

    @Test
    fun `bgr555 palette round-trips`() {
        val raw = byteArrayOf(0x1F, 0x00, 0xE0.toByte(), 0x03, 0x00, 0x7C, 0x34, 0x12)
        val argb = Bgr555.readPalette(raw, 0, 4)
        assertEquals(0xFFF80000.toInt(), argb[0])
        assertEquals(0xFF00F800.toInt(), argb[1])
        assertEquals(0xFF0000F8.toInt(), argb[2])
        assertArrayEquals(raw, Bgr555.writePalette(argb))
    }
}
