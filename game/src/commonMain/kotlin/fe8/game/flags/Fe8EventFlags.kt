package fe8.game.flags

import gbafe.core.flags.EventFlagLayout
import gbafe.core.flags.EventFlags
import gbafe.extract.fe8.events.Fe8ScriptConstants

/**
 * FE8U event-flag memory: the [layout] of `gPermanentFlagBits` / `gChapterFlagBits`
 * (src/eventinfo.c), the `EVFLAG_*` name table and the C accessors for the two arrays.
 */
public object Fe8EventFlags {
    /** `GetPermanentFlagBitsSize()`: saved and reset bytes of the permanent array. */
    public const val PERMANENT_SAVE_BYTES: Int = 0x19

    /** `GetChapterFlagBitsSize()`: reset bytes of the chapter array. */
    public const val CHAPTER_SAVE_BYTES: Int = 5

    /** Allocated size of `gPermanentFlagBits` (IWRAM 0x03005250). */
    public const val PERMANENT_STORAGE_BYTES: Int = 32

    /** Allocated size of `gChapterFlagBits` (IWRAM 0x03005270). */
    public const val CHAPTER_STORAGE_BYTES: Int = 16

    /** Layout of FE8U's flag memory; `bitMask` is `gFlagBitMaskLut` (ROM 0x089E857C). */
    public val layout: EventFlagLayout = EventFlagLayout(
        permanentSaveBytes = PERMANENT_SAVE_BYTES,
        chapterSaveBytes = CHAPTER_SAVE_BYTES,
        permanentStorageBytes = PERMANENT_STORAGE_BYTES,
        chapterStorageBytes = CHAPTER_STORAGE_BYTES,
        chapterFlagLimit = 100,
        permanentBaseId = 100,
        bitMask = byteArrayOf(1, 2, 4, 8, 16, 32, 64, -128, 0, 0, 0, 0, 0),
    )

    /** A fresh, all-clear flag memory with the FE8U [layout]. */
    public fun create(): EventFlags = EventFlags(layout)

    /** The `EVFLAG_*` name of flag [id], or null if the decomp does not name it. */
    public fun nameOf(id: Int): String? =
        Fe8ScriptConstants.nameOf(Fe8ScriptConstants.KIND_EVENT_FLAG, id)

    /** The flag id of an `EVFLAG_*` [name], or `ScriptConstants.NONE` if unknown. */
    public fun idOf(name: String): Int =
        Fe8ScriptConstants.valueOf(Fe8ScriptConstants.KIND_EVENT_FLAG, name)

    /**
     * Transcribed from src/eventinfo.c: GetPermanentFlagBits (not verified: no test checks it
     *   against a trace yet)
     *
     * A copy of `gPermanentFlagBits` (the C function returns a pointer to the 32 byte array).
     */
    public fun getPermanentFlagBits(flags: EventFlags): ByteArray =
        ByteArray(PERMANENT_STORAGE_BYTES) { flags.byteAt(it).toByte() }

    /** Ported from src/eventinfo.c: GetPermanentFlagBitsSize */
    public fun getPermanentFlagBitsSize(): Int = PERMANENT_SAVE_BYTES

    /**
     * Transcribed from src/eventinfo.c: GetChapterFlagBits (not verified: no test checks it against
     *   a trace yet)
     *
     * A copy of `gChapterFlagBits` (the C function returns a pointer to the 16 byte array).
     */
    public fun getChapterFlagBits(flags: EventFlags): ByteArray =
        ByteArray(CHAPTER_STORAGE_BYTES) { flags.byteAt(PERMANENT_STORAGE_BYTES + it).toByte() }

    /** Ported from src/eventinfo.c: GetChapterFlagBitsSize */
    public fun getChapterFlagBitsSize(): Int = CHAPTER_SAVE_BYTES
}
