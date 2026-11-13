package fe8.game.ui

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * The window frame that surrounds menus and popups (decomp `src/uiutils.c`): the border graphics
 * and their palette, and the routines that draw a bordered box into a BG tilemap. Used by the
 * popup banner, the item panel beside the action menu, the shop, the trade screens and the prep
 * screens. A frame is built from a 16-entry model of tile references (4 by 4 tiles: the 2x2 top
 * left corner with the top edge, the interior pattern, the edges and the bottom row), all in
 * palette bank 1 (`BGPAL_WINDOW_FRAME`). Also holds the item hover bar (the highlight under the
 * selected item in the trade screen) and BG 0/1 clearing.
 */
public object UiFrame {
    /**
     * ROM address of `sUiFrameModelTilemapLookup` (4 pointers to 16-entry tile reference
     * models: the frame styles 0 to 3). The decomp's models are named `gUnknown_080DA2F4`
     * (style 0), `gUnknown_080DA334` (1), `gUnknown_080DA354` (2) and `gUnknown_080DA314`
     * (3); what distinguishes them in play is not recorded in the decomp.
     */
    public const val UI_FRAME_MODEL_LOOKUP: Int = 0x085B6430

    /**
     * ROM address of `sUiFramePaletteLookup` (4 palette pointers: `gUiFramePaletteA` to `D`, one
     * per window colour the player can choose in the config screen, `gPlaySt.config.windowColor`).
     */
    public const val UI_FRAME_PALETTE_LOOKUP: Int = 0x085B6450

    /**
     * ROM address of `sUiFrameImageLookup` (4 LZ77 image pointers). In the decomp all four
     * entries point to the same `gUiFrameImage`; only the palette changes with the window colour.
     */
    public const val UI_FRAME_IMAGE_LOOKUP: Int = 0x085B6470

    /** `BGPAL_WINDOW_FRAME`: the BG palette bank (1) the frame tiles and their palette use. */
    public const val BGPAL_WINDOW_FRAME: Int = 1

    /**
     * Loads the window frame graphics: decompresses the frame image to the start of BG tile VRAM
     * (`BGCHR_WINDOW_FRAME`, tile 0) and its 16-colour palette into palette bank
     * [BGPAL_WINDOW_FRAME]. A screen calls this when it sets up, before drawing frames.
     *
     * [windowColor] (0..3) is the player's window colour option (`gPlaySt.config.windowColor`);
     * the decomp reads it itself, here the caller passes it. It picks the palette.
     *
     * Ported from src/uiutils.c: LoadUiFrameGraphics
     */
    public fun loadUiFrameGraphics(hardware: BgHardware, rom: RomView, windowColor: Int = 0) {
        val imageAddr = rom.u32(UI_FRAME_IMAGE_LOOKUP + 4 * windowColor)
        rom.decompressInto(imageAddr, hardware.vram, 0)

        val palAddr = rom.u32(UI_FRAME_PALETTE_LOOKUP + 4 * windowColor)
        val palette = rom.pal(palAddr, 16)
        palette.copyInto(hardware.palette, BGPAL_WINDOW_FRAME * 16)
        hardware.enablePaletteSync()
    }

    /**
     * Blanks the BG 0 (text) and BG 1 (frame) tilemaps and flags both for upload; screens call it
     * to wipe the previous menu or window before drawing the next.
     *
     * Ported from src/uiutils.c: ClearBg0Bg1
     */
    public fun clearBg0Bg1(hardware: BgHardware) {
        hardware.fill(hardware.mapBuffer(0), 0)
        hardware.fill(hardware.mapBuffer(1), 0)

        hardware.enableBgSyncByMask(3)
    }

