package fe8.game.ui

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * UI window frame rendering and graphics loading (decomp `src/uiutils.c`).
 */
public object UiFrame {
    /** ROM address of `sUiFrameModelTilemapLookup` (4 model pointers). */
    public const val UI_FRAME_MODEL_LOOKUP: Int = 0x085B6430

    /** ROM address of `sUiFramePaletteLookup` (4 palette pointers). */
    public const val UI_FRAME_PALETTE_LOOKUP: Int = 0x085B6450

    /** ROM address of `sUiFrameImageLookup` (4 LZ77 image pointers). */
    public const val UI_FRAME_IMAGE_LOOKUP: Int = 0x085B6470

    /** Palette bank index used for UI window frames (`BGPAL_WINDOW_FRAME`). */
    public const val BGPAL_WINDOW_FRAME: Int = 1

    /**
     * Unpacks the window frame image into BG tile VRAM (offset 0) and its 16-colour palette into
     * `gPaletteBuffer` at [BGPAL_WINDOW_FRAME] (palette 1).
     *
     * [windowColor] (0..3) picks which of the four frame graphics to load.
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
     * Clears the BG 0 and BG 1 tilemaps and flags both for sync.
     *
     * Ported from src/uiutils.c: ClearBg0Bg1
     */
    public fun clearBg0Bg1(hardware: BgHardware) {
        hardware.fill(hardware.mapBuffer(0), 0)
        hardware.fill(hardware.mapBuffer(1), 0)

        hardware.enableBgSyncByMask(3)
    }

    /**
     * Draws a dialogue or popup frame into [bg0] (cleared to 0) and [bg1] (frame metatiles from
     * [style]). The frame's top-left tile is ([x], [y]) and it is [width] by [height] tiles,
     * border included. [style] (0..3) picks one of the four metatile sets of
     * `sUiFrameModelTilemapLookup`. Sets BG0/BG1 positions to (0, 0) and enables sync on
     * [hardware] if provided.
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

        // Last tile column and row of the frame, border included.
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
            // Style 3 has no top border: its first interior row uses metatiles 4..7,
            // the rows below it use 8..11.
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
     * Draws a dialogue or popup frame into [hardware]'s BG0 (cleared) and BG1 (frame), using
     * the hardware's map buffers 0 and 1. Arguments are as for the overload above; [style]
     * defaults to 0.
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

    /** ROM address of `gUnknown_080DA374`, the resting metatiles of the item hover bar. */
    public const val HOVER_CLEAR_TILES: Int = 0x080DA374

    /** `TILEREF(0x7A..0x7C, BGPAL_WINDOW_FRAME)`: left, middle and right tile of the hover bar. */
    private const val HOVER_LEFT: Int = 0x107A
    private const val HOVER_MIDDLE: Int = 0x107B
    private const val HOVER_RIGHT: Int = 0x107C

    /**
     * Draws a window frame into the 32 by 32 [tilemap] with its top-left tile at ([x], [y]),
     * [width] by [height] tiles, border included. [style] (0..3) picks the metatile set and
     * [tilebase] is added to every tile entry.
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
     * Zeroes the [width] by [height] tile rectangle of [tilemap] whose top-left tile is
     * ([x], [y]).
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
     * Draws the item hover bar, [width] tiles wide, on the tile row below [y] of BG [bg], starting
     * at column [x]; [base] is added to each tile entry. Enables the BG's sync.
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
     * Restores the plain window tiles under the item hover bar drawn by [drawUiItemHoverExt] (the
     * arguments are the same). Enables the BG's sync.
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
