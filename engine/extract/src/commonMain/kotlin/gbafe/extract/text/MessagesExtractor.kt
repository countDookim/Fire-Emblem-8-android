package gbafe.extract.text

import gba.extract.Json
import gbafe.extract.hexUpper
import gbafe.text.MessageCodec
import gbafe.text.MsgHuffman

/**
 * Extracts every `gMsgTable` string as tagged text (`text/<lang>/messages.json`, format
 * `gbafe.text`).
 *
 * The message table (`gMsgTable`: one 4-byte ROM pointer per message id, each to a Huffman bit
 * stream) and the Huffman tree (`gMsgHuffmanTable`) are laid out as in FE8U (the layout this port
 * follows; other games are not checked); callers pass the game's addresses. Ids are `MSG_%03X` like
 * the decomp's `texts/texts.txt` headers.
 */
public object MessagesExtractor {
    /**
     * Decodes messages 0 until [count] of `gMsgTable` (at ROM offset [tableOffset]) into tagged
     * strings. [huffmanOffset] is the ROM offset of `gMsgHuffmanTable` and [rootOffset] that of
     * its root node (see [MsgHuffman]).
     */
    public fun extract(
        rom: ByteArray,
        huffmanOffset: Int,
        rootOffset: Int,
        tableOffset: Int,
        count: Int,
        codec: MessageCodec,
    ): List<String> {
        val huffman = MsgHuffman(rom, huffmanOffset, rootOffset)
        return List(count) { id ->
            val messagePtr = readU32Le(rom, tableOffset + id * 4)
            // 0x08000000..0x09FFFFFF: the cartridge ROM mirror the game's pointers use.
            require(messagePtr ushr 25 == 4) {
                "message $id pointer 0x${messagePtr.toUInt().toString(16)} is not in ROM"
            }
            codec.decode(huffman.decode(messagePtr - 0x08000000))
        }
    }

    /**
     * Message id of table index [index] as the decomp's `texts.txt` writes it: `MSG_` plus 3+ hex
     * digits.
     */
    public fun id(index: Int): String = "MSG_${hexUpper(index, 3)}"

    /** The `gbafe.text` JSON document (version 1) for [messages] of [game], one entry per id. */
    public fun toJson(game: String, messages: List<String>): String = Json.write(
        linkedMapOf(
            "format" to "gbafe.text", "game" to game, "version" to 1,
            "entries" to messages.mapIndexed { index, text ->
                linkedMapOf("id" to id(index), "text" to text)
            },
        ),
    )

    /**
     * Parses the face names out of the decomp's `texts/textdefs.txt` content [textdefs]: lines
     * `[FID_Name] = 0xLO, 0xHI`, where the two bytes are the argument stored after a `LoadFace`
     * code. The map value is `lo | hi << 8`; other lines (the control codes) are ignored.
     */
    public fun parseFaces(textdefs: String): Map<String, Int> {
        val faceDefRegex =
            Regex("""^\[(FID_\w+)]\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*,\s*(0x[0-9A-Fa-f]+|\d+)\s*$""")
        val faces = LinkedHashMap<String, Int>()
        for (line in textdefs.lines()) {
            val match = faceDefRegex.matchEntire(line.trim()) ?: continue
            faces[match.groupValues[1]] =
                parseNumber(match.groupValues[2]) or (parseNumber(match.groupValues[3]) shl 8)
        }
        return faces
    }

    /** Parses a decimal or `0x`-prefixed hexadecimal token string into an integer. */
    private fun parseNumber(token: String): Int =
        if (token.startsWith("0x")) token.substring(2).toInt(16) else token.toInt()

    /** Reads a 32-bit little-endian unsigned word from [bytes] at [offset]. */
    private fun readU32Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
