package fe8.game.ui

import fe8.game.RomView
import gbafe.render.BgHardware

/**
 * The 16x16 icons of items and weapon types (decomp `src/icon.c`), as shown beside item names in
 * the unit's item menu, the shop, the trade and supply screens, the stat screen, popups and the
 * item panel. An icon index (`GetItemIconId(item)` for an item) picks one of 224 icons in ROM. To
 * draw one on a BG, the icon's four tiles are copied into BG tile VRAM once, and a lookup table
 * counts how many places use it; to draw one as a sprite, its tiles are copied straight to
 * OBJ VRAM ([loadIconObjectGraphics]).
 */
public object ItemIcon {
    /** ROM address of `item_icon_tiles` (224 4bpp icons, 128 bytes each; [MAX_ICON_COUNT]). */
    public const val ITEM_ICON_TILES: Int = 0x085926F4

    /**
     * ROM address of `item_icon_palette` (2 palettes of 16 BGR555 colours each), right after the
     * tiles; callers load both into consecutive banks (`BGPAL_ICONS` = 4 .. 5 for BG icons).
     */
    public const val ITEM_ICON_PALETTE: Int = 0x085996F4

    /** Bytes per icon (`0x80` in the decomp): 16x16 pixels in 4bpp = 4 tiles of 32 bytes. */
    public const val ICON_BYTES: Int = 0x80

    /** `OBJ_VRAM0`: offset of the sprite tile memory in GBA VRAM, 64 KiB (0x10000). */
    public const val OBJ_VRAM0: Int = 0x10000

    /**
     * `MAX_ICON_COUNT`: number of icons in ROM, and entries of `DrawnIconLookupTable` (one per
     * icon, holding how many places draw it and which graphics slot it occupies).
     */
    public const val MAX_ICON_COUNT: Int = 224

    /**
     * Bytes of one `DrawnIconLookupTable` entry (`struct IconStruct`, include/icon.h):
     * `References` at +0 (a count of the places drawing the icon, capped at 0xFF) and `Index` at
     * +1 (graphics slot plus one, 0 when the icon is not in VRAM). The table is 0x380 bytes in
     * the ROM build, so the entries are 4 bytes.
     */
    public const val ICON_ENTRY_SIZE: Int = 4

    /**
     * `MAX_ICON_GFX_COUNT`: BG graphics slots for icons, and entries of `IconGFXIDLookupTable`
     * (1 byte each: the icon index plus one using the slot, 0 when the slot is free).
     */
    public const val MAX_ICON_GFX_COUNT: Int = 32

    /**
     * Forgets every icon loaded into BG VRAM: zeroes all of [drawnIconLookupTable]
     * (`DrawnIconLookupTable`) and the first 0x20 bytes of [iconGfxIdLookupTable]
     * (`IconGFXIDLookupTable`). A screen that draws BG icons calls this when it sets up its VRAM.
     *
     * Ported from src/icon.c: ResetIconGraphics
     */
    public fun resetIconGraphics(drawnIconLookupTable: ByteArray, iconGfxIdLookupTable: ByteArray) {
        drawnIconLookupTable.fill(0, 0, MAX_ICON_COUNT * ICON_ENTRY_SIZE)
        iconGfxIdLookupTable.fill(0, 0, 0x20)
    }

    /**
     * Returns the BG tile number of the graphics slot [index] (as stored in
     * `DrawnIconLookupTable[].Index`, so 1 or more). Slots count down from tile 0x300 in steps of
     * four tiles (one 16x16 icon), inside the BG icon tile range `BGCHR_ICON_BEGIN` 0x280 to
     * `BGCHR_ICON_END` 0x300 of include/constants/video-global.h.
     *
     * Transcribed from src/icon.c: GetIconGfxTileIndex (not verified: formula 0x300 - index * 4; LoadIconObjectGraphics is verified at the PopupProc_GfxDraw tile 0x240)
     */
    public fun getIconGfxTileIndex(index: Int): Int = 0x300 - index * 4

    /**
     * Ported from src/icon.c: GetIconGfxIndex
     *
     * Claims the first free graphics slot of [iconGfxIdLookupTable] for icon [index] (stored as
     * `index + 1`, so 0 means free) and returns the slot number; -1 when all
     * [MAX_ICON_GFX_COUNT] slots are taken, which the decomp does not guard against.
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
     * Returns the BG tile number of icon [index], loading the icon into BG VRAM if it is not there
     * yet. Each call counts a reference to it in [drawnIconLookupTable] (capped at 0xFF); the
     * first time, it takes a graphics slot and copies the icon's 4 tiles (0x80 bytes) to BG VRAM
     * (the decomp queues this with `RegisterDataMove`; it is done at once here). The reference
     * count is never lowered by this code; `ClearIconGfx` frees a slot and is not ported. The
     * reference-count cap (0xFF) is not verified.
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
     * Draws icon [iconIndex] as 2x2 BG tiles into the tilemap [tm] at entry [at] ([at], [at] + 1,
     * [at] + 0x20, [at] + 0x21; 0x20 is the width of a 32-tile tilemap row). [palBase] is added
     * to each tile entry, so it carries the palette bank in bits 12 to 15 (`OamPalBase` in the
     * decomp; a BG icon uses bank 4 or 5, `BGPAL_ICONS`), not an OBJ palette. A negative index
     * writes four blank (0) tiles, so nothing is drawn.
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
     * Loads icon palette [index] (0 or 1; 16 colours from `item_icon_palette`) into palette bank
     * [paletteSlot] (0..31: banks 0 to 15 are for BGs, 16 to 31 for sprites) and flags the
     * palette for upload.
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
     * Copies icon [index] into OBJ VRAM so a sprite can show it: the top two tiles (0x40 bytes) go
     * to sprite tile [tileIndex] (taken modulo 0x400) and the bottom two to the tiles 32 later
     * (0x400 bytes on), the layout of 2D sprite tile mapping where a tile row is 32 tiles. If
     * [index] < 0, zeroes both 0x40-byte rows. Used, for instance, for the weapon icons of the
     * battle animation's gauge (`LoadIconObjectGraphics(GetItemIconId(weapon), 0x1DC)`).
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
