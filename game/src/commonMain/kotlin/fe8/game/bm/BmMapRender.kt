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

    /**
     * Stand-in for `DisplayMovementViewTile` (bmmap.c:342), called by the scroll renderers while
     * `gBmSt.gameStateBits & 1` is set. Not ported; no trace reaches it.
     */
    public fun interface MovementViewTile {
        /**
         * Draws the range tile of map tile ([xBmMap], [yBmMap]) at ([xTileMap], [yTileMap]) of
         * [bg].
         */
        public fun display(bg: ShortArray, xBmMap: Int, yBmMap: Int, xTileMap: Int, yTileMap: Int)
    }

    /** The `DisplayMovementViewTile` stand-in, or null to draw nothing on BG2. */
    public var movementView: MovementViewTile? = null

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

    /**
     * Ported from src/bmmap.c: UpdateBmMapDisplay
     *
     * Redraws the column / row that scrolled across a 16 pixel boundary since
     * `gBmSt.cameraPrevious`, stores the camera as the new `cameraPrevious` and sets the BG3 scroll
     * (and the BG2 scroll while `gameStateBits & 1`) relative to `mapRenderOrigin`.
     */
    public fun updateBmMapDisplay() {
        val st = bmSt
        if (st.cameraX != st.cameraPreviousX) {
            if (st.cameraX > st.cameraPreviousX) {
                if (((st.cameraX - 1) xor (st.cameraPreviousX - 1)) and 0x10 != 0) {
                    renderBmMapColumn(VISIBLE_COLUMNS)
                }
            } else if ((st.cameraX xor st.cameraPreviousX) and 0x10 != 0) {
                renderBmMapColumn(0)
            }
        }

        if (st.cameraY != st.cameraPreviousY) {
            if (st.cameraY > st.cameraPreviousY) {
                if (((st.cameraY - 1) xor (st.cameraPreviousY - 1)) and 0x10 != 0) {
                    renderBmMapLine(VISIBLE_ROWS)
                }
            } else if ((st.cameraY xor st.cameraPreviousY) and 0x10 != 0) {
                renderBmMapLine(0)
            }
        }

        st.cameraPreviousX = st.cameraX
        st.cameraPreviousY = st.cameraY

        val x = st.cameraX - st.mapRenderOriginX * 16
        val y = st.cameraY - st.mapRenderOriginY * 16
        hardware.lcd.setBgPosition(BG3, x, y)
        if (st.gameStateBits and 1 != 0) {
            hardware.lcd.setBgPosition(BG2, x, y)
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
        val st = bmSt
        val bg3 = hardware.mapBuffer(BG3)
        val xBmMap = ((st.cameraX shr 4) + xOffset) and 0xFFFF
        val yBmMap = (st.cameraY shr 4) and 0xFFFF
        val xTileMap = ((st.cameraX shr 4) - st.mapRenderOriginX + xOffset) and 0xF
        val yTileMap = ((st.cameraY shr 4) - st.mapRenderOriginY) and 0xFFFF
        val view = if (st.gameStateBits and 1 != 0) movementView else null
        for (iy in 10 downTo 0) {
            val ty = (yTileMap + iy) and 0xF
            displayBmTile(bg3, xTileMap, ty, xBmMap, (yBmMap + iy) and 0xFFFF)
            view?.display(hardware.mapBuffer(BG2), xBmMap, (yBmMap + iy) and 0xFFFF, xTileMap, ty)
        }
        hardware.enableBgSyncByMask(
            if (st.gameStateBits and 1 != 0) BG2_SYNC_BIT or BG3_SYNC_BIT else BG3_SYNC_BIT,
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
        val st = bmSt
        val bg3 = hardware.mapBuffer(BG3)
        val xBmMap = (st.cameraX shr 4) and 0xFFFF
        val yBmMap = ((st.cameraY shr 4) + yOffset) and 0xFFFF
        val xTileMap = ((st.cameraX shr 4) - st.mapRenderOriginX) and 0xFFFF
        val yTileMap = ((st.cameraY shr 4) - st.mapRenderOriginY + yOffset) and 0xF
        val view = if (st.gameStateBits and 1 != 0) movementView else null
        for (ix in 15 downTo 0) {
            val tx = (xTileMap + ix) and 0xF
            displayBmTile(bg3, tx, yTileMap, (xBmMap + ix) and 0xFFFF, yBmMap)
            view?.display(hardware.mapBuffer(BG2), (xBmMap + ix) and 0xFFFF, yBmMap, tx, yTileMap)
        }
        hardware.enableBgSyncByMask(
            if (st.gameStateBits and 1 != 0) BG2_SYNC_BIT or BG3_SYNC_BIT else BG3_SYNC_BIT,
        )
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
