package fe8.game.bm

import gbafe.render.BgHardware

/**
 * Battle map tile rendering of src/bmmap.c: [displayBmTile] writes one 2x2 metatile into a BG
 * tilemap buffer, [renderBmMap] and [renderBmMapOnBg2] redraw the whole 15x10 visible window
 * into `gBG3TilemapBuffer` / `gBG2TilemapBuffer` of [hardware]. Nothing here allocates.
 *
 * The incremental scroll renderers (`RenderBmMapColumn`, `UpdateBmMapDisplay`, ...) are not part
 * of this class.
 */
public class BmMapRender(
    /** `gBmSt`: camera in, `mapRenderOrigin` out. */
    private val bmSt: BmSt,
    /** The map pools (`gBmMapBaseTiles`, `gBmMapFog`) and `sTilesetConfig`. */
    private val map: BmMap,
    /** The BG tilemap buffers, BG position, sync flags and `gLCDControlBuffer`. */
    private val hardware: BgHardware,
) {
    /**
     * Ported from src/bmmap.c: DisplayBmTile
     *
     * Writes the 2x2 tilemap entries of the tileset metatile of map tile ([xBmMap], [yBmMap]) to
     * [bg] at tile ([xTileMap], [yTileMap]) (`bg` has 0x20 entries per row), with palette bank 6,
     * or 11 where `gBmMapFog` is 0.
     */
    public fun displayBmTile(
        bg: ShortArray,
        xTileMap: Int,
        yTileMap: Int,
        xBmMap: Int,
        yBmMap: Int,
    ) {
        val out = yTileMap * 0x40 + xTileMap * 2
        // `sTilesetConfig + tile` is a u16 pointer: the base tile is a halfword index.
        val config = map.tilesetConfig
        val tile = map.baseTile(xBmMap, yBmMap) * 2
        val base = if (map.fog[yBmMap, xBmMap] != 0) 6 shl 12 else 11 shl 12
        bg[out] = (base + half(config, tile)).toShort()
        bg[out + 1] = (base + half(config, tile + 2)).toShort()
        bg[out + 0x20] = (base + half(config, tile + 4)).toShort()
        bg[out + 0x21] = (base + half(config, tile + 6)).toShort()
    }

    private fun half(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun renderWindow(bg: ShortArray) {
        bmSt.mapRenderOriginX = (bmSt.cameraX shr 4) and 0xFFFF
        bmSt.mapRenderOriginY = (bmSt.cameraY shr 4) and 0xFFFF
        val originX = bmSt.mapRenderOriginX.toShort().toInt()
        val originY = bmSt.mapRenderOriginY.toShort().toInt()
        for (iy in VISIBLE_ROWS - 1 downTo 0) {
            for (ix in VISIBLE_COLUMNS - 1 downTo 0) {
                displayBmTile(bg, ix, iy, originX + ix, originY + iy)
            }
        }
    }

    /**
     * Ported from src/bmmap.c: RenderBmMap
     *
     * Sets `gBmSt.mapRenderOrigin` from the camera, draws the 15x10 visible tiles into
     * `gBG3TilemapBuffer`, requests the BG3 sync, resets the BG3 scroll and enables all layers.
     */
    public fun renderBmMap() {
        renderWindow(hardware.mapBuffer(BG3))
        hardware.enableBgSyncByMask(BG3_SYNC_BIT)
        hardware.lcd.setBgPosition(BG3, 0, 0)
        hardware.lcd.setDispEnable(1, 1, 1, 1, 1)
    }

    /**
     * Transcribed from src/bmmap.c: RenderBmMapOnBg2 (not verified: no trace calls it)
     *
     * [renderBmMap] onto BG2: tile data offset 0x8000, `gBG2TilemapBuffer`, BG2 sync and scroll
     * reset (the display enable is left alone).
     */
    public fun renderBmMapOnBg2() {
        hardware.setTileDataOffset(BG2, TILESET_OFFSET)
        renderWindow(hardware.mapBuffer(BG2))
        hardware.enableBgSyncByMask(BG2_SYNC_BIT)
        hardware.lcd.setBgPosition(BG2, 0, 0)
    }

    /** Constants of the visible window. */
    public companion object {
        /** `BG_2`. */
        public const val BG2: Int = 2

        /** `BG_3`. */
        public const val BG3: Int = 3

        /** `BG2_SYNC_BIT`. */
        public const val BG2_SYNC_BIT: Int = 1 shl 2

        /** `BG3_SYNC_BIT`. */
        public const val BG3_SYNC_BIT: Int = 1 shl 3

        /** Metatile columns drawn by [BmMapRender.renderBmMap]. */
        public const val VISIBLE_COLUMNS: Int = 15

        /** Metatile rows drawn by [BmMapRender.renderBmMap]. */
        public const val VISIBLE_ROWS: Int = 10

        /** BG tile data offset of the chapter tileset. */
        public const val TILESET_OFFSET: Int = 0x8000
    }
}
