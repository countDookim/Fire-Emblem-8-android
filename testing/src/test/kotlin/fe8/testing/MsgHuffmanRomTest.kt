package fe8.testing

import fe8.core.VerifiesPort
import fe8.text.MsgHuffman
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Golden test of [MsgHuffman] on ROM strings whose text is known independently of our decoder: the
 * names referenced by the decomp tables (`src/data_items.c` Iron Sword `nameTextId = 0x354`,
 * `src/data_characters.c` Eirika `0x212`, Seth `0x213`), as shown in game. Addresses from
 * decomp/fireemblem8.nm.txt: `gMsgHuffmanTable` 0x0815A72C, `gMsgHuffmanTableRoot` 0x0815D488,
 * `gMsgTable` 0x0815D48C (0x3530 bytes = 3404 string pointers).
 */
class MsgHuffmanRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    @Test
    @VerifiesPort("DecodeString")
    fun `known names decode exactly`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val root = u32(rom, 0x0815D488 - 0x08000000) - 0x08000000
        val huff = MsgHuffman(rom, 0x0815A72C - 0x08000000, root)
        fun text(id: Int): String {
            val bytes = huff.decode(u32(rom, 0x0815D48C - 0x08000000 + id * 4) - 0x08000000)
            assertEquals(0, bytes.last().toInt(), "terminator")
            return String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1)
        }
        assertEquals("Iron Sword", text(0x354))
        assertEquals("Eirika", text(0x212))
        assertEquals("Seth", text(0x213))
    }
}
