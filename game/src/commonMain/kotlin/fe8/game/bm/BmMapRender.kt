package fe8.game.bm

import gbafe.render.BgHardware

/**
 * Battle map tile rendering of src/bmmap.c: [displayBmTile] writes one 2x2 metatile into a BG
 * tilemap buffer, [renderBmMap] and [renderBmMapOnBg2] redraw the whole 15x10 visible window
 * into `gBG3TilemapBuffer` / `gBG2TilemapBuffer` of [hardware]. Nothing here allocates.
 *
 * [updateBmMapDisplay] with [renderBmMapColumn] and [renderBmMapLine] is the incremental redraw of
 * BG3 when the camera scrolls: it draws the metatile column or row that came into view into the
 * 32x32 ring buffer (wrapped with `& 0xF` metatiles around `mapRenderOrigin`) and moves the BG3
 * scroll. `DisplayMovementViewTile` (the BG2 range overlay, `gameStateBits & 1`) is not ported:
 * [movementView] stands in for it and is null unless something sets it.
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
        val destBufferIndex = yTileMap * 0x40 + xTileMap * 2
        // `sTilesetConfig + tile` is a u16 pointer: the base tile is a halfword index.
        val config = map.tilesetConfig
        val tile = map.baseTile(xBmMap, yBmMap) * 2
        val paletteBase = if (map.fog[yBmMap, xBmMap] != 0) 6 shl 12 else 11 shl 12
        bg[destBufferIndex] = (paletteBase + half(config, tile)).toShort()
        bg[destBufferIndex + 1] = (paletteBase + half(config, tile + 2)).toShort()
        bg[destBufferIndex + 0x20] = (paletteBase + half(config, tile + 4)).toShort()
        bg[destBufferIndex + 0x21] = (paletteBase + half(config, tile + 6)).toShort()
    }

    /**
     * Stand-in until M6-B for `DisplayMovementViewTile` (bmmap.c:342), called by the scroll
     * renderers while `gBmSt.gameStateBits & 1` is set. Not ported; no trace reaches it.
     */
    public fun interface MovementViewTile {
        /**
         * Draws the range tile of map tile ([xBmMap], [yBmMap]) at ([xTileMap], [yTileMap]) of
         * [bg].
         */
        public fun display(bg: ShortArray, xBmMap: Int, yBmMap: Int, xTileMap: Int, yTileMap: Int)
    }

    /** The `DisplayMovementViewTile` stand-in (until M6-B), or null to draw nothing on BG2. */
    public var movementView: MovementViewTile? = null

    /** Reads an unsigned little-endian 16-bit halfword from [bytes] at [offset]. */
    private fun half(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** Renders the full visible 15x10 metatile window into [tilemapBuffer]. */
    private fun renderWindow(tilemapBuffer: ShortArray) {
        bmSt.mapRenderOriginX = (bmSt.cameraX shr 4) and 0xFFFF
        bmSt.mapRenderOriginY = (bmSt.cameraY shr 4) and 0xFFFF
        val originX = bmSt.mapRenderOriginX.toShort().toInt()
        val originY = bmSt.mapRenderOriginY.toShort().toInt()
        for (tileY in VISIBLE_ROWS - 1 downTo 0) {
            for (tileX in VISIBLE_COLUMNS - 1 downTo 0) {
                displayBmTile(tilemapBuffer, tileX, tileY, originX + tileX, originY + tileY)
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

    /**
     * Ported from src/bmmap.c: UpdateBmMapDisplay
     *
     * Redraws the column / row that scrolled across a 16 pixel boundary since
     * `gBmSt.cameraPrevious`, stores the camera as the new `cameraPrevious` and sets the BG3 scroll
     * (and the BG2 scroll while `gameStateBits & 1`) relative to `mapRenderOrigin`.
     */
    public fun updateBmMapDisplay() {
        val bm = bmSt
        if (bm.cameraX != bm.cameraPreviousX) {
            if (bm.cameraX > bm.cameraPreviousX) {
                if (((bm.cameraX - 1) xor (bm.cameraPreviousX - 1)) and 0x10 != 0) {
                    renderBmMapColumn(VISIBLE_COLUMNS)
                }
            } else if ((bm.cameraX xor bm.cameraPreviousX) and 0x10 != 0) {
                renderBmMapColumn(0)
            }
        }

        if (bm.cameraY != bm.cameraPreviousY) {
            if (bm.cameraY > bm.cameraPreviousY) {
                if (((bm.cameraY - 1) xor (bm.cameraPreviousY - 1)) and 0x10 != 0) {
                    renderBmMapLine(VISIBLE_ROWS)
                }
            } else if ((bm.cameraY xor bm.cameraPreviousY) and 0x10 != 0) {
                renderBmMapLine(0)
            }
        }

        bm.cameraPreviousX = bm.cameraX
        bm.cameraPreviousY = bm.cameraY

        val scrollX = bm.cameraX - bm.mapRenderOriginX * 16
        val scrollY = bm.cameraY - bm.mapRenderOriginY * 16
        hardware.lcd.setBgPosition(BG3, scrollX, scrollY)
        if (bm.gameStateBits and 1 != 0) {
            hardware.lcd.setBgPosition(BG2, scrollX, scrollY)
        }
    }

    /**
     * Ported from src/bmmap.c: RenderBmMapColumn
     *
     * Draws the 11 metatiles of the map column at camera column + [xOffset] into the BG3 column
     * `(cameraColumn - origin + xOffset) & 0xF`, rows wrapped with `& 0xF`, and requests the BG3
     * sync (BG2 as well while `gameStateBits & 1`).
     */
    public fun renderBmMapColumn(xOffset: Int) {
        val bm = bmSt
        val bg3Buffer = hardware.mapBuffer(BG3)
        val xBmMap = ((bm.cameraX shr 4) + xOffset) and 0xFFFF
        val yBmMap = (bm.cameraY shr 4) and 0xFFFF
        val xTileMap = ((bm.cameraX shr 4) - bm.mapRenderOriginX + xOffset) and 0xF
        val yTileMap = ((bm.cameraY shr 4) - bm.mapRenderOriginY) and 0xFFFF
        val movementOverlay = if (bm.gameStateBits and 1 != 0) movementView else null
        for (rowOffset in 10 downTo 0) {
            val wrappedTileY = (yTileMap + rowOffset) and 0xF
            displayBmTile(bg3Buffer, xTileMap, wrappedTileY, xBmMap, (yBmMap + rowOffset) and 0xFFFF)
            movementOverlay?.display(hardware.mapBuffer(BG2), xBmMap, (yBmMap + rowOffset) and 0xFFFF, xTileMap, wrappedTileY)
        }
        hardware.enableBgSyncByMask(
            if (bm.gameStateBits and 1 != 0) BG2_SYNC_BIT or BG3_SYNC_BIT else BG3_SYNC_BIT,
        )
    }

    /**
     * Ported from src/bmmap.c: RenderBmMapLine
     *
     * Draws the 16 metatiles of the map row at camera row + [yOffset] into the BG3 row
     * `(cameraRow - origin + yOffset) & 0xF`, columns wrapped with `& 0xF`, and requests the BG3
     * sync (BG2 as well while `gameStateBits & 1`).
     */
    public fun renderBmMapLine(yOffset: Int) {
        val bm = bmSt
        val bg3Buffer = hardware.mapBuffer(BG3)
        val xBmMap = (bm.cameraX shr 4) and 0xFFFF
        val yBmMap = ((bm.cameraY shr 4) + yOffset) and 0xFFFF
        val xTileMap = ((bm.cameraX shr 4) - bm.mapRenderOriginX) and 0xFFFF
        val yTileMap = ((bm.cameraY shr 4) - bm.mapRenderOriginY + yOffset) and 0xF
        val movementOverlay = if (bm.gameStateBits and 1 != 0) movementView else null
        for (colOffset in 15 downTo 0) {
            val wrappedTileX = (xTileMap + colOffset) and 0xF
            displayBmTile(bg3Buffer, wrappedTileX, yTileMap, (xBmMap + colOffset) and 0xFFFF, yBmMap)
            movementOverlay?.display(hardware.mapBuffer(BG2), (xBmMap + colOffset) and 0xFFFF, yBmMap, wrappedTileX, yTileMap)
        }
        hardware.enableBgSyncByMask(
            if (bm.gameStateBits and 1 != 0) BG2_SYNC_BIT or BG3_SYNC_BIT else BG3_SYNC_BIT,
        )
    }

    /** Constants of the visible window. */
    public companion object {
        /** `BG_2`: background layer index 2 (used for range display overlay). */
        public const val BG2: Int = 2

        /** `BG_3`: background layer index 3 (used for map tiles). */
        public const val BG3: Int = 3

        /** `BG2_SYNC_BIT`: VBlank sync bitmask for BG2 (1 shl 2). */
        public const val BG2_SYNC_BIT: Int = 1 shl 2

        /** `BG3_SYNC_BIT`: VBlank sync bitmask for BG3 (1 shl 3). */
        public const val BG3_SYNC_BIT: Int = 1 shl 3

        /** Metatile columns drawn by [BmMapRender.renderBmMap] (15 metatiles = 240 pixels). */
        public const val VISIBLE_COLUMNS: Int = 15

        /** Metatile rows drawn by [BmMapRender.renderBmMap] (10 metatiles = 160 pixels). */
        public const val VISIBLE_ROWS: Int = 10

        /** BG tile data offset of the chapter tileset (0x8000). */
        public const val TILESET_OFFSET: Int = 0x8000
    }
}
