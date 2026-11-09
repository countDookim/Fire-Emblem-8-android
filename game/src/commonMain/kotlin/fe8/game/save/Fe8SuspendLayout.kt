/**
 * Binary memory layout for mid-chapter suspend save blocks (`struct SuspendSaveBlock`).
 *
 * Defines field offsets, member counts, and struct sizes for the 0x1F78-byte suspend save blocks
 * in FE8 SRAM (`SUSPENDSAVE_OFFSET_MAX`). Offsets are relative to the start of either suspend slot
 * ([Fe8SramLayout.SUSPEND0_OFFSET] or [Fe8SramLayout.SUSPEND1_OFFSET]).
 * Corresponds to declarations in `include/bmsave.h` and `include/sram-layout.h`.
 */

package fe8.game.save

/**
 * Layout of `struct SuspendSaveBlock` (include/bmsave.h, include/sram-layout.h `suspend_save_memory_map`).
 *
 * All offsets represent byte distances from the beginning of the suspend save slot, totaling 0x1F78 bytes.
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

    /** Byte size of the active turn action state (`sizeof(struct ActionData)` = 0x38). */
    public const val ACTION_SIZE: Int = 0x38

    /** Maximum number of simultaneous map traps allocated (`TRAP_MAX_COUNT` = 64). */
    public const val TRAP_MAX_COUNT: Int = 64

    /** Byte size of an individual map trap structure (`sizeof(struct Trap)` = 8). */
    public const val TRAP_SIZE: Int = 8

    /** Maximum entries in the disabled menu command list (`MENU_OVERRIDE_MAX` = 0x10). */
    public const val MENU_OVERRIDE_MAX: Int = 0x10

    /**
     * Byte size of the world map save state (`sizeof(struct GMapSaveInfo)` = 0x24).
     * Allocated on the stack by `WriteWorldMapStuff` (0x080A70B0) and passed to `WriteAndVerifySramFast`.
     */
    public const val GMAP_SAVE_INFO_SIZE: Int = 0x24

    /** Byte size of dungeon records (`sizeof(struct Dungeon)` = 0x0C). */
    public const val DUNGEON_SIZE: Int = 0x0C

    /** Byte size of global dungeon crawl state (`sizeof(struct Struct030017A0)` = 0x25: current, dungeon[2], type). */
    public const val DUNGEON_STATE_SIZE: Int = 0x25

    /** Relative offset of `playSt` (`struct PlaySt`). */
    public const val PLAY_ST: Int = 0x00

    /** Relative offset of `action` (`struct ActionData`). */
    public const val ACTION: Int = 0x4C

    /** Relative offset of blue unit array (`struct SuspendSavePackedUnit blueUnits[51]`). */
    public const val BLUE_UNITS: Int = ACTION + ACTION_SIZE

    /**
     * Relative offset of the world map monster / dungeon records slot (`wmMonsterUnit`).
     * Reuses a 0x34-byte packed unit slot to store `struct Dungeon dungeon[2]` (0x18 bytes) plus padding.
     */
    public const val WM_MONSTER_UNIT: Int = BLUE_UNITS + UNIT_SAVE_AMOUNT_BLUE * PACKED_UNIT_SIZE

    /** Relative offset of red unit array (`struct SuspendSavePackedUnit redUnits[50]`). */
    public const val RED_UNITS: Int = WM_MONSTER_UNIT + PACKED_UNIT_SIZE

    /** Relative offset of green unit array (`struct SuspendSavePackedUnit greenUnits[10]`). */
    public const val GREEN_UNITS: Int = RED_UNITS + UNIT_SAVE_AMOUNT_RED * PACKED_UNIT_SIZE

    /** Relative offset of map traps array (`struct Trap traps[64]`). */
    public const val TRAPS: Int = GREEN_UNITS + UNIT_SAVE_AMOUNT_GREEN * PACKED_UNIT_SIZE

    /** Relative offset of convoy supply items (`supplyItems`, 0xB0 bytes). */
    public const val SUPPLY: Int = TRAPS + TRAP_MAX_COUNT * TRAP_SIZE

    /** Relative offset of player unit usage and kill/loss records (`pidStats`, 0x46 entries × 16 bytes). */
    public const val PID_STATS: Int = SUPPLY + 0xB0

    /** Relative offset of chapter turn counts and clear records (`chapterStats`, 0x30 entries × 4 bytes). */
    public const val CHAPTER_STATS: Int = PID_STATS + 0x46 * 0x10

    /** Relative offset of disabled menu items list (`menuOverride`, 0x10 bytes). */
    public const val MENU_OVERRIDE: Int = CHAPTER_STATS + 0x30 * 4

    /** Relative offset of permanent event flags (`permanentFlags`, 0x19 bytes). */
    public const val PERMANENT_FLAGS: Int = MENU_OVERRIDE + MENU_OVERRIDE_MAX

    /** Relative offset of chapter-temporary event flags (`chapterFlags`, 7 bytes). */
    public const val CHAPTER_FLAGS: Int = PERMANENT_FLAGS + 0x19

    /** Relative offset of world map state (`wmStuff`, 0x24 bytes). */
    public const val WM_STUFF: Int = CHAPTER_FLAGS + 7

    /** Relative offset of current dungeon state (`dungeon`, 0x0C bytes). */
    public const val DUNGEON: Int = WM_STUFF + GMAP_SAVE_INFO_SIZE

    /** Relative offset of event engine slot counter (`eventSlotCnt`, 4 bytes). */
    public const val EVENT_SLOT_CNT: Int = DUNGEON + DUNGEON_SIZE

    /** Total size of one suspend save block (`SUSPENDSAVE_OFFSET_MAX` = 0x1F78 bytes). */
    public const val SIZE: Int = EVENT_SLOT_CNT + 4
}
