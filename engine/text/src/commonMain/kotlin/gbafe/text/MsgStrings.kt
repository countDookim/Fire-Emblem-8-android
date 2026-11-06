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
     * the active message.
     *
     * Ported from src/msg.c: GetStringFromIndex
     */
    public fun getStringFromIndex(index: Int): ByteArray {
        if (index == activeMsg) return buffer
        decodeInto(index, buffer, 0)
        activeMsg = index
        return buffer
    }

    /**
     * Decodes message [index] into [dst] at [dstOffset] (no caching) and returns [dst].
     *
     * Transcribed from src/msg.c: GetStringFromIndexInBuffer (not verified: the reference trace
     * never shows a caller-supplied buffer filled by it)
     */
    // Not @VerifiesPort: no traced call of the InBuffer variant; it shares the verified decode
    // path.
    public fun getStringFromIndexInBuffer(
        index: Int,
        dst: ByteArray,
        dstOffset: Int = 0,
    ): ByteArray {
        decodeInto(index, dst, dstOffset)
        return dst
    }

    private fun decodeInto(index: Int, dst: ByteArray, dstOffset: Int) {
        val p = tableOffset + index * 4
        val ptr = (rom[p].toInt() and 0xFF) or ((rom[p + 1].toInt() and 0xFF) shl 8) or
            ((rom[p + 2].toInt() and 0xFF) shl 16) or ((rom[p + 3].toInt() and 0xFF) shl 24)
        huffman.decodeInto(ptr - ROM_BASE, dst, dstOffset)
        setMsgTerminator(dst, dstOffset)
    }

    /** Buffer size constant and the static terminator fix-up. */
    public companion object {
        /** `sizeof(sMsgString.buffer1)`. */
        public const val BUFFER_SIZE: Int = 0x1000

        private const val ROM_BASE = 0x08000000
        private const val CHFE_L_LOAD_FACE = 0x10
        private const val HALF_CLOSE_EYES = 0x80
        private const val DOT = 0x1F

        /**
         * Turns trailing `[.]` (0x1F) codes of the 0-terminated string at [str]`[start]` into
         * terminators, except one that follows `[HalfCloseEyes]` (0x80).
         *
         * Ported from src/msg.c: SetMsgTerminator
         *
         * The C reads `str[-1]` when the string is a lone `[.]`; here that byte is taken as 0.
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
