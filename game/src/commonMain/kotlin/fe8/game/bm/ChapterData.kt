package fe8.game.bm

import fe8.game.RomView

/**
 * Read-only view of the ROM chapter tables: `gChapterDataTable` (`struct ROMChapterData`, one
 * [ENTRY_SIZE]-byte entry per chapter) and `gChapterDataAssetTable` (pointers to map, map change
 * and event data, indexed by the ids stored in the chapter entries).
 *
 * Addresses are GBA addresses (0x08xxxxxx); nothing is allocated per call. The extra map
 * ([EXTRA_MAP_CHAPTER], `0x7F`) lives in SRAM in the original and is not part of the ROM tables:
 * the lookups reject it with [IllegalArgumentException].
 */
public class ChapterData(
    /** The ROM the tables are read from. */
    private val rom: RomView,
) {
    /**
     * Ported from src/chapterdata.c: GetROMChapterStruct
     *
     * Returns the address of the `struct ROMChapterData` of chapter [chIndex].
     */
    public fun getROMChapterStruct(chIndex: Int): Int {
        require(chIndex in 0 until CHAPTER_COUNT) { "not a ROM chapter: $chIndex" }
        return CHAPTER_DATA_TABLE + chIndex * ENTRY_SIZE
    }

    /**
     * Ported from src/chapterdata.c: GetChapterMapPointer
     *
     * Returns the address of the (compressed) map layout of chapter [chIndex]:
     * `gChapterDataAssetTable[mainLayerId]`.
     */
    public fun getChapterMapPointer(chIndex: Int): Int =
        asset(u8(chIndex, OFF_MAP_MAIN_LAYER_ID))

    /**
     * Ported from src/chapterdata.c: GetChapterMapChangesPointer
     *
     * Returns the address of the map change data of chapter [chIndex]:
     * `gChapterDataAssetTable[changeLayerId]`. Not yet covered by a `@VerifiesPort` test.
     */
    public fun getChapterMapChangesPointer(chIndex: Int): Int =
        asset(u8(chIndex, OFF_MAP_CHANGE_LAYER_ID))

    /**
     * Ported from src/chapterdata.c: GetChapterEventDataPointer
     *
     * Returns the address of the `ChapterEventGroup` of chapter [chIndex]:
     * `gChapterDataAssetTable[mapEventDataId]`.
     */
    public fun getChapterEventDataPointer(chIndex: Int): Int =
        asset(u8(chIndex, OFF_MAP_EVENT_DATA_ID))

    /** `map.obj1Id` of chapter [chIndex]: id of the first tileset object graphics. */
    public fun obj1Id(chIndex: Int): Int = u8(chIndex, OFF_MAP_OBJ1_ID)

    /** `map.obj2Id` of chapter [chIndex]: id of the second tileset object graphics. */
    public fun obj2Id(chIndex: Int): Int = u8(chIndex, OFF_MAP_OBJ2_ID)

    /** `map.paletteId` of chapter [chIndex]. */
    public fun paletteId(chIndex: Int): Int = u8(chIndex, OFF_MAP_PALETTE_ID)

    /** `map.tileConfigId` of chapter [chIndex]. */
    public fun tileConfigId(chIndex: Int): Int = u8(chIndex, OFF_MAP_TILE_CONFIG_ID)

    /** `map.mainLayerId` of chapter [chIndex]: asset table index of the map layout. */
    public fun mainLayerId(chIndex: Int): Int = u8(chIndex, OFF_MAP_MAIN_LAYER_ID)

    /** `map.objAnimId` of chapter [chIndex]. */
    public fun objAnimId(chIndex: Int): Int = u8(chIndex, OFF_MAP_OBJ_ANIM_ID)

    /** `map.paletteAnimId` of chapter [chIndex]. */
    public fun paletteAnimId(chIndex: Int): Int = u8(chIndex, OFF_MAP_PALETTE_ANIM_ID)

    /** `map.changeLayerId` of chapter [chIndex]: asset table index of the map change data. */
    public fun changeLayerId(chIndex: Int): Int = u8(chIndex, OFF_MAP_CHANGE_LAYER_ID)

    /** `mapEventDataId` of chapter [chIndex]: asset table index of the event data. */
    public fun mapEventDataId(chIndex: Int): Int = u8(chIndex, OFF_MAP_EVENT_DATA_ID)

    /** `initialPosX` of chapter [chIndex]: initial camera tile X. */
    public fun initialPosX(chIndex: Int): Int = u8(chIndex, OFF_INITIAL_POS_X)

    /** `initialPosY` of chapter [chIndex]: initial camera tile Y. */
    public fun initialPosY(chIndex: Int): Int = u8(chIndex, OFF_INITIAL_POS_Y)

    /** `initialFogLevel` of chapter [chIndex]: the starting vision range (0 = no fog). */
    public fun initialFogLevel(chIndex: Int): Int = u8(chIndex, OFF_INITIAL_FOG_LEVEL)

    /** `initialWeather` of chapter [chIndex]. */
    public fun initialWeather(chIndex: Int): Int = u8(chIndex, OFF_INITIAL_WEATHER)

    /** `mapBgmIds[slot]` of chapter [chIndex] (`slot` 0 until [BGM_SLOTS]); 0xFFFF means none. */
    public fun mapBgmId(chIndex: Int, slot: Int): Int {
        require(slot in 0 until BGM_SLOTS) { "bgm slot: $slot" }
        return rom.u16(getROMChapterStruct(chIndex) + OFF_MAP_BGM_IDS + 2 * slot)
    }

    /** `victorySongEnemyThreshold` (s8) of chapter [chIndex]. */
    public fun victorySongEnemyThreshold(chIndex: Int): Int =
        u8(chIndex, OFF_VICTORY_SONG_ENEMY_THRESHOLD).toByte().toInt()

    private fun u8(chIndex: Int, offset: Int): Int = rom.u8(getROMChapterStruct(chIndex) + offset)

    private fun asset(id: Int): Int = rom.u32(CHAPTER_DATA_ASSET_TABLE + 4 * id)

    /** Table addresses and struct layout (`struct ROMChapterData`, include/chapterdata.h). */
    public companion object {
        /** GBA address of `gChapterDataTable`. */
        public const val CHAPTER_DATA_TABLE: Int = 0x088B0890

        /** GBA address of `gChapterDataAssetTable`. */
        public const val CHAPTER_DATA_ASSET_TABLE: Int = 0x088B363C

        /** `sizeof(struct ROMChapterData)`. */
        public const val ENTRY_SIZE: Int = 0x94

        /** Number of chapters in `gChapterDataTable` (symbol size 0x2DAC / [ENTRY_SIZE]). */
        public const val CHAPTER_COUNT: Int = 79

        /** Chapter index of the SRAM-backed extra map, which is not in the ROM tables. */
        public const val EXTRA_MAP_CHAPTER: Int = 0x7F

        /** Number of `mapBgmIds` entries per chapter. */
        public const val BGM_SLOTS: Int = 11

        /** Offset of `map.obj1Id`. */
        public const val OFF_MAP_OBJ1_ID: Int = 0x04

        /** Offset of `map.obj2Id`. */
        public const val OFF_MAP_OBJ2_ID: Int = 0x05

        /** Offset of `map.paletteId`. */
        public const val OFF_MAP_PALETTE_ID: Int = 0x06

        /** Offset of `map.tileConfigId`. */
        public const val OFF_MAP_TILE_CONFIG_ID: Int = 0x07

        /** Offset of `map.mainLayerId`. */
        public const val OFF_MAP_MAIN_LAYER_ID: Int = 0x08

        /** Offset of `map.objAnimId`. */
        public const val OFF_MAP_OBJ_ANIM_ID: Int = 0x09

        /** Offset of `map.paletteAnimId`. */
        public const val OFF_MAP_PALETTE_ANIM_ID: Int = 0x0A

        /** Offset of `map.changeLayerId`. */
        public const val OFF_MAP_CHANGE_LAYER_ID: Int = 0x0B

        /** Offset of `initialPosX`. */
        public const val OFF_INITIAL_POS_X: Int = 0x10

        /** Offset of `initialPosY`. */
        public const val OFF_INITIAL_POS_Y: Int = 0x11

        /** Offset of `initialFogLevel`. */
        public const val OFF_INITIAL_FOG_LEVEL: Int = 0x0C

        /** Offset of `initialWeather`. */
        public const val OFF_INITIAL_WEATHER: Int = 0x12

        /** Offset of `mapBgmIds`. */
        public const val OFF_MAP_BGM_IDS: Int = 0x16

        /** Offset of `victorySongEnemyThreshold`. */
        public const val OFF_VICTORY_SONG_ENEMY_THRESHOLD: Int = 0x86

        /** Offset of `mapEventDataId`. */
        public const val OFF_MAP_EVENT_DATA_ID: Int = 0x74
    }
}
