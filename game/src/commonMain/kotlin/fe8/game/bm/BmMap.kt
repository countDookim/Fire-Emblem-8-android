package fe8.game.bm

import fe8.game.RomView

/**
 * The chapter map model of src/bmmap.c: the unpacked map layout (`gBmMapBuffer`), the tileset
 * config (`sTilesetConfig`), the base tile map (`gBmMapBaseTiles`), the terrain map and the other
 * byte pools (`gBmMapUnit`, `gBmMapMovement`, ...), plus `gBmMapSize`.
 *
 * Every buffer is preallocated at construction for the largest map (the C pool size
 * [MAP_POOL_SIZE]); loading a chapter never allocates. The row-pointer tables the C keeps in EWRAM
 * are implicit (see [Pool]).
 *
 * Stand-ins until M6-H: [applyEnabledMapChanges] (bmtrick.c-era map change application) and
 * [refreshAllLightRunes] (bmtrick.c) are later tasks, and the tileset graphics/palette unpack
 * (`UnpackChapterMapGraphics`) is in [ChapterMapGfx], not here; the chapter 0x75 water shadow pass
 * (`sub_8019624`) is not ported yet.
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
     * including the two tiles of safety above the map (`y` from -2) and, as in C, an `x` past the
     * row end reads the next row.
     */
    public class Pool {
        /** The pool bytes, [MAP_POOL_SIZE] long. */
        public val bytes: ByteArray = ByteArray(MAP_POOL_SIZE)

        /** Bytes reserved for the row-pointer table: `(height + 4) * 4`. */
        public var rowTableBytes: Int = 0
            private set

        /** Bytes per row: `width + 2`. */
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

    /** `gBmMapUnit`: unit slot index map (0 = empty, 1..62 = player, 65.. = NPC, 129.. = enemy). */
    public val unit: Pool = Pool()

    /** `gBmMapTerrain`: terrain ID map. */
    public val terrain: Pool = Pool()

    /** `gBmMapMovement`: movement cost / reachable area map. */
    public val movement: Pool = Pool()

    /** `gBmMapRange`: attack / staff range overlay map. */
    public val range: Pool = Pool()

    /** `gBmMapFog`: fog of war visibility map (0 = hidden by fog, >0 = visible). */
    public val fog: Pool = Pool()

    /** `gBmMapHidden`: hidden traps and hidden units map. */
    public val hidden: Pool = Pool()

    /** `gBmMapOther`: secondary working / calculation map. */
    public val other: Pool = Pool()

    /** The pool last passed to [bmMapFill] (`SetWorkingBmMap`'s `gWorkingBmMap`), if any. */
    public var workingMap: Pool? = null
        private set

    /** `gBmMapBuffer`: the decompressed map (`x`, `y` bytes, then `x * y` u16 tiles). */
    public val mapBuffer: ByteArray = ByteArray(MAP_BUFFER_SIZE)

    /** `sTilesetConfig`: [TILESET_CONFIG_SIZE] bytes, terrain lookup at [TERRAIN_LOOKUP]. */
    public val tilesetConfig: ByteArray = ByteArray(TILESET_CONFIG_SIZE)

    /** `sBmBaseTilesPool`: row-pointer table followed by the u16 base tiles. */
    private val baseTiles = ShortArray(MAP_POOL_SIZE)
    private var baseTilesOrigin = 0

    /**
     * Ported from src/bmmap.c: InitChapterMap
     *
     * Unpacks chapter [chapterId]'s map, sets up the unit/terrain/movement/range/fog/hidden/other
     * pools, builds the base tiles and refreshes the terrain map. Graphics and palette unpack
     * (`UnpackChapterMapGraphics`) is a separate task and is not done here. The base tile
     * adjustments after the unpack (`ApplyEnabledMapChanges`, shadow and tile changes) are
     * stand-ins until M6-H ([applyEnabledMapChanges] is a hook), so a chapter that has them
     * (e.g. Ch2)
     * differs from the game's pool; see [loadBaseTiles].
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
     * parameter, but every caller passes `gBmMapBuffer`), sets the map size, decompresses the
     * tileset config of the chapter into [tilesetConfig] and sets `cameraMax`.
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
     * above and below. The pool contents are left as they are.
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
     * Sets the outermost tiles of the map (first and last column and row) of [pool] to [value].
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
     * zeroed row below the map (for the subtitle help at the map edge).
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

    /** Reads `gBmMapBaseTiles[y][x]`, the tileset tile id of map tile ([x], [y]). */
    public fun baseTile(x: Int, y: Int): Int =
        baseTiles[baseTilesOrigin + y * sizeX + x].toInt() and 0xFFFF

    /**
     * Ported from src/bmmap.c: RefreshTerrainBmMap
     *
     * Fills [terrain] from the base tiles through the tileset terrain lookup, then calls
     * [refreshAllLightRunes].
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
     * systems may have overwritten).
     */
    public fun getTrueTerrainAt(x: Int, y: Int): Int = terrainLookup(baseTile(x, y))

    /** Looks up terrain ID from `sTilesetConfig` terrain table (`gTilesetTerrainLookup`) for [tile]. */
    private fun terrainLookup(tile: Int): Int =
        tilesetConfig[TERRAIN_LOOKUP + (tile shr 2)].toInt() and 0xFF

    /** Sizes of the C buffers. */
    public companion object {
        /** `MAP_POOL_SIZE`: bytes of each map pool (and halfwords of the base tile pool). */
        public const val MAP_POOL_SIZE: Int = 0x7B8

        /** Size of `gBmMapBuffer` in bytes. */
        public const val MAP_BUFFER_SIZE: Int = 0x800

        /** Size of `sTilesetConfig` in bytes (`u16[0x1000 + 0x200]`). */
        public const val TILESET_CONFIG_SIZE: Int = 0x2400

        /** Byte offset of `gTilesetTerrainLookup` (`sTilesetConfig + 0x1000` halfwords). */
        public const val TERRAIN_LOOKUP: Int = 0x2000

        /** Screen display width in pixels (240). */
        private const val DISPLAY_WIDTH = 240

        /** Screen display height in pixels (160). */
        private const val DISPLAY_HEIGHT = 160
    }
}
