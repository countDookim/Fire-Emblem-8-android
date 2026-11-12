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

    /** Offset of OBJ character VRAM from the start of GBA VRAM: 64 KiB (0x10000). */
    public const val OBJ_VRAM0: Int = 0x10000

    /** `MAX_ICON_COUNT`: entries of `DrawnIconLookupTable`. */
    public const val MAX_ICON_COUNT: Int = 224

    /**
     * Bytes of one `DrawnIconLookupTable` entry: `References` at +0, `Index` at +1 (the table is
     * 0x380 bytes in the ROM build, so the entries are 4 bytes).
     */
    public const val ICON_ENTRY_SIZE: Int = 4

    /** `MAX_ICON_GFX_COUNT`: entries of `IconGFXIDLookupTable` (1 byte each). */
    public const val MAX_ICON_GFX_COUNT: Int = 32

    /**
     * Clears the icon bookkeeping: all of [drawnIconLookupTable] (`DrawnIconLookupTable`, the
     * `MAX_ICON_COUNT` entries) and the first 0x20 bytes of
     * [iconGfxIdLookupTable] (`IconGFXIDLookupTable`).
     *
     * Ported from src/icon.c: ResetIconGraphics
     */
    public fun resetIconGraphics(drawnIconLookupTable: ByteArray, iconGfxIdLookupTable: ByteArray) {
        drawnIconLookupTable.fill(0, 0, MAX_ICON_COUNT * ICON_ENTRY_SIZE)
        iconGfxIdLookupTable.fill(0, 0, 0x20)
    }

    /**
     * Returns the base OBJ tile index allocated for dynamic icon slot [index]. Slots count down
     * from tile 0x300 in steps of four tiles (one 16x16 icon).
     *
     * Transcribed from src/icon.c: GetIconGfxTileIndex (not verified: formula 0x300 - index * 4; LoadIconObjectGraphics is verified at the PopupProc_GfxDraw tile 0x240)
     */
    public fun getIconGfxTileIndex(index: Int): Int = 0x300 - index * 4

    /**
     * Ported from src/icon.c: GetIconGfxIndex
     *
     * Takes the first free slot of [iconGfxIdLookupTable] for icon [index] (stored as
     * `index + 1`); -1 when all [MAX_ICON_GFX_COUNT] are taken.
     */
    public fun getIconGfxIndex(iconGfxIdLookupTable: ByteArray, index: Int): Int {
        for (i in 0 until MAX_ICON_GFX_COUNT) {
            if (iconGfxIdLookupTable[i].toInt() == 0) {
                iconGfxIdLookupTable[i] = (index + 1).toByte()
                return i
            }
        }
        return -1
    }

    /**
     * Ported from src/icon.c: GetIconTileIndex
     *
     * The BG tile of icon [index]: counts a reference to it in [drawnIconLookupTable] and, the
     * first time, takes a graphics slot and copies the icon's 4 tiles to BG VRAM (the
     * `RegisterDataMove` is done at once here). The reference-count cap (0xFF) is not verified.
     */
    public fun getIconTileIndex(
        drawnIconLookupTable: ByteArray,
        iconGfxIdLookupTable: ByteArray,
        hardware: BgHardware,
        rom: RomView,
        index: Int,
    ): Int {
        val refs = index * ICON_ENTRY_SIZE
        val slot = refs + 1

        if (drawnIconLookupTable[slot].toInt() != 0) {
            if ((drawnIconLookupTable[refs].toInt() and 0xFF) < 0xFF) drawnIconLookupTable[refs]++

            return getIconGfxTileIndex(drawnIconLookupTable[slot].toInt() and 0xFF)
        }

        drawnIconLookupTable[refs]++
        drawnIconLookupTable[slot] = (getIconGfxIndex(iconGfxIdLookupTable, index) + 1).toByte()

        val tile = getIconGfxTileIndex(drawnIconLookupTable[slot].toInt() and 0xFF)
        val dest = (0x20 * tile) and 0x1FFE0
        val src = ITEM_ICON_TILES + index * ICON_BYTES
        for (i in 0 until ICON_BYTES) hardware.vram[dest + i] = rom.u8(src + i).toByte()

        return tile
    }

    /**
     * Ported from src/icon.c: DrawIcon
     *
     * Writes the 2x2 BG tiles of icon [iconIndex] (none for a negative index) with [palBase]
     * added to [tm] at [at], [at] + 1, [at] + 0x20 and [at] + 0x21.
     */
    public fun drawIcon(
        tm: ShortArray,
        at: Int,
        iconIndex: Int,
        palBase: Int,
        drawnIconLookupTable: ByteArray,
        iconGfxIdLookupTable: ByteArray,
        hardware: BgHardware,
        rom: RomView,
    ) {
        if (iconIndex < 0) {
            tm[at] = 0
            tm[at + 1] = 0
            tm[at + 0x20] = 0
            tm[at + 0x21] = 0
            return
        }

        var tile = getIconTileIndex(
            drawnIconLookupTable, iconGfxIdLookupTable, hardware, rom, iconIndex,
        ) + palBase

        tm[at] = tile++.toShort()
        tm[at + 1] = tile++.toShort()
        tm[at + 0x20] = tile++.toShort()
        tm[at + 0x21] = tile.toShort()
    }

    /**
     * Loads 16 colours of icon palette [index] (0 or 1) into palette slot [paletteSlot]
     * (0..31).
     *
     * Ported from src/icon.c: LoadIconPalette
     */
    public fun loadIconPalette(hardware: BgHardware, rom: RomView, index: Int, paletteSlot: Int) {
        val src = ITEM_ICON_PALETTE + index * 0x20
        val colors = rom.pal(src, 16)
        colors.copyInto(hardware.palette, paletteSlot * 16)
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
        // Tile numbers wrap at 1024 (the 10-bit OBJ tile index); each 4bpp tile is 0x20 bytes.
        val target = OBJ_VRAM0 + ((tileIndex and 0x3FF) * 0x20)
        if (index < 0) {
            hardware.vram.fill(0, target, target + 0x40)
            hardware.vram.fill(0, target + 0x400, target + 0x400 + 0x40)
        } else {
            val src = ITEM_ICON_TILES + index * ICON_BYTES
            if (rom.isWholeRom) {
                val romBytes = rom.wholeRomBytes()
                val romBase = RomView.ROM_BASE
                val offset = src - romBase
                romBytes.copyInto(hardware.vram, target, offset, offset + 0x40)
                romBytes.copyInto(hardware.vram, target + 0x400, offset + 0x40, offset + 0x80)
            } else {
                for (i in 0 until 0x40) {
                    hardware.vram[target + i] = rom.u8(src + i).toByte()
                    hardware.vram[target + 0x400 + i] = rom.u8(src + 0x40 + i).toByte()
                }
            }
        }
    }
}
