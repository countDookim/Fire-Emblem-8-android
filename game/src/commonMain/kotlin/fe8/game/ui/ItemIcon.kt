package fe8.game.ui

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * Item and UI icon loading functions (decomp `src/icon.c`).
 */
public object ItemIcon {
    /** ROM address of `item_icon_tiles` (224 4bpp icons, 128 bytes each). */
    public const val ITEM_ICON_TILES: Int = 0x085926F4

    /** ROM address of `item_icon_palette` (2 palettes of 16 BGR555 colours each). */
    public const val ITEM_ICON_PALETTE: Int = 0x085996F4

    /** Bytes per icon tile payload (16x16 pixels in 4bpp = 4 tiles = 128 bytes). */
    public const val ICON_BYTES: Int = 0x80

    /** Base offset of OBJ character VRAM in GBA VRAM (32 KiB into VRAM). */
    public const val OBJ_VRAM0: Int = 0x10000

    /** `MAX_ICON_COUNT`: entries of `DrawnIconLookupTable` (a 2-byte `struct IconStruct` each). */
    public const val MAX_ICON_COUNT: Int = 224

    /** `MAX_ICON_GFX_COUNT`: entries of `IconGFXIDLookupTable` (1 byte each). */
    public const val MAX_ICON_GFX_COUNT: Int = 32

    /**
     * Clears the icon bookkeeping: all of [drawnIconLookupTable] (`DrawnIconLookupTable`, the
     * `MAX_ICON_COUNT` 2-byte `References`/`Index` entries) and the first 0x20 bytes of
     * [iconGfxIdLookupTable] (`IconGFXIDLookupTable`).
     *
     * Ported from src/icon.c: ResetIconGraphics
     */
    public fun resetIconGraphics(drawnIconLookupTable: ByteArray, iconGfxIdLookupTable: ByteArray) {
        drawnIconLookupTable.fill(0, 0, MAX_ICON_COUNT * 2)
        iconGfxIdLookupTable.fill(0, 0, 0x20)
    }

    /**
     * Returns the base OBJ tile index allocated for dynamic icon slot [index].
     *
     * Transcribed from src/icon.c: GetIconGfxTileIndex (not verified: formula 0x300 - index * 4; LoadIconObjectGraphics is verified at the PopupProc_GfxDraw tile 0x240)
     */
    public fun getIconGfxTileIndex(index: Int): Int = 0x300 - index * 4

    /**
     * Loads 16 colours of icon palette [index] (0 or 1) into palette slot [dest] (0..31).
     *
     * Ported from src/icon.c: LoadIconPalette
     */
    public fun loadIconPalette(hardware: BgHardware, rom: RomView, index: Int, dest: Int) {
        val src = ITEM_ICON_PALETTE + index * 0x20
        val colors = rom.pal(src, 16)
        colors.copyInto(hardware.palette, dest * 16)
        hardware.enablePaletteSync()
    }

    /**
     * Loads the 2x2 4bpp sprite tiles of icon [index] into OBJ VRAM at base tile [tileIndex],
     * with the top row at tile [tileIndex] and the bottom row 32 tiles later (1 KiB / 0x400 bytes,
     * matching GBA 2D OBJ mapping). If [index] < 0, clears both 64-byte rows to zero.
     *
     * Ported from src/icon.c: LoadIconObjectGraphics
     */
    public fun loadIconObjectGraphics(
        hardware: BgHardware,
        rom: RomView,
        index: Int,
        tileIndex: Int,
    ) {
        val target = OBJ_VRAM0 + ((tileIndex and 0x3FF) * 0x20)
        if (index < 0) {
            hardware.vram.fill(0, target, target + 0x40)
            hardware.vram.fill(0, target + 0x400, target + 0x400 + 0x40)
        } else {
            val src = ITEM_ICON_TILES + index * ICON_BYTES
            if (rom.isWholeRom) {
                val whole = rom.wholeRomBytes()
                val romBase = RomView.ROM_BASE
                val offset = src - romBase
                whole.copyInto(hardware.vram, target, offset, offset + 0x40)
                whole.copyInto(hardware.vram, target + 0x400, offset + 0x40, offset + 0x80)
            } else {
                for (i in 0 until 0x40) {
                    hardware.vram[target + i] = rom.u8(src + i).toByte()
                    hardware.vram[target + 0x400 + i] = rom.u8(src + 0x40 + i).toByte()
                }
            }
        }
    }
}
