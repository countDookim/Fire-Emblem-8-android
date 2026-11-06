package fe8.game.save

/**
 * Layout of `struct SuspendSaveBlock` (include/bmsave.h, include/sram-layout.h
 * `suspend_save_memory_map`): the offsets are relative to the start of a suspend block
 * ([Fe8SramLayout.SUSPEND0_OFFSET] or [Fe8SramLayout.SUSPEND1_OFFSET]), 0x1F78 bytes in all.
 */
public object Fe8SuspendLayout {
    /** `UNIT_SAVE_AMOUNT_BLUE`: blue units saved. */
    public const val UNIT_SAVE_AMOUNT_BLUE: Int = 51

    /** `UNIT_SAVE_AMOUNT_RED`: red units saved. */
    public const val UNIT_SAVE_AMOUNT_RED: Int = 50

    /** `UNIT_SAVE_AMOUNT_GREEN`: green units saved. */
    public const val UNIT_SAVE_AMOUNT_GREEN: Int = 10

    /** `sizeof(struct SuspendSavePackedUnit)`. */
    public const val PACKED_UNIT_SIZE: Int = 0x34

    /** `sizeof(struct ActionData)`. */
    public const val ACTION_SIZE: Int = 0x38

    /** `TRAP_MAX_COUNT`. */
    public const val TRAP_MAX_COUNT: Int = 64

    /** `sizeof(struct Trap)`. */
    public const val TRAP_SIZE: Int = 8

    /** `MENU_OVERRIDE_MAX`. */
    public const val MENU_OVERRIDE_MAX: Int = 0x10

    /**
     * `sizeof(struct GMapSaveInfo)`, 0x24: `WriteWorldMapStuff` (0x080A70B0) reserves 0x24 bytes
     * of stack and passes 0x24 to `WriteAndVerifySramFast`. The one definition for every save
     * block that embeds the struct.
     */
    public const val GMAP_SAVE_INFO_SIZE: Int = 0x24

    /** `sizeof(struct Dungeon)`. */
    public const val DUNGEON_SIZE: Int = 0x0C

    /** `sizeof(struct Struct030017A0)` (`gDungeonState`: current, dungeon[2], type). */
    public const val DUNGEON_STATE_SIZE: Int = 0x25

    /** Offset of `playSt`. */
    public const val PLAY_ST: Int = 0x00

    /** Offset of `action` (`struct ActionData`). */
    public const val ACTION: Int = 0x4C

    /** Offset of `blueUnits`. */
    public const val BLUE_UNITS: Int = ACTION + ACTION_SIZE

    /** Offset of `wmMonsterUnit`: `struct Dungeon dungeon[2]` then 0x1C unused bytes. */
    public const val WM_MONSTER_UNIT: Int = BLUE_UNITS + UNIT_SAVE_AMOUNT_BLUE * PACKED_UNIT_SIZE

    /** Offset of `redUnits`, directly followed by `greenUnits`. */
    public const val RED_UNITS: Int = WM_MONSTER_UNIT + PACKED_UNIT_SIZE

    /** Offset of `greenUnits`. */
    public const val GREEN_UNITS: Int = RED_UNITS + UNIT_SAVE_AMOUNT_RED * PACKED_UNIT_SIZE

    /** Offset of `traps`. */
    public const val TRAPS: Int = GREEN_UNITS + UNIT_SAVE_AMOUNT_GREEN * PACKED_UNIT_SIZE

    /** Offset of `supplyItems`. */
    public const val SUPPLY: Int = TRAPS + TRAP_MAX_COUNT * TRAP_SIZE

    /** Offset of `pidStats`. */
    public const val PID_STATS: Int = SUPPLY + 0xB0

    /** Offset of `chapterStats`. */
    public const val CHAPTER_STATS: Int = PID_STATS + 0x46 * 0x10

    /** Offset of `menuOverride`. */
    public const val MENU_OVERRIDE: Int = CHAPTER_STATS + 0x30 * 4

    /** Offset of `permanentFlags`. */
    public const val PERMANENT_FLAGS: Int = MENU_OVERRIDE + MENU_OVERRIDE_MAX

    /** Offset of `chapterFlags` (7 bytes, 5 of them written). */
    public const val CHAPTER_FLAGS: Int = PERMANENT_FLAGS + 0x19

    /** Offset of `wmStuff` (`struct GMapSaveInfo`). */
    public const val WM_STUFF: Int = CHAPTER_FLAGS + 7

    /** Offset of `dungeon` (`struct Dungeon`), directly after `wmStuff` (already 4-aligned). */
    public const val DUNGEON: Int = WM_STUFF + GMAP_SAVE_INFO_SIZE

    /** Offset of `eventSlotCnt`. */
    public const val EVENT_SLOT_CNT: Int = DUNGEON + DUNGEON_SIZE

    /** Size of the block (`SUSPENDSAVE_OFFSET_MAX`). */
    public const val SIZE: Int = EVENT_SLOT_CNT + 4
}
