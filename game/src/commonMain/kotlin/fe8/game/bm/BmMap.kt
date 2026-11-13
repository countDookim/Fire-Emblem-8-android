package fe8.game.bm

import fe8.game.RomView

/**
 * The chapter map model of src/bmmap.c: the unpacked map layout (`gBmMapBuffer`), the tileset
 * config (`sTilesetConfig`), the base tile map (`gBmMapBaseTiles`: which tileset tile each map
 * square shows, before units), the terrain map and the other byte pools (`gBmMapUnit`,
 * `gBmMapMovement`, ...), plus `gBmMapSize`. Each pool is a map-sized grid of one byte per tile
 * that the rest of the game reads by `[y][x]`: who stands where, what terrain is there, how far
 * the selected unit can move or attack, what the fog hides.
 *
 * Every buffer is preallocated at construction for the largest map (the C pool size
 * [MAP_POOL_SIZE]); loading a chapter never allocates. The row-pointer tables the C keeps in EWRAM
 * are implicit (see [Pool]).
 *
 * Stand-ins until M6-H: [applyEnabledMapChanges] (the map change events, such as a wall opening
 * or a door replaced by floor, which rewrite base tiles) and [refreshAllLightRunes] (bmtrick.c)
 * are later tasks, and the tileset graphics/palette unpack (`UnpackChapterMapGraphics`) is in
 * [ChapterMapGfx], not here. Also not ported: the water shadow pass `sub_8019624`, which
 * `InitChapterMap` runs only for chapter `CHAPTER_75` (0x75) and `sub_8019778` runs after a
 * reload, and `InitMapForMinimap` (the minimap's cut-down init).
 */
