package fe8.game.bm

import gbafe.render.BgHardware

/**
 * Battle map tile rendering of src/bmmap.c: [displayBmTile] writes one 2x2 metatile into a BG
 * tilemap buffer, [renderBmMap] and [renderBmMapOnBg2] redraw the whole 15x10 visible window
 * into `gBG3TilemapBuffer` / `gBG2TilemapBuffer` of [hardware]. Nothing here allocates.
 *
 * The map is drawn on BG3 (the chapter map, with its tileset in the tileset area of BG VRAM; BG2
 * holds the move and attack range squares, or the map as it was before a change). The 15x10
 * metatile window is 240x160 pixels, but BG3 is a 32x32 tile (16x16 metatile) ring buffer:
 * [updateBmMapDisplay] with [renderBmMapColumn] and [renderBmMapLine] is the incremental redraw
 * when the camera scrolls (every frame, from the map display proc `MAPTASK`, src/bmio.c). It draws
 * the metatile column or row that came into view into the ring buffer (wrapped with `& 0xF`
 * metatiles around `mapRenderOrigin`) and moves the BG3 scroll by the camera's offset inside
 * the 16 pixel grid. One more column and row than the window is drawn (16x11), because between
 * grid lines the screen shows part of one more. `DisplayMovementViewTile` (the BG2 range
 * overlay, drawn while `gameStateBits & BM_FLAG_0`, bit 0) is not ported: [movementView] stands
 * in for it and is null unless something sets it.
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
     * [bg] at tile ([xTileMap], [yTileMap]) (`bg` has 0x20 entries per row, so the 2x2 block
     * starts at `yTileMap * 0x40 + xTileMap * 2`), with palette bank 6, or 11 where `gBmMapFog`
     * is 0 (fogged: the alternate map palette). The four entries are the metatile's four
     * halfwords in `sTilesetConfig`; adding the palette to them keeps their tile number.
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
     * renderers while `gBmSt.gameStateBits & 1` is set. In the C it writes the BG2 range overlay
     * for one map tile: a movement tile (palette 4, tiles 0x280..0x283) where `gBmMapMovement` is
     * reachable, a range tile (palette 5) where `gBmMapRange` is set, otherwise empty. Not
     * ported; no trace reaches it.
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

    /**
     * Sets `mapRenderOrigin` to the camera's metatile and draws the 15x10 visible metatile
     * window into [tilemapBuffer] (the shared part of `RenderBmMap` and `RenderBmMapOnBg2`).
     */
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
     * Redraws the whole visible map from scratch: sets `gBmSt.mapRenderOrigin` from the camera,
     * draws the 15x10 visible tiles into `gBG3TilemapBuffer`, requests the BG3 sync, resets the
     * BG3 scroll and enables all layers (four BGs and sprites, `SetDispEnable(1, 1, 1, 1, 1)`).
     * Called when the whole map look changes at once, for example after a fog vision change
     * (`EventSetFogVisionExt`) or a map change, and when a chapter map is set up.
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
     * reset (the display enable is left alone). The callers (`src/bmunit.c`, `src/bmusailment.c`,
     * `src/eventscr_utils.c`) call it just before a fog or map change refreshes the maps and
     * redraws BG3, so BG2 keeps the map as it was before the change.
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
     * `gBmSt.cameraPrevious` (bit 4 of the camera differs: scrolling right or down draws the new
     * column 15 / row 10 off the right or bottom edge, left or up draws column / row 0; the
     * `- 1` on the increasing side makes the edge appear when the camera passes the grid line,
     * not before), stores the camera as the new `cameraPrevious` and sets the BG3 scroll (and
     * the BG2 scroll while `gameStateBits & 1`) to the camera minus `mapRenderOrigin` in
     * pixels. Runs every frame in the map display proc.
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
     * sync (BG2 as well while `gameStateBits & 1`). [xOffset] is 0 (the left edge) or 15 (just
     * past the right edge of the window); 11 rows because the window is 10 rows plus the partly
     * visible one.
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
     * sync (BG2 as well while `gameStateBits & 1`). [yOffset] is 0 (the top edge) or 10 (just
     * below the window); 16 columns because the window is 15 columns plus the partly visible
     * one.
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
        /** `BG_2` (hardware.h): background layer index 2 (the range display overlay). */
        public const val BG2: Int = 2

        /** `BG_3` (hardware.h): background layer index 3 (the map tiles). */
        public const val BG3: Int = 3

        /** `BG2_SYNC_BIT`: VBlank sync bitmask for BG2 (1 shl 2). */
        public const val BG2_SYNC_BIT: Int = 1 shl 2

        /** `BG3_SYNC_BIT`: VBlank sync bitmask for BG3 (1 shl 3). */
        public const val BG3_SYNC_BIT: Int = 1 shl 3

        /** Metatile columns drawn by `renderBmMap` (15 metatiles = 240 pixels, the screen). */
        public const val VISIBLE_COLUMNS: Int = 15

        /** Metatile rows drawn by `renderBmMap` (10 metatiles = 160 pixels, the screen). */
        public const val VISIBLE_ROWS: Int = 10

        /**
         * BG tile data offset (0x8000) `RenderBmMapOnBg2` gives BG2. `UnpackChapterMapGraphics`
         * unpacks the map tileset to `BG_VRAM + 0x20 * 0x400`, which is this offset, so BG2 then
         * draws from the map tileset.
         */
        public const val TILESET_OFFSET: Int = 0x8000
    }
}
