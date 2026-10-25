package fe8.testing

import fe8.data.Bgr555
import fe8.data.Lz77
import fe8.data.Tiles
import fe8.extract.IndexedPng
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ROM round trip of the extraction path for tile graphics: LZ77 stream → 4bpp tiles → indexed PNG →
 * tiles → bytes must reproduce the decompressed ROM data exactly (M2-03).
 */
class TilesPngRomTest {
    private val rom = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    @Test
    fun `Img_TriPegasusKnightBG survives tiles to PNG and back byte-identically`() {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        val bytes = rom.readBytes()
        val tiles = Lz77.decode(bytes, 0x080E17C0 - 0x08000000).data
        val count = tiles.size / Tiles.BYTES_4BPP // 256
        val img = Tiles.decode4bpp(tiles, 0, count, tilesPerRow = 32)
        // Any 16-colour palette works for an index round trip; use one read through the BGR555 path.
        val pal = Bgr555.readPalette(ByteArray(32) { (it * 13).toByte() }, 0, 16)
        val png = IndexedPng.write(img, pal)
        val back = IndexedPng.read(png)
        assertArrayEquals(tiles, Tiles.encode4bpp(back.image, count))
        assertArrayEquals(pal, back.palette)
    }
}
