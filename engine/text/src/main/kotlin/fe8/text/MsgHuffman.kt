package fe8.text

/**
 * Decoder for the original game's Huffman-compressed message strings (`gMsgTable` entries).
 *
 * Load/extract time only: the game will ship decoded text as JSON (`text/<lang>/`), so this exists for the
 * extractor and for verifying it. It returns raw message bytes, control codes included; turning those
 * into readable tags is a separate step (M2-09).
 *
 * Tree layout (`gMsgHuffmanTable`): 4-byte nodes. An internal node holds two `u16` child indices (left
 * for bit 0, right for bit 1) and has bit 31 clear when read as a `u32`; a leaf has bit 31 set and holds
 * one or two output bytes in its low 16 bits. The root node's address is stored in
 * `gMsgHuffmanTableRoot`. Bits are consumed LSB-first within each input byte. Decoding ends after
 * emitting a single-byte leaf whose byte is 0 (the string terminator, which is included in the output).
 */
public class MsgHuffman(
    private val rom: ByteArray,
    /** ROM offset of `gMsgHuffmanTable` (node 0). */
    private val tableOffset: Int,
    /** ROM offset of the root node (the value stored in `gMsgHuffmanTableRoot`, minus 0x08000000). */
    private val rootOffset: Int,
) {
    /**
     * Decodes the string whose compressed bits start at ROM offset [offset] and returns its bytes,
     * including the trailing 0.
     *
     * Ported from asm/arm.s: DecodeString
     *
     * @throws IllegalArgumentException if the bit stream runs past the end of the ROM.
     */
    public fun decode(offset: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var src = offset
        var bits = 0
        var bitsLeft = 0
        while (true) {
            var node = rootOffset
            var value: Int
            do {
                if (bitsLeft == 0) {
                    require(src < rom.size) { "message bits run past the end of the ROM" }
                    bits = rom[src++].toInt() and 0xFF
                    bitsLeft = 8
                }
                val child = u16(node + if (bits and 1 != 0) 2 else 0)
                bits = bits ushr 1
                bitsLeft--
                node = tableOffset + child * 4
                value = u16(node) or (u16(node + 2) shl 16)
            } while (value >= 0)
            if (value and 0xFF00 != 0) {
                out.write(value and 0xFF)
                out.write((value ushr 8) and 0xFF)
            } else {
                out.write(value and 0xFF)
                if (value and 0xFF == 0) return out.toByteArray()
            }
        }
    }

    private fun u16(o: Int) = (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
}
