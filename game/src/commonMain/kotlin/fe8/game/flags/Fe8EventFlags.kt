package fe8.game.flags

import gbafe.core.flags.EventFlagLayout
import gbafe.core.flags.EventFlags
import gbafe.extract.fe8.events.Fe8ScriptConstants

/**
 * FE8U event-flag memory layout and helpers: maps campaign-wide permanent flags
 * (`gPermanentFlagBits`) and chapter-local flags (`gChapterFlagBits`) in `src/eventinfo.c`,
 * along with the `EVFLAG_*` event flag identifier lookup table and C accessors.
 */
public object Fe8EventFlags {
    /**
     * Size in bytes of the permanent flag array written to save games and reset on starting a new
     * game (`GetPermanentFlagBitsSize()`, 0x19 = 25 bytes, covering flags 100..299).
     */
    public const val PERMANENT_SAVE_BYTES: Int = 0x19

    /**
     * Size in bytes of the chapter-local flag array reset at chapter start and preserved in chapter
     * suspend data (`GetChapterFlagBitsSize()`, 5 bytes, covering flags 0..39).
     */
    public const val CHAPTER_SAVE_BYTES: Int = 5

    /**
     * Allocated size in bytes of `gPermanentFlagBits` in IWRAM (0x03005250, 32 bytes = 256 flags).
     */
    public const val PERMANENT_STORAGE_BYTES: Int = 32

    /**
     * Allocated size in bytes of `gChapterFlagBits` in IWRAM (0x03005270, 16 bytes = 128 flags).
     */
    public const val CHAPTER_STORAGE_BYTES: Int = 16

    /**
     * Layout configuration of FE8U's event flag memory; `bitMask` matches `gFlagBitMaskLut`
     * (ROM 0x089E857C).
     */
    public val layout: EventFlagLayout = EventFlagLayout(
        permanentSaveBytes = PERMANENT_SAVE_BYTES,
        chapterSaveBytes = CHAPTER_SAVE_BYTES,
        permanentStorageBytes = PERMANENT_STORAGE_BYTES,
        chapterStorageBytes = CHAPTER_STORAGE_BYTES,
        chapterFlagLimit = 100,
        permanentBaseId = 100,
        bitMask = byteArrayOf(1, 2, 4, 8, 16, 32, 64, -128, 0, 0, 0, 0, 0),
    )

    /** Creates a fresh event-flag memory instance with all permanent and chapter flags cleared. */
    public fun create(): EventFlags = EventFlags(layout)

    /**
     * Returns the symbolic `EVFLAG_*` constant name of flag [id], or null if not named in decomp.
     */
    public fun nameOf(id: Int): String? =
        Fe8ScriptConstants.nameOf(Fe8ScriptConstants.KIND_EVENT_FLAG, id)

    /**
     * Returns the integer flag id for symbolic `EVFLAG_*` constant [name], or
     * `ScriptConstants.NONE` if unknown.
     */
    public fun idOf(name: String): Int =
        Fe8ScriptConstants.valueOf(Fe8ScriptConstants.KIND_EVENT_FLAG, name)

    /**
     * Transcribed from src/eventinfo.c: GetPermanentFlagBits (not verified: no test checks it
     *   against a trace yet)
     *
     * Returns a 32-byte snapshot copy of `gPermanentFlagBits`. The original C function returns a
     * pointer to the IWRAM byte array directly.
     */
    public fun getPermanentFlagBits(flags: EventFlags): ByteArray =
        ByteArray(PERMANENT_STORAGE_BYTES) { flags.byteAt(it).toByte() }

    /**
     * Ported from src/eventinfo.c: GetPermanentFlagBitsSize
     *
     * Returns the number of bytes from the permanent flag array that are written to save files
     * and cleared when starting a new game (25 bytes).
     */
    public fun getPermanentFlagBitsSize(): Int = PERMANENT_SAVE_BYTES

    /**
     * Transcribed from src/eventinfo.c: GetChapterFlagBits (not verified: no test checks it against
     *   a trace yet)
     *
     * Returns a 16-byte snapshot copy of `gChapterFlagBits`. The original C function returns a
     * pointer to the IWRAM byte array directly.
     */
    public fun getChapterFlagBits(flags: EventFlags): ByteArray =
        ByteArray(CHAPTER_STORAGE_BYTES) { flags.byteAt(PERMANENT_STORAGE_BYTES + it).toByte() }

    /**
     * Ported from src/eventinfo.c: GetChapterFlagBitsSize
     *
     * Returns the number of bytes from the chapter flag array that are cleared when entering
     * a new chapter or saved during a chapter suspend (5 bytes).
     */
    public fun getChapterFlagBitsSize(): Int = CHAPTER_SAVE_BYTES
}
