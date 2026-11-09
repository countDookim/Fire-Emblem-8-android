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
     * Draws a dialogue or popup frame into [bg0] (cleared to 0) and [bg1] (frame metatiles from [style]).
     * Sets BG0/BG1 positions to (0, 0) and enables sync on [hardware] if provided.
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
        val model = ShortArray(16) { rom.u16(modelOffset + 2 * it).toShort() }

        val xMax = x + width - 1
        val yMax = y + height - 1

        if (style != 3) {
            for (iy in y + 1 until yMax step 2) {
                for (ix in x + 1 until xMax step 2) {
                    var offset = iy * 32 + ix
                    bg0[offset] = 0
                    bg1[offset] = model[6]

                    offset += 1
                    bg0[offset] = 0
                    bg1[offset] = model[6]

                    offset += 31
                    bg0[offset] = 0
                    bg1[offset] = model[9]

                    offset += 1
                    bg0[offset] = 0
                    bg1[offset] = model[10]
                }

                bg0[iy * 32 + x] = 0
                bg1[iy * 32 + x] = model[8]

                bg0[iy * 32 + xMax] = 0
                bg1[iy * 32 + xMax] = model[7]

                bg0[(iy + 1) * 32 + x] = 0
                bg1[(iy + 1) * 32 + x] = model[8]

                bg0[(iy + 1) * 32 + xMax] = 0
                bg1[(iy + 1) * 32 + xMax] = model[11]
            }

            for (ix in x + 1 until xMax step 2) {
                bg0[y * 32 + ix] = 0
                bg1[y * 32 + ix] = model[2]

                bg0[y * 32 + ix + 1] = 0
                bg1[y * 32 + ix + 1] = model[2]

                bg0[yMax * 32 + ix] = 0
                bg1[yMax * 32 + ix] = model[13]

                bg0[yMax * 32 + ix + 1] = 0
                bg1[yMax * 32 + ix + 1] = model[14]
            }

            bg0[y * 32 + x] = 0
            bg0[y * 32 + xMax] = 0
            bg0[yMax * 32 + x] = 0
            bg0[yMax * 32 + xMax] = 0

            bg1[y * 32 + x] = model[0]
            bg1[y * 32 + x + 1] = model[1]
            bg1[(y + 1) * 32 + x] = model[4]
            bg1[(y + 1) * 32 + x + 1] = model[5]

            bg1[y * 32 + xMax] = model[3]
            bg1[yMax * 32 + x] = model[12]
            bg1[yMax * 32 + xMax] = model[15]
        } else {
            for (iy in y + 1 until yMax step 2) {
                val r9 = (iy == y + 1)
                for (ix in x + 1 until xMax step 2) {
                    var offset = iy * 32 + ix
                    bg0[offset] = 0
                    bg1[offset] = model[if (r9) 5 else 9]

                    offset += 1
                    bg0[offset] = 0
                    bg1[offset] = model[if (r9) 6 else 10]

                    offset += 31
                    bg0[offset] = 0
                    bg1[offset] = model[9]

                    offset += 1
                    bg0[offset] = 0
                    bg1[offset] = model[10]
                }

                bg0[iy * 32 + x] = 0
                bg1[iy * 32 + x] = model[if (r9) 4 else 8]

                bg0[iy * 32 + xMax] = 0
                bg1[iy * 32 + xMax] = model[if (r9) 7 else 11]

                bg0[(iy + 1) * 32 + x] = 0
                bg1[(iy + 1) * 32 + x] = model[8]

                bg0[(iy + 1) * 32 + xMax] = 0
                bg1[(iy + 1) * 32 + xMax] = model[11]
            }

            for (ix in x + 1 until xMax step 2) {
                bg0[yMax * 32 + ix] = 0
                bg1[yMax * 32 + ix] = model[13]

                bg0[yMax * 32 + ix + 1] = 0
                bg1[yMax * 32 + ix + 1] = model[14]
            }

            bg0[yMax * 32 + x] = 0
            bg0[yMax * 32 + xMax] = 0

            bg1[yMax * 32 + x] = model[12]
            bg1[yMax * 32 + xMax] = model[15]
        }

        if (hardware != null) {
            hardware.lcd.setBgPosition(0, 0, 0)
            hardware.lcd.setBgPosition(1, 0, 0)
            hardware.enableBgSyncByMask(3)
        }
    }

    /**
     * Draws a dialogue or popup frame into [hardware]'s BG0 (cleared) and BG1 (frame).
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
}
