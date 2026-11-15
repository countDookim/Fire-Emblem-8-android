package fe8.testing

import gba.core.VerifiesPort
import gbafe.text.MsgHuffman
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Golden test of [MsgHuffman] against the runtime extraction ([RuntimeMessages]) for the name messages
 * the decomp tables reference (`ITEM_SWORD_IRON` `nameTextId = 0x354`, `CHARACTER_EIRIKA` `0x212`,
 * `CHARACTER_SETH` `0x213`); no game text is embedded (CLAUDE.md rule 3). Addresses from
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
        val expected = RuntimeMessages.load()
        for (id in intArrayOf(0x354, 0x212, 0x213)) {
            val decoded = text(id)
            assertEquals(expected[id], decoded, "message 0x${id.toString(16)}")
            assertEquals(true, decoded.isNotEmpty() && decoded.all { it in ' '..'~' }, "message 0x${id.toString(16)} is a plain name")
        }
    }

    /** Every `gMsgTable` entry decodes and terminates (groundwork for MessagesRomTest); not a port verification. */
    @Test
    fun `all 3404 messages decode`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val huff = MsgHuffman(rom, 0x0815A72C - 0x08000000, u32(rom, 0x0815D488 - 0x08000000) - 0x08000000)
        var total = 0
        for (id in 0 until 0x3530 / 4) {
            val ptr = u32(rom, 0x0815D48C - 0x08000000 + id * 4)
            assertEquals(4, ptr ushr 25, "message $id pointer 0x${ptr.toUInt().toString(16)}")
            val bytes = huff.decode(ptr - 0x08000000)
            assertEquals(0, bytes.last().toInt(), "message $id terminator")
            total += bytes.size
        }
        assertEquals(true, total > 100_000, "decoded $total bytes")
    }
}
