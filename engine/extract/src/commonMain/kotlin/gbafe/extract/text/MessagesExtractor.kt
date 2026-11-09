package gbafe.extract.text

import gba.extract.Json
import gbafe.extract.hexUpper
import gbafe.text.MessageCodec
import gbafe.text.MsgHuffman

/**
 * Extracts every `gMsgTable` string as tagged text (`text/<lang>/messages.json`, format
 * `gbafe.text`).
 *
 * The message table (4-byte ROM pointers to Huffman bit streams) and the Huffman tree are laid
 * out the same in all GBA Fire Emblem games; callers pass the game's addresses. Ids are `MSG_%03X`
 * like the decomp's `texts/texts.txt` headers.
 */
public object MessagesExtractor {
    /** Decodes [count] messages of the table at ROM offset [tableOffset] into tagged strings. */
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
            require(messagePtr ushr 25 == 4) {
                "message $id pointer 0x${messagePtr.toUInt().toString(16)} is not in ROM"
            }
            codec.decode(huffman.decode(messagePtr - 0x08000000))
        }
    }

    /** Message id as written in the decomp (`MSG_000`, `MSG_212`, ...). */
    public fun id(index: Int): String = "MSG_${hexUpper(index, 3)}"

    /** The `gbafe.text` document for [messages] of [game]. */
    public fun toJson(game: String, messages: List<String>): String = Json.write(
        linkedMapOf(
            "format" to "gbafe.text", "game" to game, "version" to 1,
            "entries" to messages.mapIndexed { index, text ->
                linkedMapOf("id" to id(index), "text" to text)
            },
        ),
    )

    /**
     * Parses `texts/textdefs.txt` content [textdefs] for face names, lines
     * `[FID_Name] = 0xLO, 0xHI`; the value is `lo | hi << 8` as stored after `LoadFace`.
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
