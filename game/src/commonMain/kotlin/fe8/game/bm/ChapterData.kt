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
     * Returns the address of the `struct ROMChapterData` of chapter [chapterIndex].
     */
    public fun getROMChapterStruct(chapterIndex: Int): Int {
        require(chapterIndex in 0 until CHAPTER_COUNT) { "not a ROM chapter: $chapterIndex" }
        return CHAPTER_DATA_TABLE + chapterIndex * ENTRY_SIZE
    }

    /**
     * Ported from src/chapterdata.c: GetChapterMapPointer
     *
     * Returns the address of the (compressed) map layout of chapter [chapterIndex]:
     * `gChapterDataAssetTable[mainLayerId]`.
     */
    public fun getChapterMapPointer(chapterIndex: Int): Int =
        asset(u8(chapterIndex, OFF_MAP_MAIN_LAYER_ID))

    /**
     * Transcribed from src/chapterdata.c: GetChapterMapChangesPointer (not verified: no test checks
     *   it against a trace yet)
     *
     * Returns the address of the map change data of chapter [chapterIndex]:
     * `gChapterDataAssetTable[changeLayerId]`. Not yet covered by a `@VerifiesPort` test.
     */
    public fun getChapterMapChangesPointer(chapterIndex: Int): Int =
        asset(u8(chapterIndex, OFF_MAP_CHANGE_LAYER_ID))

    /**
     * Ported from src/chapterdata.c: GetChapterEventDataPointer
     *
     * Returns the address of the `ChapterEventGroup` of chapter [chapterIndex]:
     * `gChapterDataAssetTable[mapEventDataId]`.
     */
    public fun getChapterEventDataPointer(chapterIndex: Int): Int =
        asset(u8(chapterIndex, OFF_MAP_EVENT_DATA_ID))

    /** `map.obj1Id` of chapter [chapterIndex]: id of the first tileset object graphics. */
    public fun obj1Id(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_OBJ1_ID)

    /** `map.obj2Id` of chapter [chapterIndex]: id of the second tileset object graphics. */
    public fun obj2Id(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_OBJ2_ID)

    /** `map.paletteId` of chapter [chapterIndex]: id of the tileset palette. */
    public fun paletteId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_PALETTE_ID)

    /** `map.tileConfigId` of chapter [chapterIndex]: id of the tileset configuration / terrain lookup. */
    public fun tileConfigId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_TILE_CONFIG_ID)

    /** `map.mainLayerId` of chapter [chapterIndex]: asset table index of the map layout. */
    public fun mainLayerId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_MAIN_LAYER_ID)

    /** `map.objAnimId` of chapter [chapterIndex]: tileset object animation ID. */
    public fun objAnimId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_OBJ_ANIM_ID)

    /** `map.paletteAnimId` of chapter [chapterIndex]: tileset palette animation ID. */
    public fun paletteAnimId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_PALETTE_ANIM_ID)

    /** `map.changeLayerId` of chapter [chapterIndex]: asset table index of the map change data. */
    public fun changeLayerId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_CHANGE_LAYER_ID)

    /** `mapEventDataId` of chapter [chapterIndex]: asset table index of the event data. */
    public fun mapEventDataId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_EVENT_DATA_ID)

    /** `initialPosX` of chapter [chapterIndex]: initial camera tile X coordinate. */
    public fun initialPosX(chapterIndex: Int): Int = u8(chapterIndex, OFF_INITIAL_POS_X)

    /** `initialPosY` of chapter [chapterIndex]: initial camera tile Y coordinate. */
    public fun initialPosY(chapterIndex: Int): Int = u8(chapterIndex, OFF_INITIAL_POS_Y)

    /** `initialFogLevel` of chapter [chapterIndex]: starting vision range (0 = no fog). */
    public fun initialFogLevel(chapterIndex: Int): Int = u8(chapterIndex, OFF_INITIAL_FOG_LEVEL)

    /** `initialWeather` of chapter [chapterIndex]: starting weather condition ID. */
    public fun initialWeather(chapterIndex: Int): Int = u8(chapterIndex, OFF_INITIAL_WEATHER)

    /** `fadeToBlack` (u8, 0x87) of chapter [chapterIndex]: nonzero when the map start fades from black. */
    public fun fadeToBlack(chapterIndex: Int): Int = u8(chapterIndex, OFF_FADE_TO_BLACK)

    /** `mapBgmIds[slot]` of chapter [chapterIndex] (`slot` 0 until [BGM_SLOTS]); 0xFFFF means none. */
    public fun mapBgmId(chapterIndex: Int, slot: Int): Int {
        require(slot in 0 until BGM_SLOTS) { "bgm slot: $slot" }
        return rom.u16(getROMChapterStruct(chapterIndex) + OFF_MAP_BGM_IDS + 2 * slot)
    }

    /** `victorySongEnemyThreshold` (s8) of chapter [chapterIndex]: enemy count threshold to trigger victory theme. */
    public fun victorySongEnemyThreshold(chapterIndex: Int): Int =
        u8(chapterIndex, OFF_VICTORY_SONG_ENEMY_THRESHOLD).toByte().toInt()

    /** Reads the unsigned byte at [offset] within the chapter struct of [chapterIndex]. */
    private fun u8(chapterIndex: Int, offset: Int): Int = rom.u8(getROMChapterStruct(chapterIndex) + offset)

    /** Looks up the 32-bit ROM pointer for asset [assetId] from `gChapterDataAssetTable`. */
    private fun asset(assetId: Int): Int = rom.u32(CHAPTER_DATA_ASSET_TABLE + 4 * assetId)

    /** Table addresses and struct layout (`struct ROMChapterData`, include/chapterdata.h). */
    public companion object {
        /** GBA address of `gChapterDataTable`. */
        public const val CHAPTER_DATA_TABLE: Int = 0x088B0890

        /** GBA address of `gChapterDataAssetTable`. */
        public const val CHAPTER_DATA_ASSET_TABLE: Int = 0x088B363C

        /** `sizeof(struct ROMChapterData)` in bytes (0x94 = 148 bytes). */
        public const val ENTRY_SIZE: Int = 0x94

        /** Number of chapters in `gChapterDataTable` (symbol size 0x2DAC / [ENTRY_SIZE] = 79 chapters). */
        public const val CHAPTER_COUNT: Int = 79

        /** Chapter index of the SRAM-backed extra map, which is not in the ROM tables (0x7F). */
        public const val EXTRA_MAP_CHAPTER: Int = 0x7F

        /** Number of `mapBgmIds` entries per chapter (11 slots). */
        public const val BGM_SLOTS: Int = 11

        /** Offset of `map.obj1Id` in `struct ROMChapterData`. */
        public const val OFF_MAP_OBJ1_ID: Int = 0x04

        /** Offset of `map.obj2Id` in `struct ROMChapterData`. */
        public const val OFF_MAP_OBJ2_ID: Int = 0x05

        /** Offset of `map.paletteId` in `struct ROMChapterData`. */
        public const val OFF_MAP_PALETTE_ID: Int = 0x06

        /** Offset of `map.tileConfigId` in `struct ROMChapterData`. */
        public const val OFF_MAP_TILE_CONFIG_ID: Int = 0x07

        /** Offset of `map.mainLayerId` in `struct ROMChapterData`. */
        public const val OFF_MAP_MAIN_LAYER_ID: Int = 0x08

        /** Offset of `map.objAnimId` in `struct ROMChapterData`. */
        public const val OFF_MAP_OBJ_ANIM_ID: Int = 0x09

        /** Offset of `map.paletteAnimId` in `struct ROMChapterData`. */
        public const val OFF_MAP_PALETTE_ANIM_ID: Int = 0x0A

        /** Offset of `map.changeLayerId` in `struct ROMChapterData`. */
        public const val OFF_MAP_CHANGE_LAYER_ID: Int = 0x0B

        /** Offset of `initialPosX` in `struct ROMChapterData`. */
        public const val OFF_INITIAL_POS_X: Int = 0x10

        /** Offset of `initialPosY` in `struct ROMChapterData`. */
        public const val OFF_INITIAL_POS_Y: Int = 0x11

        /** Offset of `initialFogLevel` in `struct ROMChapterData`. */
        public const val OFF_INITIAL_FOG_LEVEL: Int = 0x0C

        /** Offset of `initialWeather` in `struct ROMChapterData`. */
        public const val OFF_INITIAL_WEATHER: Int = 0x12

        /** Offset of `mapBgmIds` in `struct ROMChapterData`. */
        public const val OFF_MAP_BGM_IDS: Int = 0x16

        /** Offset of `fadeToBlack` in `struct ROMChapterData`. */
        public const val OFF_FADE_TO_BLACK: Int = 0x87

        /** Offset of `victorySongEnemyThreshold` in `struct ROMChapterData`. */
        public const val OFF_VICTORY_SONG_ENEMY_THRESHOLD: Int = 0x86

        /** Offset of `mapEventDataId` in `struct ROMChapterData`. */
        public const val OFF_MAP_EVENT_DATA_ID: Int = 0x74
    }
}