    /**
     * Draws a window frame (as seen on popups, the shop and trade windows and the item panel)
     * into [bg1] and blanks the same tiles of [bg0], so text of an earlier window does not show
     * through the new frame. The frame's top-left tile is ([x], [y]) and it is [width] by
     * [height] tiles, border included. [style] (0..3) picks a tile model of
     * `sUiFrameModelTilemapLookup`. Resets the scroll of BG 0 and BG 1 to (0, 0) and flags both
     * for upload, when [hardware] is given (the decomp always does).
     *
     * Ported from src/uiutils.c: DrawUiFrame2
     */
    public fun drawUiFrame2(
        bg0: ShortArray,
        bg1: ShortArray,
        rom: RomView,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        style: Int = 0,
        hardware: BgHardware? = null,
    ) {
        val modelOffset = rom.u32(UI_FRAME_MODEL_LOOKUP + 4 * style)
        val metatiles = ShortArray(16) { rom.u16(modelOffset + 2 * it).toShort() }

        // Last tile column and row of the frame (`xMax`, `yMax`), border included.
        val xMax = x + width - 1
        val yMax = y + height - 1

        if (style != 3) {
            for (iy in y + 1 until yMax step 2) {
                for (ix in x + 1 until xMax step 2) {
                    var offset = iy * 32 + ix
                    bg0[offset] = 0
                    bg1[offset] = metatiles[6]

                    offset += 1
                    bg0[offset] = 0
                    bg1[offset] = metatiles[6]

                    offset += 31
                    bg0[offset] = 0
                    bg1[offset] = metatiles[9]

                    offset += 1
                    bg0[offset] = 0
                    bg1[offset] = metatiles[10]
                }

                bg0[iy * 32 + x] = 0
                bg1[iy * 32 + x] = metatiles[8]

                bg0[iy * 32 + xMax] = 0
                bg1[iy * 32 + xMax] = metatiles[7]

                bg0[(iy + 1) * 32 + x] = 0
                bg1[(iy + 1) * 32 + x] = metatiles[8]

                bg0[(iy + 1) * 32 + xMax] = 0
                bg1[(iy + 1) * 32 + xMax] = metatiles[11]
            }

            for (ix in x + 1 until xMax step 2) {
                bg0[y * 32 + ix] = 0
                bg1[y * 32 + ix] = metatiles[2]

                bg0[y * 32 + ix + 1] = 0
                bg1[y * 32 + ix + 1] = metatiles[2]

                bg0[yMax * 32 + ix] = 0
                bg1[yMax * 32 + ix] = metatiles[13]

                bg0[yMax * 32 + ix + 1] = 0
                bg1[yMax * 32 + ix + 1] = metatiles[14]
            }

            bg0[y * 32 + x] = 0
            bg0[y * 32 + xMax] = 0
            bg0[yMax * 32 + x] = 0
            bg0[yMax * 32 + xMax] = 0

            bg1[y * 32 + x] = metatiles[0]
            bg1[y * 32 + x + 1] = metatiles[1]
            bg1[(y + 1) * 32 + x] = metatiles[4]
            bg1[(y + 1) * 32 + x + 1] = metatiles[5]

            bg1[y * 32 + xMax] = metatiles[3]
            bg1[yMax * 32 + x] = metatiles[12]
            bg1[yMax * 32 + xMax] = metatiles[15]
        } else {
            // Style 3 does not write the frame's top row (y) at all, so the caller or an earlier
            // draw provides it. Its first interior row (y + 1) uses model entries 4..7, the
            // rows below use 8..11 (and 5, 6 become 9, 10), as in the C `r9` flag.
            for (iy in y + 1 until yMax step 2) {
                val isFirstRow = (iy == y + 1)
                for (ix in x + 1 until xMax step 2) {
                    var offset = iy * 32 + ix
                    bg0[offset] = 0
                    bg1[offset] = metatiles[if (isFirstRow) 5 else 9]

                    offset += 1
                    bg0[offset] = 0
                    bg1[offset] = metatiles[if (isFirstRow) 6 else 10]

                    offset += 31
                    bg0[offset] = 0
                    bg1[offset] = metatiles[9]

                    offset += 1
                    bg0[offset] = 0
                    bg1[offset] = metatiles[10]
                }

                bg0[iy * 32 + x] = 0
                bg1[iy * 32 + x] = metatiles[if (isFirstRow) 4 else 8]

                bg0[iy * 32 + xMax] = 0
                bg1[iy * 32 + xMax] = metatiles[if (isFirstRow) 7 else 11]

                bg0[(iy + 1) * 32 + x] = 0
                bg1[(iy + 1) * 32 + x] = metatiles[8]

                bg0[(iy + 1) * 32 + xMax] = 0
                bg1[(iy + 1) * 32 + xMax] = metatiles[11]
            }

            for (ix in x + 1 until xMax step 2) {
                bg0[yMax * 32 + ix] = 0
                bg1[yMax * 32 + ix] = metatiles[13]

                bg0[yMax * 32 + ix + 1] = 0
                bg1[yMax * 32 + ix + 1] = metatiles[14]
            }

            bg0[yMax * 32 + x] = 0
            bg0[yMax * 32 + xMax] = 0

            bg1[yMax * 32 + x] = metatiles[12]
            bg1[yMax * 32 + xMax] = metatiles[15]
        }

        if (hardware != null) {
            hardware.lcd.setBgPosition(0, 0, 0)
            hardware.lcd.setBgPosition(1, 0, 0)
            hardware.enableBgSyncByMask(3)
        }
    }

