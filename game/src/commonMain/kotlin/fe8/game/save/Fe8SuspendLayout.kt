/**
 * Byte layout of one mid-chapter suspend save block (`struct SuspendSaveBlock`).
 *
 * Field offsets, element counts and sizes of the 0x1F78-byte block (`SUSPENDSAVE_OFFSET_MAX`),
 * of which the SRAM holds two. Offsets are relative to the start of a block
 * ([Fe8SramLayout.SUSPEND0_OFFSET] or [Fe8SramLayout.SUSPEND1_OFFSET]). From
 * `include/bmsave.h` and `include/sram-layout.h`.
 */

package fe8.game.save

/**
 * Layout of `struct SuspendSaveBlock` (include/bmsave.h; the offsets are the
 * `suspend_save_memory_map` enum of include/sram-layout.h).
 *
 * All offsets are byte distances from the start of the block; the sizes add up to 0x1F78.
 * `sizeof(struct GMapSaveInfo)` and `sizeof(struct Dungeon)` are not written in the headers: 0x24
 * and 0xC are what makes this total (and `SaveBlocks` offset 0x3FC4 for the game slots) work out,
 * and 0xC matches the `Struct030017A0` offsets 0x0C / 0x24.
 */
public object Fe8SuspendLayout {
    /** Number of player (blue) units serialized in a suspend save (`UNIT_SAVE_AMOUNT_BLUE`). */
    public const val UNIT_SAVE_AMOUNT_BLUE: Int = 51

    /** Number of enemy (red) units serialized in a suspend save (`UNIT_SAVE_AMOUNT_RED`). */
    public const val UNIT_SAVE_AMOUNT_RED: Int = 50

    /** Number of NPC / allied (green) units serialized in a suspend save (`UNIT_SAVE_AMOUNT_GREEN`). */
    public const val UNIT_SAVE_AMOUNT_GREEN: Int = 10

    /** Byte size of a single packed suspend unit (`sizeof(struct SuspendSavePackedUnit)` = 0x34). */
    public const val PACKED_UNIT_SIZE: Int = 0x34

    /** Byte size of `gActionData`, the pending unit action (`sizeof(struct ActionData)` = 0x38). */
    public const val ACTION_SIZE: Int = 0x38

    /** Number of map trap slots (`TRAP_MAX_COUNT` = 64): fire tiles, gas / arrow traps etc. */
    public const val TRAP_MAX_COUNT: Int = 64

    /** Byte size of an individual map trap structure (`sizeof(struct Trap)` = 8). */
    public const val TRAP_SIZE: Int = 8

    /** Entries in the forced-disabled menu command list (`MENU_OVERRIDE_MAX` = 0x10). */
    public const val MENU_OVERRIDE_MAX: Int = 0x10

    /**
     * Byte size of the world map save state (`sizeof(struct GMapSaveInfo)`): nodes[8], paths[4],
     * units[7] halfwords, cursor x / y, flag bits, skirmishes[3] and skirmish state. The fields
     * end at 0x20; the size 0x24 is derived from the 0x1F78 block size, not stated in a header.
     */
    public const val GMAP_SAVE_INFO_SIZE: Int = 0x24

    /**
     * Byte size of one `struct Dungeon` (bit-packed record of exp earned, units used, turn count,
     * map time, enemies defeated and clear counts; 0x0C, see [DUNGEON_STATE_SIZE]).
     */
    public const val DUNGEON_SIZE: Int = 0x0C

    /**
     * Byte size of `gDungeonState` (`struct Struct030017A0` = 0x25: `current`, `dungeon[2]`,
     * `type`). Only `current` is saved into the `dungeon` field and `dungeon[2]` into
     * `wmMonsterUnit`; `type` rides in `current.unk_0B_4`.
     */
    public const val DUNGEON_STATE_SIZE: Int = 0x25

    /** Offset of `playSt` (`gPlaySt`, 0x4C bytes). */
    public const val PLAY_ST: Int = 0x00

