package gbafe.text

/**
 * The game's message-string buffer and lookup (`src/msg.c`): `gMsgTable` indexes Huffman-compressed
 * strings that are decoded into a single shared 0x1000-byte buffer (`sMsgString`).
 *
 * Game-neutral: the caller supplies the ROM table location. All state lives in preallocated fields;
 * lookups do not allocate.
 */
public class MsgStrings(
    private val rom: ByteArray,
    private val huffman: MsgHuffman,
    /** ROM offset of `gMsgTable` (an array of 32-bit ROM pointers). */
    private val tableOffset: Int,
) {
    /**
     * The shared decode buffer (`sMsgString.buffer1`); strings returned by [getStringFromIndex]
     * live here.
     */
    public val buffer: ByteArray = ByteArray(BUFFER_SIZE)

    /**
     * Message id currently decoded in [buffer] (`sActiveMsg`; starts at 0 like the zeroed EWRAM).
     */
    public var activeMsg: Int = 0
        private set

    /**
     * Returns the shared [buffer] holding message [index], decoding it only if it is not already
     * cached.
     *
     * Ported from src/msg.c: GetStringFromIndex
     */
    public fun getStringFromIndex(index: Int): ByteArray {
        if (activeMsg != index) {
            val msgPtr = readU32(tableOffset + index * 4)
            require(msgPtr ushr 25 == 4) { "message $index pointer 0x${msgPtr.toUInt().toString(16)} is not in ROM" }
            huffman.decodeInto(msgPtr - ROM_BASE, buffer, 0)
            activeMsg = index
        }
        return buffer
    }

    /**
     * Decodes message [index] directly into [out] without modifying the shared cache [buffer].
     *
     * Transcribed from src/msg.c: GetStringFromIndexInBuffer (not verified: the reference trace
     * never calls this variant, only [getStringFromIndex])
     */
    public fun getStringFromIndexInBuffer(index: Int, out: ByteArray) {
        val msgPtr = readU32(tableOffset + index * 4)
        require(msgPtr ushr 25 == 4) { "message $index pointer 0x${msgPtr.toUInt().toString(16)} is not in ROM" }
        huffman.decodeInto(msgPtr - ROM_BASE, out, 0)
    }

    private fun readU32(o: Int): Int =
        (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8) or
            ((rom[o + 2].toInt() and 0xFF) shl 16) or ((rom[o + 3].toInt() and 0xFF) shl 24)

    /** Buffer size and terminator helpers. */
    public companion object {
        /** Size of the shared decode buffer in bytes (`sizeof(sMsgString.buffer1)` = 0x1000 = 4096). */
        public const val BUFFER_SIZE: Int = 0x1000

        private const val ROM_BASE = 0x08000000
        private const val CHFE_L_LOAD_FACE = 0x80
        private const val HALF_CLOSE_EYES = 0x1E
        private const val DOT = '.'.code

        /**
         * Replaces trailing periods with string terminators (`0`) unless preceded by
         * [HALF_CLOSE_EYES].
         *
         * Ported from src/msg.c: SetMsgTerminator
         */
        public fun setMsgTerminator(str: ByteArray, start: Int = 0) {
            var off = 0
            while (str[start + off].toInt() != 0) {
                val ch = str[start + off].toInt() and 0xFF
                if (ch == CHFE_L_LOAD_FACE) off += 2
                if (ch == HALF_CLOSE_EYES) off += 1
                off++
            }
            off--
            while (off >= 0) {
                if ((str[start + off].toInt() and 0xFF) != DOT) return
                val prev = if (off > 0) str[start + off - 1].toInt() and 0xFF else 0
                if (prev != HALF_CLOSE_EYES) str[start + off] = 0
                off--
            }
        }
    }
}
