package gbafe.core.flags

/**
 * Shape of the original's event-flag memory (`gPermanentFlagBits` and `gChapterFlagBits`,
 * src/eventinfo.c). Game-neutral; the FE8U values live in `fe8.game.flags.Fe8EventFlags`.
 *
 * The two arrays are adjacent in IWRAM (0x03005250 and 0x03005270 in FE8U) and each symbol is
 * larger than the range the game saves or resets: the permanent array is 32 bytes but only 0x19 are
 * saved and reset; the chapter array is 16 bytes but only 5 are. Flags outside the saved range
 * still address real bytes in C, so [EventFlags] mirrors the whole storage.
 *
 * @property permanentSaveBytes `GetPermanentFlagBitsSize()`; also the bytes `ResetPermanentFlags`
 *     clears.
 * @property chapterSaveBytes `GetChapterFlagBitsSize()`; also the bytes `ResetChapterFlags` clears.
 * @property permanentStorageBytes allocated size of `gPermanentFlagBits`; the chapter array follows
 *     it.
 * @property chapterStorageBytes allocated size of `gChapterFlagBits`.
 * @property chapterFlagLimit flags strictly below this are chapter flags in
 *     `SetFlag`/`ClearFlag`/`CheckFlag`.
 * @property permanentBaseId permanent flag ids are `permanentBaseId + 1 + bit`; ids up to it are
 *     no-ops.
 * @property bitMask `gFlagBitMaskLut`: the mask for `flag % 8` (first 8 entries are the single
 *     bits).
 */
public class EventFlagLayout(
    public val permanentSaveBytes: Int,
    public val chapterSaveBytes: Int,
    public val permanentStorageBytes: Int,
    public val chapterStorageBytes: Int,
    public val chapterFlagLimit: Int,
    public val permanentBaseId: Int,
    public val bitMask: ByteArray,
) {
    init {
        require(permanentSaveBytes in 0..permanentStorageBytes) { "permanentSaveBytes" }
        require(chapterSaveBytes in 0..chapterStorageBytes) { "chapterSaveBytes" }
        require(bitMask.size >= 8) { "bitMask needs at least 8 entries" }
    }

    /** Total mirrored bytes: the permanent array followed by the chapter array. */
    public val totalBytes: Int get() = permanentStorageBytes + chapterStorageBytes
}

/**
 * Event flags: 1..99 are chapter flags (cleared each chapter), above 100 are permanent flags that
 * persist in the save. Storage is one byte array laid out exactly like the original (permanent
 * array first, chapter array right after) so saves can copy raw bytes.
 *
 * Quirks kept from C: flag 0 and flag 100 are no-ops and read false; chapter flag `n` is bit `n -
 * 1`, permanent flag `n` is bit `n - 101`; flags beyond the saved range (chapter 41..99, permanent
 * above 300) still land in real bytes that the reset functions do not clear, and a permanent bit
 * past the permanent array lands in the chapter array just as in memory. Ids that would address
 * bytes outside the mirror (C would corrupt unrelated memory) and negative chapter ids throw
 * [IllegalArgumentException].
 *
 * @property layout sizes and constants of the flag memory.
 */
public class EventFlags(public val layout: EventFlagLayout) : FlagStore {
    private val bits = ByteArray(layout.totalBytes)
    private val chapterBase = layout.permanentStorageBytes
    private val masks = layout.bitMask

    /** Number of mirrored bytes (permanent array then chapter array). */
    public val size: Int get() = bits.size

    /**
     * Byte [index] of the mirror as 0..255; the chapter array starts at
     * `layout.permanentStorageBytes`.
     */
    public fun byteAt(index: Int): Int = bits[index].toInt() and 0xFF

    /** Copies the whole mirror to [dest] starting at [destOffset]. */
    public fun copyOut(dest: ByteArray, destOffset: Int = 0) {
        bits.copyInto(dest, destOffset, 0, bits.size)
    }

    /** Overwrites the whole mirror from [src] starting at [srcOffset]. */
    public fun copyIn(src: ByteArray, srcOffset: Int = 0) {
        src.copyInto(bits, 0, srcOffset, srcOffset + bits.size)
    }

    /** Copies the saved part of the permanent flags (`permanentSaveBytes`) to [dest]. */
    public fun copyOutPermanent(dest: ByteArray, destOffset: Int = 0) {
        bits.copyInto(dest, destOffset, 0, layout.permanentSaveBytes)
    }

    /** Overwrites the saved part of the permanent flags from [src]. */
    public fun copyInPermanent(src: ByteArray, srcOffset: Int = 0) {
        src.copyInto(bits, 0, srcOffset, srcOffset + layout.permanentSaveBytes)
    }

    /** Copies the saved part of the chapter flags (`chapterSaveBytes`) to [dest]. */
    public fun copyOutChapter(dest: ByteArray, destOffset: Int = 0) {
        bits.copyInto(dest, destOffset, chapterBase, chapterBase + layout.chapterSaveBytes)
    }

    /** Overwrites the saved part of the chapter flags from [src]. */
    public fun copyInChapter(src: ByteArray, srcOffset: Int = 0) {
        src.copyInto(bits, chapterBase, srcOffset, srcOffset + layout.chapterSaveBytes)
    }

