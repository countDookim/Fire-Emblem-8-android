package gbafe.text

/**
 * The game's message-string lookup (`src/msg.c`): `gMsgTable` holds one pointer per message id to a
 * Huffman-compressed string, and a string is decoded into one buffer shared by every caller
 * (`sMsgString.buffer1`), so a returned string stays valid only until the next different lookup.
 * The decomp's `buffer1` is 0x555 bytes; this port allocates [BUFFER_SIZE] (the reason for the
 * larger size is not recorded).
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

    /**
     * Shared body of both lookups: `CallARM_DecompText(gMsgTable[index], dst)` followed by
     * `SetMsgTerminator(dst)`.
     */
    private fun decodeInto(index: Int, dst: ByteArray, dstOffset: Int) {
        val p = tableOffset + index * 4
        val ptr = (rom[p].toInt() and 0xFF) or ((rom[p + 1].toInt() and 0xFF) shl 8) or
            ((rom[p + 2].toInt() and 0xFF) shl 16) or ((rom[p + 3].toInt() and 0xFF) shl 24)
        huffman.decodeInto(ptr - ROM_BASE, dst, dstOffset)
        setMsgTerminator(dst, dstOffset)
    }

    /** Buffer size constant and the terminator fix-up applied to every decoded message. */
    public companion object {
        /** Size of [buffer]; the decomp's `sizeof(sMsgString.buffer1)` is 0x555, not this value. */
        public const val BUFFER_SIZE: Int = 0x1000

        /** Start of the cartridge ROM in the GBA address space, subtracted from table pointers. */
        private const val ROM_BASE = 0x08000000

        /** `CHFE_L_LoadFace` (`include/scene.h`): followed by a 2-byte face id. */
        private const val CHFE_L_LOAD_FACE = 0x10

        /**
         * `'\x80'`, the lead byte of every two-byte control code (`0x80 xx`, e.g. `[Wink]`). The
         * decomp's `SetMsgTerminator` labels it `[HalfCloseEyes]` in its comments, but that code is
         * `0x80 0x1E`; the function only tests the lead byte.
         */
        private const val EXTENDED_CODE_LEAD = 0x80

        /** `CHFE_L_DEnd` (0x1F, the `[.]` code): a trailing run of these ends the message. */
        private const val DOT = 0x1F

        /**
         * Turns trailing `[.]` (`CHFE_L_DEnd`, 0x1F) codes of the 0-terminated string at
         * [str]`[start]` into terminators, except one that directly follows a 0x80 byte (see
         * `EXTENDED_CODE_LEAD`). The first loop only finds the end of the string, skipping the
         * argument bytes of `LoadFace` (2) and of the 0x80 codes (1) so an argument is never read
         * as a code.
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
                if (ch == EXTENDED_CODE_LEAD) off += 1
                off++
            }
            off--
            while (off >= 0) {
                if ((str[start + off].toInt() and 0xFF) != DOT) return
                val prev = if (off > 0) str[start + off - 1].toInt() and 0xFF else 0
                if (prev != EXTENDED_CODE_LEAD) str[start + off] = 0
                off--
            }
        }
    }
}