    /**
     * Draws a window frame into [hardware]'s BG 0 (blanked) and BG 1 (frame) tilemap buffers,
     * as the decomp's `DrawUiFrame2(x, y, width, height, style)` does with `gBG0TilemapBuffer`
     * and `gBG1TilemapBuffer`. Arguments are as for the overload above; [style] defaults to 0
     * (the style of the shop, trade and item panel frames; the popup passes its window style).
     */
    public fun drawUiFrame2(
        hardware: BgHardware,
        rom: RomView,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        style: Int = 0,
    ) {
        drawUiFrame2(
            hardware.mapBuffer(0),
            hardware.mapBuffer(1),
            rom,
            x,
            y,
            width,
            height,
            style,
            hardware,
        )
    }

    /**
     * ROM address of `gUnknown_080DA374`, a 16-entry tile model like the frame ones. Entries 6
     * and 7 (both the plain frame background tile 0x09) are written back when the item hover bar
     * is cleared; the other entries are not used by this class.
     */
    public const val HOVER_CLEAR_TILES: Int = 0x080DA374

    /**
     * `TILEREF(0x7A..0x7C, BGPAL_WINDOW_FRAME)` (tile number plus bank 1 shifted by 12): left,
     * middle and right tile of the hover bar.
     */
    private const val HOVER_LEFT: Int = 0x107A
    private const val HOVER_MIDDLE: Int = 0x107B
    private const val HOVER_RIGHT: Int = 0x107C

    /**
     * Draws a window frame into the 32 by 32 [tilemap] with its top-left tile at ([x], [y]),
     * [width] by [height] tiles, border included, without touching BG 0 or the scroll (unlike
     * [drawUiFrame2]). [style] (0..3) picks the tile model and [tilebase] is added to every
     * tile entry (for a frame drawn on another BG whose tile VRAM or palette differs). The class
     * change screen uses it for its small frame on BG 2.
     *
     * Ported from src/uiutils.c: DrawUiFrame
     */
    public fun drawUiFrame(
        tilemap: ShortArray,
        rom: RomView,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        tilebase: Int,
        style: Int,
    ) {
        val model = rom.u32(UI_FRAME_MODEL_LOOKUP + 4 * style)
        val xMax = x + width - 1
        val yMax = y + height - 1

        var iy = y + 1
        while (iy < yMax) {
            var ix = x + 1
            while (ix < xMax) {
                val offset = iy * 32 + ix
                tilemap[offset] = (rom.u16(model + 2 * 6) + tilebase).toShort()
                tilemap[offset + 1] = (rom.u16(model + 2 * 6) + tilebase).toShort()
                tilemap[offset + 32] = (rom.u16(model + 2 * 9) + tilebase).toShort()
                tilemap[offset + 33] = (rom.u16(model + 2 * 10) + tilebase).toShort()
                ix += 2
            }

            tilemap[iy * 32 + x] = (rom.u16(model + 2 * 8) + tilebase).toShort()
            tilemap[iy * 32 + xMax] = (rom.u16(model + 2 * 7) + tilebase).toShort()
            tilemap[(iy + 1) * 32 + x] = (rom.u16(model + 2 * 8) + tilebase).toShort()
            tilemap[(iy + 1) * 32 + xMax] = (rom.u16(model + 2 * 11) + tilebase).toShort()
            iy += 2
        }

        var ix = x + 1
        while (ix < xMax) {
            tilemap[y * 32 + ix] = (rom.u16(model + 2 * 2) + tilebase).toShort()
            tilemap[y * 32 + ix + 1] = (rom.u16(model + 2 * 2) + tilebase).toShort()
            tilemap[yMax * 32 + ix] = (rom.u16(model + 2 * 13) + tilebase).toShort()
            tilemap[yMax * 32 + ix + 1] = (rom.u16(model + 2 * 14) + tilebase).toShort()
            ix += 2
        }

        tilemap[y * 32 + x] = (rom.u16(model) + tilebase).toShort()
        tilemap[y * 32 + x + 1] = (rom.u16(model + 2 * 1) + tilebase).toShort()
        tilemap[(y + 1) * 32 + x] = (rom.u16(model + 2 * 4) + tilebase).toShort()
        tilemap[(y + 1) * 32 + x + 1] = (rom.u16(model + 2 * 5) + tilebase).toShort()

        tilemap[y * 32 + xMax] = (rom.u16(model + 2 * 3) + tilebase).toShort()
        tilemap[yMax * 32 + x] = (rom.u16(model + 2 * 12) + tilebase).toShort()
        tilemap[yMax * 32 + xMax] = (rom.u16(model + 2 * 15) + tilebase).toShort()
    }