    /**
     * Ported from src/eventinfo.c: SetChapterFlag
     *
     * Sets chapter flag [flag]; flag 0 does nothing.
     */
    public fun setChapterFlag(flag: Int) {
        if (flag == 0) return
        val bit = chapterBit(flag)
        val i = chapterBase + (bit shr 3)
        bits[i] = (bits[i].toInt() or masks[bit and 7].toInt()).toByte()
    }

    /**
     * Ported from src/eventinfo.c: ClearChapterFlag
     *
     * Clears chapter flag [flag]; flag 0 does nothing.
     */
    public fun clearChapterFlag(flag: Int) {
        if (flag == 0) return
        val bit = chapterBit(flag)
        val i = chapterBase + (bit shr 3)
        bits[i] = (bits[i].toInt() and masks[bit and 7].toInt().inv()).toByte()
    }

    /**
     * Ported from src/eventinfo.c: ResetChapterFlags
     *
     * Clears the first `chapterSaveBytes` (5) chapter bytes; flags 41..99 live beyond them and
     * survive.
     */
    public fun resetChapterFlags() {
        bits.fill(0, chapterBase, chapterBase + layout.chapterSaveBytes)
    }

    /**
     * Ported from src/eventinfo.c: CheckChapterFlag
     *
     * True if chapter flag [flag] is set; flag 0 is always false.
     */
    public fun checkChapterFlag(flag: Int): Boolean {
        if (flag == 0) return false
        val bit = chapterBit(flag)
        return (bits[chapterBase + (bit shr 3)].toInt() and masks[bit and 7].toInt()) != 0
    }

    /**
     * Ported from src/eventinfo.c: SetPermanentFlag
     *
     * Sets permanent flag [flag]; ids up to and including `permanentBaseId` (including negatives)
     * do nothing.
     */
    public fun setPermanentFlag(flag: Int) {
        if (flag <= layout.permanentBaseId) return
        val bit = permanentBit(flag)
        val i = bit shr 3
        bits[i] = (bits[i].toInt() or masks[bit and 7].toInt()).toByte()
    }

    /**
     * Ported from src/eventinfo.c: ClearPermanentFlag
     *
     * Clears permanent flag [flag]; ids up to and including `permanentBaseId` (including negatives)
     * do nothing.
     */
    public fun clearPermanentFlag(flag: Int) {
        if (flag <= layout.permanentBaseId) return
        val bit = permanentBit(flag)
        val i = bit shr 3
        bits[i] = (bits[i].toInt() and masks[bit and 7].toInt().inv()).toByte()
    }

    /**
     * Ported from src/eventinfo.c: ResetPermanentFlags
     *
     * Clears the first `permanentSaveBytes` (0x19) permanent bytes.
     */
    public fun resetPermanentFlags() {
        bits.fill(0, 0, layout.permanentSaveBytes)
    }

    /**
     * Ported from src/eventinfo.c: CheckPermanentFlagFrom
     *
     * True if permanent flag [flag] is set in [list] starting at [offset] (used on save-file
     * buffers); ids up to and including `permanentBaseId` are always false. Throws if the bit lies
     * outside [list].
     */
    public fun checkPermanentFlagFrom(flag: Int, list: ByteArray, offset: Int = 0): Boolean {
        if (flag <= layout.permanentBaseId) return false
        val bit = flag - layout.permanentBaseId - 1
        val i = offset + (bit shr 3)
        require(i < list.size) { "permanent flag $flag outside the buffer" }
        return (list[i].toInt() and masks[bit and 7].toInt()) != 0
    }

    /**
     * Ported from src/eventinfo.c: CheckPermanentFlag
     *
     * True if permanent flag [flag] is set in this instance.
     */
    public fun checkPermanentFlag(flag: Int): Boolean = checkPermanentFlagFrom(flag, bits)

    /**
     * Ported from src/eventinfo.c: SetFlag
     *
     * Sets [flag] as a chapter flag below `chapterFlagLimit`, else as a permanent flag.
     */
    override fun setFlag(flag: Int) {
        if (flag < layout.chapterFlagLimit) setChapterFlag(flag) else setPermanentFlag(flag)
    }

    /**
     * Ported from src/eventinfo.c: ClearFlag
     *
     * Clears [flag] as a chapter flag below `chapterFlagLimit`, else as a permanent flag.
     */
    override fun clearFlag(flag: Int) {
        if (flag < layout.chapterFlagLimit) clearChapterFlag(flag) else clearPermanentFlag(flag)
    }

    /**
     * Ported from src/eventinfo.c: CheckFlag
     *
     * True if [flag] is set (chapter flag below `chapterFlagLimit`, else permanent).
     */
    override fun checkFlag(flag: Int): Boolean =
        if (flag < layout.chapterFlagLimit) checkChapterFlag(flag) else checkPermanentFlag(flag)

    private fun chapterBit(flag: Int): Int {
        val bit = flag - 1
        require(bit >= 0 && (bit shr 3) < layout.chapterStorageBytes) {
            "chapter flag $flag outside the flag memory"
        }
        return bit
    }

    private fun permanentBit(flag: Int): Int {
        val bit = flag - layout.permanentBaseId - 1
        require((bit shr 3) < bits.size) { "permanent flag $flag outside the flag memory" }
        return bit
    }
}