public class BmMap(
    /** ROM the chapter tables, maps and tileset configs are read from. */
    private val rom: RomView,
    /** Chapter table access (map pointer, `tileConfigId`). */
    private val chapterData: ChapterData,
    /** Battle map state whose `cameraMax` [unpackChapterMap] sets. */
    private val bmSt: BmSt,
    /**
     * Stand-in for `ApplyEnabledMapChanges` (until M6-H), called by [initChapterMap] after the
     * base tiles are built; does nothing until the map change system is ported.
     */
    public var applyEnabledMapChanges: () -> Unit = {},
    /**
     * Stand-in for `RefreshAllLightRunes` (bmtrick.c; until M6-H), called at the end of
     * [refreshTerrainBmMap]; does nothing until traps are ported.
     */
    public var refreshAllLightRunes: () -> Unit = {},
) {
    /**
     * One `u8** gBmMapX` pool of `MAP_POOL_SIZE` bytes. In the C the first `(height + 4)` pointers
     * of the pool are row pointers and the tile rows follow; here the table is only reserved
     * ([rowTableBytes]) and rows are addressed arithmetically. `get(y, x)` mirrors `gBmMapX[y][x]`
     * including the two rows of safety above and below the map (`y` from -2 to height + 1, so a
     * range that sticks out of the map can be written without checks) and, as in C, an `x` past
     * the row end reads the next row; the row is `width + 2` bytes wide, the 2 extra bytes shared
     * between neighbouring rows ("two tiles on each edge (shared)" in `BmMapInit`).
     */
    public class Pool {
        /** The pool bytes, [MAP_POOL_SIZE] long. */
        public val bytes: ByteArray = ByteArray(MAP_POOL_SIZE)

        /** Bytes reserved for the row-pointer table: `(height + 4) * 4`. */
        public var rowTableBytes: Int = 0
            private set

        /** Bytes per row: `width + 2` (`BmMapInit`: the extra 2 are shared between rows). */
        public var stride: Int = 0
            private set

        /** Reads `map[y][x]`. */
        public operator fun get(y: Int, x: Int): Int =
            bytes[rowTableBytes + (y + 2) * stride + x].toInt() and 0xFF

        /** Writes `map[y][x] = value`. */
        public operator fun set(y: Int, x: Int, value: Int) {
            bytes[rowTableBytes + (y + 2) * stride + x] = value.toByte()
        }

        /**
         * Computes the row table offset and stride for a map of [width] x [height] tiles,
         * reserving padding rows for out-of-bounds safety.
         */
        internal fun layout(width: Int, height: Int) {
            val rows = height + 4
            val table = rows * 4
            val size = table + rows * (width + 2)
            require(size <= MAP_POOL_SIZE) { "map ${width}x$height exceeds the map pool" }
            rowTableBytes = table
            stride = width + 2
        }
    }

    /** `gBmMapSize.x`: map width in tiles. */
    public var sizeX: Int = 0
        private set

    /** `gBmMapSize.y`: map height in tiles. */
    public var sizeY: Int = 0
        private set

    /**
     * `gBmMapUnit`: which unit stands on each tile, as its unit slot number (the index
     * `GetUnit` takes), 0 for an empty tile: 0x01..0x3F player, 0x41..0x7F NPC, 0x81..0xBF
     * enemy, 0xC1..0xC5 link arena. Filled by [EntityBmMaps.refreshUnitsOnBmMap]; enemy units
     * hidden by fog are left out of it.
     */
    public val unit: Pool = Pool()

    /**
     * `gBmMapTerrain`: the terrain id of each tile, looked up from the base tile
     * ([refreshTerrainBmMap]); map changes and traps can overwrite single entries.
     */
    public val terrain: Pool = Pool()

    /**
     * `gBmMapMovement`: the unit move range: the least movement cost from the starting tile to
     * each tile (0 at the start), filled with -1 (0xFF) first so unreachable tiles stay 0xFF
     * (negative as `s8`, which `DisplayMovementViewTile` tests to choose the movement
     * squares). A value above `MAP_MOVEMENT_MAX` (120) means not reachable;
     * `MAP_MOVEMENT_EXTENDED` (124) is the movement budget some callers pass to
     * `GenerateMovementMap` (src/bmidoten.c) instead of a unit's own Mov. Not filled in this
     * class.
     */
    public val movement: Pool = Pool()

    /**
     * `gBmMapRange`: nonzero where a unit's attack or staff range reaches (read as `s8`
     * through `gMapRangeSigned`; `DisplayMovementViewTile` draws the range squares from it).
     * Not filled in this class.
     */
    public val range: Pool = Pool()

    /**
     * `gBmMapFog`: 0 where fog of war hides the tile, nonzero where it is visible (all 1 in a
     * chapter without fog, `chapterVisionRange` 0). Visible tiles are drawn with palette bank 6
     * and fogged ones with bank 11 ([BmMapRender.displayBmTile]). Units add 1 around them
     * (their vision) and magic-seal units subtract 1 (`RefreshUnitsOnBmMap`).
     */
    public val fog: Pool = Pool()

    /**
     * `gBmMapHidden`: bit 0 (`HIDDEN_BIT_UNIT`) is set where a unit stands in the fog and is
     * kept off [unit]; bit 1 (`HIDDEN_BIT_TRAP`, set by `RefreshMinesOnBmMap` for a mine on a
     * tile with no unit) marks a hidden trap there. Other code uses it so that a hidden unit or
     * mine still blocks that tile (src/cp_utility.c, src/bmidoten.c).
     */
    public val hidden: Pool = Pool()

    /**
     * `gBmMapOther`: a scratch map several systems borrow; callers clear it first (`BmMapFill`).
     * Known uses in the decomp: the AI's danger counts per tile, and the unit id of the moving
     * unit on its square during unit movement (src/muctrl.c). Not filled in this class.
     */
    public val other: Pool = Pool()

    /** The pool last passed to [bmMapFill] (`SetWorkingBmMap`'s `gWorkingBmMap`), if any. */
    public var workingMap: Pool? = null
        internal set

    /**
     * `gBmMapBuffer`: the decompressed chapter map: the width and height bytes, then
     * `width * height` u16 base tiles row by row (0x800 bytes).
     */
    public val mapBuffer: ByteArray = ByteArray(MAP_BUFFER_SIZE)

    /**
     * `sTilesetConfig`: [TILESET_CONFIG_SIZE] bytes: first 0x1000 halfwords (the four BG tilemap
     * entries of each metatile, 4 halfwords per metatile) then, at [TERRAIN_LOOKUP], one terrain
     * id byte per metatile (`gTilesetTerrainLookup`).
     */
    public val tilesetConfig: ByteArray = ByteArray(TILESET_CONFIG_SIZE)

    /**
     * `sBmBaseTilesPool`: `gBmMapBaseTiles`, a row-pointer table followed by the u16 base tiles
     * (one row more than the map, see [initBaseTilesBmMap]). Each tile is the halfword index of
     * its metatile in [tilesetConfig] (metatile number times 4).
     */
    private val baseTiles = ShortArray(MAP_POOL_SIZE)
    private var baseTilesOrigin = 0

    /**
     * Ported from src/bmmap.c: InitChapterMap
     *
     * Called when a chapter map is set up (a new chapter, a suspend resume, the link arena map
     * 0x3A): unpacks chapter [chapterId]'s map, sets up the unit/terrain/movement/range/fog/
     * hidden/other pools, clears unit and terrain, builds the base tiles and refreshes the
     * terrain map. Graphics and palette unpack (`UnpackChapterMapGraphics`) is a separate task
     * and is not done here. The base tile adjustments after the unpack (`ApplyEnabledMapChanges`,
     * the `CHAPTER_75` water shadows) are stand-ins until M6-H ([applyEnabledMapChanges] is a
     * hook), so a chapter that has them (e.g. Ch2) differs from the game's pool; see
     * [loadBaseTiles].
     */
    public fun initChapterMap(chapterId: Int) {
        unpackChapterMap(chapterId)

        bmMapInit(unit, sizeX, sizeY)
        bmMapInit(terrain, sizeX, sizeY)
        bmMapInit(movement, sizeX, sizeY)
        bmMapInit(range, sizeX, sizeY)
        bmMapInit(fog, sizeX, sizeY)
        bmMapInit(hidden, sizeX, sizeY)
        bmMapInit(other, sizeX, sizeY)

        bmMapFill(unit, 0)
        bmMapFill(terrain, 0)

        initBaseTilesBmMap()
        applyEnabledMapChanges()
        refreshTerrainBmMap()
    }

    /**
     * Copies a raw `sBmBaseTilesPool` image (`gBmMapBaseTiles`: `sizeY + 1` row pointers of 4
     * bytes, then `sizeY + 1` rows of `sizeX` little-endian u16 tiles) from [src] at [offset] into
     * the base tiles, for the current [sizeX] x [sizeY]. It exists because the base tile
     * adjustments of [initChapterMap] are not ported yet (M6-H): tests seed the pool from RAM
     * dumps with it, and the suspend load will need it. Does not allocate.
     */
    public fun loadBaseTiles(src: ByteArray, offset: Int) {
        val rows = sizeY + 1
        baseTilesOrigin = rows * 2
        var sourceOffset = offset + rows * 4
        for (tileIndex in 0 until rows * sizeX) {
            baseTiles[baseTilesOrigin + tileIndex] =
                ((src[sourceOffset].toInt() and 0xFF) or (src[sourceOffset + 1].toInt() shl 8)).toShort()
            sourceOffset += 2
        }
    }

    /**
     * Ported from src/bmmap.c: UnpackChapterMap
     *
     * Decompresses the map of [chapterId] into [mapBuffer] (the C takes the destination as a
     * parameter, but every caller passes `gBmMapBuffer`), sets the map size (the first two
     * bytes), decompresses the tileset config of the chapter (`tileConfigId` in the chapter
     * data) into [tilesetConfig] and sets `cameraMax` to the camera position that shows the
     * map's right and bottom edge (map size in pixels minus the 240x160 screen).
     */
    public fun unpackChapterMap(chapterId: Int) {
        rom.decompressInto(chapterData.getChapterMapPointer(chapterId), mapBuffer, 0)

        sizeX = mapBuffer[0].toInt() and 0xFF
        sizeY = mapBuffer[1].toInt() and 0xFF

        val configId = chapterData.tileConfigId(chapterId)
        rom.decompressInto(
            rom.u32(ChapterData.CHAPTER_DATA_ASSET_TABLE + 4 * configId),
            tilesetConfig,
            0,
        )

        bmSt.cameraMaxX = sizeX * 16 - DISPLAY_WIDTH
        bmSt.cameraMaxY = sizeY * 16 - DISPLAY_HEIGHT
    }

    /**
     * Ported from src/bmmap.c: BmMapInit
     *
     * Lays out [pool] for a [width] x [height] map: rows `width + 2` bytes wide, two extra rows
     * above and below (the C sets up the row pointers and points `gBmMapX` at the third one).
     * The pool contents are left as they are.
     */
    public fun bmMapInit(pool: Pool, width: Int, height: Int) {
        pool.layout(width, height)
    }

    /**
     * Ported from src/bmmap.c: BmMapFill
     *
     * Fills the whole tile area of [pool] (rows -2 to height + 1) with the byte [value], two
     * bytes at a time (an odd count loses its last byte) and makes it the working map.
     */
    public fun bmMapFill(pool: Pool, value: Int) {
        var fillByteCount = (sizeY + 4) * (sizeX + 2)
        if (fillByteCount % 2 != 0) fillByteCount -= 1
        val startOffset = pool.rowTableBytes
        pool.bytes.fill(value.toByte(), startOffset, startOffset + fillByteCount)
        workingMap = pool
    }

    /**
     * Transcribed from src/bmmap.c: BmMapFillEdges (not verified: no test checks it against a trace
     *   yet)
     *
     * Sets the outermost tiles of the map (first and last column and row) of [pool] to [value],
     * for example to wall in a range map. Not the padding rows; only the map's own border.
     */
    public fun bmMapFillEdges(pool: Pool, value: Int) {
        for (tileY in 0 until sizeY) {
            pool[tileY, 0] = value
            pool[tileY, sizeX - 1] = value
        }
        for (tileX in 0 until sizeX) {
            pool[0, tileX] = value
            pool[sizeY - 1, tileX] = value
        }
    }

    /**
     * Ported from src/bmmap.c: InitBaseTilesBmMap
     *
     * Copies the u16 tiles of [mapBuffer] into the base tile map; the pool holds one extra,
     * zeroed row below the map: the decomp comment says it is for the "subtitle help" shown
     * against the map edge, whose camera may reach one row (16 pixels) past the bottom
     * (`StartSubtitleHelp` raises `cameraMax.y` by 16).
     */
    public fun initBaseTilesBmMap() {
        val rows = sizeY + 1
        // The C writes `rows` row pointers (4 bytes each) in front of the tiles.
        baseTilesOrigin = rows * 2
        val used = baseTilesOrigin + rows * sizeX
        require(used <= MAP_POOL_SIZE) { "map exceeds the base tile pool" }
        var bufferOffset = 2
        for (tileY in 0 until sizeY) {
            for (tileX in 0 until sizeX) {
                val lowByte = mapBuffer[bufferOffset].toInt() and 0xFF
                val highByte = mapBuffer[bufferOffset + 1].toInt() and 0xFF
                baseTiles[baseTilesOrigin + tileY * sizeX + tileX] = (lowByte or (highByte shl 8)).toShort()
                bufferOffset += 2
            }
        }
        for (tileX in 0 until sizeX) baseTiles[baseTilesOrigin + sizeY * sizeX + tileX] = 0
    }

    /**
     * Reads `gBmMapBaseTiles[y][x]`: the halfword index into [tilesetConfig] of the metatile
     * (4 BG tiles, 2x2) drawn on map tile ([x], [y]); dividing by 4 gives the metatile number.
     */
    public fun baseTile(x: Int, y: Int): Int =
        baseTiles[baseTilesOrigin + y * sizeX + x].toInt() and 0xFFFF

    /**
     * Ported from src/bmmap.c: RefreshTerrainBmMap
     *
     * Fills [terrain] from the base tiles through the tileset terrain lookup (one terrain id per
     * metatile), then calls [refreshAllLightRunes] (`RefreshAllLightRunes`, bmtrick.c: sets
     * the terrain of each light rune tile to `TERRAIN_NONE`). Run after the base tiles change.
     */
    public fun refreshTerrainBmMap() {
        for (tileY in 0 until sizeY) {
            for (tileX in 0 until sizeX) terrain[tileY, tileX] = terrainLookup(baseTile(tileX, tileY))
        }
        refreshAllLightRunes()
    }

    /**
     * Ported from src/bmmap.c: GetTrueTerrainAt
     *
     * The terrain id of tile ([x], [y]) from the base tiles, ignoring [terrain] (which other
     * systems may have overwritten, such as a light rune).
     */
    public fun getTrueTerrainAt(x: Int, y: Int): Int = terrainLookup(baseTile(x, y))

    /**
     * `gTilesetTerrainLookup[tile >> 2]`: the terrain id of a base [tile] (a halfword index, so
     * `tile >> 2` is the metatile number).
     */
    private fun terrainLookup(tile: Int): Int =
        tilesetConfig[TERRAIN_LOOKUP + (tile shr 2)].toInt() and 0xFF

    /** Sizes of the C buffers. */
    public companion object {
        /**
         * `MAP_POOL_SIZE` (src/bmmap.c): bytes of each map pool (and halfwords of the base tile
         * pool); the row table, the padded rows and, for the base tiles, the extra row must fit.
         */
        public const val MAP_POOL_SIZE: Int = 0x7B8

        /** Size of `gBmMapBuffer` in bytes (`u16[0x800 / 2]`). */
        public const val MAP_BUFFER_SIZE: Int = 0x800

        /** Size of `sTilesetConfig` in bytes (`u16[0x1000 + 0x200]`). */
        public const val TILESET_CONFIG_SIZE: Int = 0x2400

        /**
         * Byte offset of `gTilesetTerrainLookup` in [BmMap.tilesetConfig]
         * (`sTilesetConfig + 0x1000` halfwords).
         */
        public const val TERRAIN_LOOKUP: Int = 0x2000

        /** `DISPLAY_WIDTH` (gba/defines.h): 240 pixels. */
        private const val DISPLAY_WIDTH = 240

        /** `DISPLAY_HEIGHT` (gba/defines.h): 160 pixels. */
        private const val DISPLAY_HEIGHT = 160
    }
}
