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
     * `sMsgString.buffer4` (0x100 bytes): the working copy of [buffer] that [strInsertTact]
     * reads.
     */
    public val buffer4: ByteArray = ByteArray(SPECIAL_BUFFER_SIZE)

    /** `sMsgString.buffer5` (0x100 bytes): the result of [strInsertTact]. */
    public val buffer5: ByteArray = ByteArray(SPECIAL_BUFFER_SIZE)

    /**
     * Copies the 0-terminated string in [buffer] to [buffer4] and from there to [buffer5], with
     * every `[Tact]` code (0x80 0x20) replaced by the string at [tactician]`[tacticianOffset]`
     * (the 0-terminated name, `GetTacticianName`); every other code is copied through. Returns
     * [buffer5]. A string over 0xFF bytes after the replacement overruns the 0x100-byte buffers
     * (the original writes past them into the next one); here that throws. Not seen: the
     * `[Tact]` replacement (item names carry no such code, so a break there survives).
     *
     * Ported from src/msg.c: StrInsertTact
     */
    public fun strInsertTact(tactician: ByteArray, tacticianOffset: Int = 0): ByteArray {
        var src = 0
        var dst = 0
        copyString(buffer4, 0, buffer, 0)
        while (buffer4[src].toInt() != 0) {
            val ch = buffer4[src].toInt() and 0xFF
            if (ch != EXTENDED_CODE_LEAD) {
                buffer5[dst++] = buffer4[src++]
            } else {
                src++
                if (buffer4[src].toInt() != TACT_CODE) {
                    buffer5[dst++] = ch.toByte()
                    buffer5[dst++] = buffer4[src++]
                } else {
                    copyString(buffer5, dst, tactician, tacticianOffset)
                    while (buffer5[dst].toInt() != 0) dst++
                    src++
                }
            }
        }
        buffer5[dst] = 0
        return buffer5
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

        /** Size of [buffer4] and [buffer5] (`sMsgString.buffer4`, `buffer5`). */
        public const val SPECIAL_BUFFER_SIZE: Int = 0x100

        /** Second byte of the `[Tact]` code (0x80 0x20): the tactician's name. */
        private const val TACT_CODE = 0x20

        /**
         * Copies the 0-terminated string at [src]`[srcOffset]` to [dst]`[dstOffset]`, terminator
         * included.
         *
         * Ported from src/bmlib.c: CopyString
         */
        public fun copyString(dst: ByteArray, dstOffset: Int, src: ByteArray, srcOffset: Int) {
            var d = dstOffset
            var s = srcOffset
            while (src[s].toInt() != 0) dst[d++] = src[s++]
            dst[d] = src[s]
        }

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
