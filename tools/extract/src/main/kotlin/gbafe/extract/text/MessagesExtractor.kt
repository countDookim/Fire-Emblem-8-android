package gbafe.extract.text

import gbafe.extract.Json
import gbafe.text.MessageCodec
import gbafe.text.MsgHuffman
import java.io.File

/**
 * Extracts every `gMsgTable` string as tagged text (`text/<lang>/messages.json`, format `gbafe.text`).
 *
 * The message table (4-byte ROM pointers to Huffman bit streams) and the Huffman tree are laid out the
 * same in all GBA Fire Emblem games; callers pass the game's addresses. Ids are `MSG_%03X` like the
 * decomp's `texts/texts.txt` headers.
 */
public object MessagesExtractor {
    /** Decodes [count] messages of the table at ROM offset [tableOffset] into tagged strings. */
    public fun extract(rom: ByteArray, huffmanOffset: Int, rootOffset: Int, tableOffset: Int, count: Int, codec: MessageCodec): List<String> {
        val huff = MsgHuffman(rom, huffmanOffset, rootOffset)
        return List(count) { id ->
            val ptr = u32(rom, tableOffset + id * 4)
            require(ptr ushr 25 == 4) { "message $id pointer 0x${ptr.toUInt().toString(16)} is not in ROM" }
            codec.decode(huff.decode(ptr - 0x08000000))
        }
    }

    /** Message id as written in the decomp (`MSG_000`, `MSG_212`, ...). */
    public fun id(index: Int): String = "MSG_%03X".format(index)

    /** The `gbafe.text` document for [messages] of [game]. */
    public fun toJson(game: String, messages: List<String>): String = Json.write(
        linkedMapOf(
            "format" to "gbafe.text", "game" to game, "version" to 1,
            "entries" to messages.mapIndexed { i, t -> linkedMapOf("id" to id(i), "text" to t) },
        ),
    )

    /**
     * Reads face names from the decomp's `texts/textdefs.txt` lines `[FID_Name] = 0xLO, 0xHI`; the value
     * is `lo | hi << 8` as stored after `LoadFace`.
     */
    public fun readFaces(textdefs: File): Map<String, Int> {
        val re = Regex("""^\[(FID_\w+)]\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*,\s*(0x[0-9A-Fa-f]+|\d+)\s*$""")
        val out = LinkedHashMap<String, Int>()
        for (line in textdefs.readLines()) {
            val m = re.matchEntire(line.trim()) ?: continue
            out[m.groupValues[1]] = num(m.groupValues[2]) or (num(m.groupValues[3]) shl 8)
        }
        return out
    }

    private fun num(s: String) = if (s.startsWith("0x")) s.substring(2).toInt(16) else s.toInt()

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
