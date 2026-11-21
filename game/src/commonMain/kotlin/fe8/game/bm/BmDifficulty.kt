/**
 * Dungeon state and record save helpers (`SaveDungeonState`, `SaveDungeonRecords`) of
 * `src/bmdifficulty.c`.
 *
 * Operates on the raw `gDungeonState` (`struct Struct030017A0`, 0x25 bytes: `current` at 0x00,
 * `dungeon[2]` at 0x0C, and `type` at 0x24) and `struct Dungeon[2]` buffers used by suspend and
 * game saves.
 */

package fe8.game.bm

/**
 * Dungeon record and state serialization helpers of `src/bmdifficulty.c`.
 */
public object BmDifficulty {
    /** Byte size of one `struct Dungeon` (`sizeof(struct Dungeon)` = 0x0C). */
    public const val DUNGEON_SIZE: Int = 0x0C

    /** Byte offset of `gDungeonState.dungeon[2]` in `struct Struct030017A0`. */
    public const val DUNGEON_RECORDS_OFFSET: Int = 0x0C

    /** Byte size of `gDungeonState.dungeon[2]` (`2 * sizeof(struct Dungeon)` = 0x18). */
    public const val DUNGEON_RECORDS_SIZE: Int = 2 * DUNGEON_SIZE

    /** Byte offset of `unk_0B_0` / `unk_0B_4` inside `struct Dungeon`. */
    public const val DUNGEON_UNK_0B_OFFSET: Int = 0x0B

    /** Byte offset of `gDungeonState.type` (tower = 0, ruins = 1) in `struct Struct030017A0`. */
    public const val DUNGEON_TYPE_OFFSET: Int = 0x24

    /** Total byte size of `gDungeonState` (`struct Struct030017A0` = 0x25). */
    public const val DUNGEON_STATE_SIZE: Int = 0x25

    /**
     * Transcribed from src/bmdifficulty.c: SaveDungeonState (not verified: `gDungeonState` is all
     * zeros in every reference capture, which proves the 12-byte target write into SRAM /
     * `gGenericBuffer`, not non-zero dungeon state or the post-copy `unk_0B_4 = type` update)
     *
     * Copies `gDungeonState.current` (first 0x0C bytes of [dungeonState]) into [savedDungeon] at
     * [destOffset], then sets `current.unk_0B_4` (high nibble of byte 0x0B) in [dungeonState] to
     * `gDungeonState.type` (byte 0x24). Like the C code, the copy precedes the nibble update, so
     * [savedDungeon] receives the previous `unk_0B_4` and only the live [dungeonState] receives the
     * current `type`.
     */
    public fun saveDungeonState(
        dungeonState: ByteArray,
        savedDungeon: ByteArray,
        destOffset: Int = 0,
    ) {
        dungeonState.copyInto(savedDungeon, destOffset, 0, DUNGEON_SIZE)
        val unk0b = StructCodec.u8(dungeonState, DUNGEON_UNK_0B_OFFSET)
        val type = StructCodec.u8(dungeonState, DUNGEON_TYPE_OFFSET)
        dungeonState[DUNGEON_UNK_0B_OFFSET] =
            ((unk0b and 0x0F) or ((type and 0x0F) shl 4)).toByte()
    }

    /**
     * Transcribed from src/bmdifficulty.c: SaveDungeonRecords (not verified:
     * `gDungeonState.dungeon` is all zeros in every reference capture, which proves the 24-byte
     * target write into SRAM / `gGenericBuffer`, not non-zero dungeon records)
     *
     * Copies the two `struct Dungeon` records (0x18 bytes) starting at [srcOffset] in
     * [dungeonRecords] into [savedDungeon] at [destOffset].
     */
    public fun saveDungeonRecords(
        dungeonRecords: ByteArray,
        srcOffset: Int,
        savedDungeon: ByteArray,
        destOffset: Int = 0,
    ) {
        dungeonRecords.copyInto(
            savedDungeon, destOffset, srcOffset, srcOffset + DUNGEON_RECORDS_SIZE,
        )
    }
}
