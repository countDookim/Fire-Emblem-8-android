package fe8.testing

import fe8.data.Lz77
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Golden test of [Lz77] on real compressed graphics from the ROM. The streams are located by decomp ELF
 * symbols (`decomp/fireemblem8.nm.txt` in the ROM repo): each must decode to exactly its header size and
 * end within the 0..3 alignment-padding bytes before the next symbol.
 */
class Lz77RomTest {
    private val rom = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    /** A compressed symbol, the address of the symbol after it, and its expected decompressed size. */
    private data class Stream(val name: String, val addr: Int, val next: Int, val size: Int)

    private val streams = listOf(
        Stream("Img_080DB034", 0x080DB034, 0x080DB538, 8192),
        Stream("Img_TriPegasusKnightBG", 0x080E17C0, 0x080E2164, 8192),
    )

    @Test
    fun `ROM graphics decode to their header size and end at the next symbol`() {
        assumeTrue(rom.isFile, "ROM not present (run tools/setup.sh)")
        val bytes = rom.readBytes()
        for ((name, addr, next, size) in streams) {
            val off = addr - 0x08000000
            val r = Lz77.decode(bytes, off)
            assertEquals(size, r.data.size, name)
            val pad = next - addr - r.consumed
            assertTrue(pad in 0..3, "$name: consumed ${r.consumed}, span ${next - addr}")
        }
    }
}
