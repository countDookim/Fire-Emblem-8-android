package fe8.game.bm

import fe8.game.RomView

/**
 * Read-only view of the ROM chapter tables: `gChapterDataTable` (`struct ROMChapterData`, one
 * [ENTRY_SIZE]-byte entry per chapter: which map, tileset, music, weather, fog, camera start
 * and goal the chapter has) and `gChapterDataAssetTable` (pointers to map, map change, tileset
 * graphics, animation and event data, indexed by the ids stored in the chapter entries).
 * Chapter indices are the `gPlaySt.chapterIndex` values.
 *
 * Addresses are GBA addresses (0x08xxxxxx); nothing is allocated per call. The extra map
 * ([EXTRA_MAP_CHAPTER], `0x7F`) lives in SRAM in the original (`gExtraMapInfo`) and is not part
 * of the ROM tables: the lookups reject it with [IllegalArgumentException], where the C branches
 * to the SRAM copy.
 */
public class ChapterData(
    /** The ROM the tables are read from. */
    private val rom: RomView,
) {
    /**
     * Ported from src/chapterdata.c: GetROMChapterStruct
     *
     * Returns the address of the `struct ROMChapterData` of chapter [chapterIndex] (the C takes
     * `0x7F` to mean the extra map and returns its SRAM struct instead).
     */
    public fun getROMChapterStruct(chapterIndex: Int): Int {
        require(chapterIndex in 0 until CHAPTER_COUNT) { "not a ROM chapter: $chapterIndex" }
        return CHAPTER_DATA_TABLE + chapterIndex * ENTRY_SIZE
    }

    /**
     * Ported from src/chapterdata.c: GetChapterMapPointer
     *
     * Returns the address of the (compressed) map layout of chapter [chapterIndex] (the tile
     * grid the map is drawn from, `UnpackChapterMap` in src/bmmap.c):
     * `gChapterDataAssetTable[mainLayerId]`.
     */
    public fun getChapterMapPointer(chapterIndex: Int): Int =
        asset(u8(chapterIndex, OFF_MAP_MAIN_LAYER_ID))

    /**
     * Transcribed from src/chapterdata.c: GetChapterMapChangesPointer (not verified: no test checks
     *   it against a trace yet)
     *
     * Returns the address of the map change data of chapter [chapterIndex] (the `MapChangeInfo`
     * records: tile blocks that events swap in, such as a wall that opens):
     * `gChapterDataAssetTable[changeLayerId]`. Not yet covered by a `@VerifiesPort` test.
     */
    public fun getChapterMapChangesPointer(chapterIndex: Int): Int =
        asset(u8(chapterIndex, OFF_MAP_CHANGE_LAYER_ID))

    /**
     * Ported from src/chapterdata.c: GetChapterEventDataPointer
     *
     * Returns the address of the `ChapterEventGroup` of chapter [chapterIndex] (the turn,
     * character and location events, traps, starting units and opening/ending scenes of the
     * chapter): `gChapterDataAssetTable[mapEventDataId]`.
     */
    public fun getChapterEventDataPointer(chapterIndex: Int): Int =
        asset(u8(chapterIndex, OFF_MAP_EVENT_DATA_ID))

    /**
     * `chapTitleId` (u8, 0x0E) of chapter [chapterIndex]: the id of the chapter title banner
     * graphic (`GetChapterTitleExtra` and `GetChapterTitleWM` in src/chapter_title.c return it;
     * they use fixed ids 0x54 (no data), 0x55 (epilogue) and 0x57 (post-game) for those saves,
     * and `GetChapterTitleWM` 0x46 on for world map monster spawn stops).
     */
    public fun chapTitleId(chapterIndex: Int): Int = u8(chapterIndex, OFF_CHAP_TITLE_ID)

    /**
     * `map.obj1Id` (0x04) of chapter [chapterIndex]: `gChapterDataAssetTable` index of the
     * compressed tileset graphics, loaded to BG tile 0x400 (`UnpackChapterMapGraphics`).
     */
    public fun obj1Id(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_OBJ1_ID)

    /**
     * `map.obj2Id` (0x05) of chapter [chapterIndex]: asset table index of the optional second
     * half of the tileset graphics, loaded to BG tile 0x600; the entry is null when the
     * tileset has no second part.
     */
    public fun obj2Id(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_OBJ2_ID)

    /** `map.paletteId` (0x06) of chapter [chapterIndex]: asset table index of the tile palette. */
    public fun paletteId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_PALETTE_ID)

    /**
     * `map.tileConfigId` (0x07) of chapter [chapterIndex]: asset table index of the tileset
     * configuration (`sTilesetConfig`: each metatile's four tiles and its terrain id).
     */
    public fun tileConfigId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_TILE_CONFIG_ID)

    /** `map.mainLayerId` (0x08) of chapter [chapterIndex]: asset table index of the map layout. */
    public fun mainLayerId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_MAIN_LAYER_ID)

    /**
     * `map.objAnimId` (0x09) of chapter [chapterIndex]: asset table index of the animated tile
     * graphics frames (`MapObjAnimFrm`, e.g. moving water); `BMapVSync_InitMapAnimations`.
     */
    public fun objAnimId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_OBJ_ANIM_ID)

    /**
     * `map.paletteAnimId` (0x0A) of chapter [chapterIndex]: asset table index of the tile
     * palette animation frames (`MapPaletteAnimFrm`, colors cycled over time).
     */
    public fun paletteAnimId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_PALETTE_ANIM_ID)

    /** `map.changeLayerId` (0x0B) of chapter [chapterIndex]: asset table index of map changes. */
    public fun changeLayerId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_CHANGE_LAYER_ID)

    /** `mapEventDataId` (0x74) of chapter [chapterIndex]: asset table index of the event group. */
    public fun mapEventDataId(chapterIndex: Int): Int = u8(chapterIndex, OFF_MAP_EVENT_DATA_ID)

    /**
     * `initialPosX` (0x10) of chapter [chapterIndex]: the tile column the camera is centered on
     * when the chapter starts (`ChapterIntro`, src/chapterintrofx.c), also where the chapter
     * title card is centered.
     */
    public fun initialPosX(chapterIndex: Int): Int = u8(chapterIndex, OFF_INITIAL_POS_X)

    /** `initialPosY` (0x11) of chapter [chapterIndex]: the tile row the camera starts on. */
    public fun initialPosY(chapterIndex: Int): Int = u8(chapterIndex, OFF_INITIAL_POS_Y)

    /**
     * `initialFogLevel` (0x0C) of chapter [chapterIndex]: the starting fog of war vision range
     * (`gPlaySt.chapterVisionRange`, 0 = no fog); also what the fog events restore when they
     * pass a negative range. A skirmish map picks its own range at random instead.
     */
    public fun initialFogLevel(chapterIndex: Int): Int = u8(chapterIndex, OFF_INITIAL_FOG_LEVEL)

    /**
     * `initialWeather` (0x12) of chapter [chapterIndex]: the starting weather
     * (`gPlaySt.chapterWeatherId`: `WEATHER_FINE` 0, snow 1, snowstorm 2, night 3, rain 4,
     * flames 5, sandstorm 6, clouds 7).
     */
    public fun initialWeather(chapterIndex: Int): Int = u8(chapterIndex, OFF_INITIAL_WEATHER)

    /**
     * `fadeToBlack` (bool8, 0x87) of chapter [chapterIndex]: nonzero makes the chapter intro skip
     * the cross-fade into the map and start the `MAP_BGM_PROLOGUE` song at once, as skirmish
     * maps do (`ChapterIntro_LoopFadeToMap`, src/chapterintrofx.c).
     */
    public fun fadeToBlack(chapterIndex: Int): Int = u8(chapterIndex, OFF_FADE_TO_BLACK)

    /**
     * `mapBgmIds[slot]` (u16 array at 0x16) of chapter [chapterIndex]: the song for a
     * `MAP_BGM_*` slot (`slot` 0 until [BGM_SLOTS]): 0 player phase, 1 enemy, 2 NPC, 3-5 the
     * same three for the Hector story (left over from FE7), 6 / 7 the player/NPC and enemy
     * songs after the music change flag, 8-10 prologue songs (`MAP_BGM_PROLOGUE` = 9 plays at the
     * chapter intro). 0xFFFF means none. Chosen by `GetCurrentMapMusicIndex`, src/bm.c.
     */
    public fun mapBgmId(chapterIndex: Int, slot: Int): Int {
        require(slot in 0 until BGM_SLOTS) { "bgm slot: $slot" }
        return rom.u16(getROMChapterStruct(chapterIndex) + OFF_MAP_BGM_IDS + 2 * slot)
    }

    /**
     * `victorySongEnemyThreshold` (s8, 0x86) of chapter [chapterIndex]: when the player phase
     * song is chosen and this many enemies or fewer remain, the victory song plays instead (0
     * disables it; skirmishes use 1 regardless); `GetCurrentMapMusicIndex`, src/bm.c.
     */
    public fun victorySongEnemyThreshold(chapterIndex: Int): Int =
        u8(chapterIndex, OFF_VICTORY_SONG_ENEMY_THRESHOLD).toByte().toInt()

    /** Reads the unsigned byte at [offset] within the chapter struct of [chapterIndex]. */
    private fun u8(chapterIndex: Int, offset: Int): Int =
        rom.u8(getROMChapterStruct(chapterIndex) + offset)

    /**
     * Looks up the 32-bit ROM pointer at index [assetId] of `gChapterDataAssetTable` (0 when the
     * chapter has no such asset).
     */
    private fun asset(assetId: Int): Int = rom.u32(CHAPTER_DATA_ASSET_TABLE + 4 * assetId)

    /** Table addresses and struct layout (`struct ROMChapterData`, include/chapterdata.h). */
    public companion object {
        /** GBA address of `gChapterDataTable`. */
        public const val CHAPTER_DATA_TABLE: Int = 0x088B0890

        /** GBA address of `gChapterDataAssetTable`. */
        public const val CHAPTER_DATA_ASSET_TABLE: Int = 0x088B363C

        /** `sizeof(struct ROMChapterData)`: 0x94 bytes (last member `unk93` at 0x93). */
        public const val ENTRY_SIZE: Int = 0x94

        /**
         * Number of chapters in `gChapterDataTable`: 79 (the decomp's chapter_settings.json has
         * 79 entries; the symbol size 0x2DAC / [ENTRY_SIZE] agrees).
         */
        public const val CHAPTER_COUNT: Int = 79

        /** Chapter index of the SRAM-backed extra map, which is not in the ROM tables (0x7F). */
        public const val EXTRA_MAP_CHAPTER: Int = 0x7F

        /** Number of `mapBgmIds` entries per chapter: the 11 `MAP_BGM_*` slots. */
        public const val BGM_SLOTS: Int = 11

        /** Offset of `chapTitleId` in `struct ROMChapterData`. */
        public const val OFF_CHAP_TITLE_ID: Int = 0x0E

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