    /**
     * Blanks the [width] by [height] tile rectangle of [tilemap] whose top-left tile is
     * ([x], [y]), to erase a frame drawn by [drawUiFrame].
     *
     * Ported from src/uiutils.c: ClearUiFrame
     */
    public fun clearUiFrame(tilemap: ShortArray, x: Int, y: Int, width: Int, height: Int) {
        for (row in 0 until height) {
            val start = (y + row) * 32 + x
            for (col in 0 until width) tilemap[start + col] = 0
        }
    }

    /**
     * Draws the item hover bar: a highlight [width] tiles wide on tile row [y] + 1 of BG [bg],
     * from column [x] (left cap, middle tiles, right cap); [base] is added to each tile entry.
     * The trade screen shows it under the item the cursor is on (the decomp's callers use the
     * BG 1 version, `DrawUiItemHover`, with width 12). Flags the BG for upload.
     *
     * Ported from src/uiutils.c: DrawUiItemHoverExt
     */
    public fun drawUiItemHoverExt(
        hardware: BgHardware,
        bg: Int,
        base: Int,
        x: Int,
        y: Int,
        width: Int,
    ) {
        val xMax = x + width - 1
        val row = (y + 1) * 32
        val tilemap = hardware.mapBuffer(bg)

        tilemap[row + x] = (HOVER_LEFT + base).toShort()
        var ix = x + 1
        while (ix < xMax) {
            tilemap[row + ix] = (HOVER_MIDDLE + base).toShort()
            ix++
        }
        tilemap[row + ix] = (HOVER_RIGHT + base).toShort()

        hardware.enableBgSyncByMask(1 shl bg)
    }

    /**
     * Removes the item hover bar drawn by [drawUiItemHoverExt] (same arguments) by writing the
     * plain frame background tile back, alternating the model's entries 6 and 7 by column; an
     * even [width] ends on entry 7, an odd one on entry 6. Flags the BG for upload.
     *
     * Ported from src/uiutils.c: ClearUiItemHoverExt
     */
    public fun clearUiItemHoverExt(
        hardware: BgHardware,
        rom: RomView,
        bg: Int,
        base: Int,
        x: Int,
        y: Int,
        width: Int,
    ) {
        val xMax = x + width - 1
        val row = (y + 1) * 32
        val tilemap = hardware.mapBuffer(bg)
        val even = rom.u16(HOVER_CLEAR_TILES + 2 * 6) + base
        val odd = rom.u16(HOVER_CLEAR_TILES + 2 * 7) + base

        var ix = x
        while (ix < xMax) {
            tilemap[row + ix] = even.toShort()
            tilemap[row + ix + 1] = odd.toShort()
            ix += 2
        }
        tilemap[row + xMax] = (if (width % 2 != 0) even else odd).toShort()

        hardware.enableBgSyncByMask(1 shl bg)
    }
}
