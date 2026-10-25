package fe8.testing

import gbafe.extract.text.MessagesExtractor
import gbafe.text.MessageCodec
import gbafe.text.MsgHuffman
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-09: all 3404 `gMsgTable` messages decode to tagged text and re-encode to identical bytes, and two
 * messages match the decomp's `texts/texts.txt` (pinned 04d1edb; its `[LF]` is our `{NL}` alias, source
 * line breaks are not part of the string). Also dumps `testing/build/extracted/text/en/messages.json`.
 * Addresses from decomp/fireemblem8.nm.txt (see [MsgHuffmanRomTest]).
 */
class MessagesRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")
    private val faces = mapOf("FID_Eirika" to 0x0102, "FID_Seth" to 0x0104, "FID_Fado" to 0x0151)
    private val codec = MessageCodec(faces = faces)

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun rom(): ByteArray {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return romFile.readBytes()
    }

    private fun messages(rom: ByteArray) =
        MessagesExtractor.extract(rom, 0x15A72C, u32(rom, 0x15D488) - 0x08000000, 0x15D48C, 0x3530 / 4, codec)

    @Test
    fun `every message round-trips to identical bytes`() {
        val rom = rom()
        val huff = MsgHuffman(rom, 0x15A72C, u32(rom, 0x15D488) - 0x08000000)
        val text = messages(rom)
        assertEquals(3404, text.size)
        for ((id, t) in text.withIndex()) {
            val raw = huff.decode(u32(rom, 0x15D48C + id * 4) - 0x08000000)
            assertArrayEquals(raw, codec.encode(t), "${MessagesExtractor.id(id)}: $t")
        }
    }

    @Test
    fun `messages match texts_txt`() {
        val text = messages(rom())
        assertEquals("Weapon Level increased.{.}", text[0x002])
        assertEquals("Eirika", text[0x212])
        val msg904 = "{OpenMidLeft}{LoadFace FID_Fado}{OpenMidRight}{LoadFace FID_Eirika}{OpenMidRight}{MoveRight}" +
            "{OpenRight}Father{ToggleMouthMove}...{.}{ToggleMouthMove}{A}{OpenMidLeft}Eirika.{.}{A}{NL}" +
            "Are you wearing the{.}{NL}bracelet I gave you?{A}{OpenRight}Yes, I have it right here.{A}"
        assertEquals(msg904, text[0x904].substring(0, msg904.length))
        assertEquals(true, text[0x904].endsWith("{OpenMidLeft}Go now, Seth! Ride!{.}{NL}Take her to safety!{.}{A}"))
    }

    @Test
    fun `dump messages json for schema validation`() {
        val out = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "text/en/messages.json")
        val json = MessagesExtractor.toJson("fe8", messages(rom()))
        out.parentFile.mkdirs()
        out.writeText(json)
    }
}