    /** Offset of `action` (`gActionData`; also carries the RN state in its first 6 bytes). */
    public const val ACTION: Int = 0x4C

    /** Relative offset of blue unit array (`struct SuspendSavePackedUnit blueUnits[51]`). */
    public const val BLUE_UNITS: Int = ACTION + ACTION_SIZE

    /**
     * Relative offset of `wmMonsterUnit`, a spare packed-unit slot (0x34 bytes) that the suspend
     * save fills with `gDungeonState.dungeon[2]` (0x18 bytes) and 0x1C bytes of filler (the C
     * header's TODO suggests `struct Dungeon dungeons[2]; u8 filler[0x1C]`).
     */
    public const val WM_MONSTER_UNIT: Int = BLUE_UNITS + UNIT_SAVE_AMOUNT_BLUE * PACKED_UNIT_SIZE

    /** Relative offset of red unit array (`struct SuspendSavePackedUnit redUnits[50]`). */
    public const val RED_UNITS: Int = WM_MONSTER_UNIT + PACKED_UNIT_SIZE

    /** Relative offset of green unit array (`struct SuspendSavePackedUnit greenUnits[10]`). */
    public const val GREEN_UNITS: Int = RED_UNITS + UNIT_SAVE_AMOUNT_RED * PACKED_UNIT_SIZE

    /** Relative offset of map traps array (`struct Trap traps[64]`). */
    public const val TRAPS: Int = GREEN_UNITS + UNIT_SAVE_AMOUNT_GREEN * PACKED_UNIT_SIZE

    /** Relative offset of the convoy (`supplyItems`, 0xB0 bytes, packed by `WriteSupplyItems`). */
    public const val SUPPLY: Int = TRAPS + TRAP_MAX_COUNT * TRAP_SIZE

    /**
     * Relative offset of `pidStats` (`gPidStatsData`, `BWL_ARRAY_NUM` = 0x46 entries of 0x10 bytes
     * each): per-character battle / win / loss, death and usage counters.
     */
    public const val PID_STATS: Int = SUPPLY + 0xB0

    /**
     * Relative offset of `chapterStats` (`gChapterStats`, `WIN_ARRAY_NUM` = 0x30 entries of 4
     * bytes): per chapter clear record (chapter index 7 bits, turn count 9 bits, clear time 16
     * bits).
     */
    public const val CHAPTER_STATS: Int = PID_STATS + 0x46 * 0x10

    /** Relative offset of `menuOverride`: 0x10 forced-disabled menu command ids. */
    public const val MENU_OVERRIDE: Int = CHAPTER_STATS + 0x30 * 4

    /** Relative offset of `permanentFlags`: the first 0x19 bytes of `gPermanentFlagBits`. */
    public const val PERMANENT_FLAGS: Int = MENU_OVERRIDE + MENU_OVERRIDE_MAX

    /**
     * Relative offset of `chapterFlags`: the first 5 bytes of `gChapterFlagBits` plus 2 bytes of
     * padding (`SUSPENDSAVE_SIZE_CHAPTERFLAGS` = 0x05 + 2).
     */
    public const val CHAPTER_FLAGS: Int = PERMANENT_FLAGS + 0x19

    /** Relative offset of `wmStuff`, the world map state (`struct GMapSaveInfo`, 0x24 bytes). */
    public const val WM_STUFF: Int = CHAPTER_FLAGS + 7

    /** Relative offset of `dungeon`, `gDungeonState.current` (one `struct Dungeon`, 0xC bytes). */
    public const val DUNGEON: Int = WM_STUFF + GMAP_SAVE_INFO_SIZE

    /** Relative offset of `eventSlotCnt`, the event engine's slot counter (an int, 4 bytes). */
    public const val EVENT_SLOT_CNT: Int = DUNGEON + DUNGEON_SIZE

    /** Total size of one suspend save block (`SUSPENDSAVE_OFFSET_MAX` = 0x1F78 = 8056 bytes). */
    public const val SIZE: Int = EVENT_SLOT_CNT + 4
}
