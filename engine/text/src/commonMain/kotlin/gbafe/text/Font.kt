package gbafe.text

/**
 * `struct Font` (include/fontgrp.h): the state of one text font. Fields mirror the C layout; the
 * pointers are GBA addresses (`Int`), so a font can be compared byte for byte with RAM through
 * [serialize].
 *
 * Game-neutral. Fields are public mutable like the C struct, because the text code writes them
 * directly.
 */
public class Font {
    /** `vramDest` (0x00): VRAM address text tiles are drawn to. */
    public var vramDest: Int = 0

    /** `glyphs` (0x04): address of the table of 256 `struct Glyph *` (ASCII or Shift-JIS). */
    public var glyphs: Int = 0

    /** `drawGlyph` (0x08): function-pointer value of the glyph drawing routine. */
    public var drawGlyph: Int = 0

    /** `get_draw_dest` (0x0C): function-pointer value of the draw-destination routine. */
    public var getDrawDest: Int = 0

    /** `tileref` (0x10, u16): BG tile reference of the first tile, `TILEREF(chr, palid)`. */
    public var tileref: Int = 0

    /** `chr_counter` (0x12, u16): next free tile of the font's tile area, in half-tile units. */
    public var chrCounter: Int = 0

    /** `palid` (0x14, u16): palette bank. */
    public var palid: Int = 0

    /** `lang` (0x16, u8): 0 = Shift-JIS glyph lists, nonzero = ASCII. */
    public var lang: Int = 0

    /**
     * Writes the font as the 0x18 bytes the GBA keeps in RAM (little-endian, padding byte 0x17
     * zeroed) to [out] at [offset].
     */
    public fun serialize(out: ByteArray, offset: Int = 0) {
        put32(out, offset, vramDest)
        put32(out, offset + 0x04, glyphs)
        put32(out, offset + 0x08, drawGlyph)
        put32(out, offset + 0x0C, getDrawDest)
        put16(out, offset + 0x10, tileref)
        put16(out, offset + 0x12, chrCounter)
        put16(out, offset + 0x14, palid)
        out[offset + 0x16] = lang.toByte()
        out[offset + 0x17] = 0
    }

    /** Size constants. */
    public companion object {
        /** `sizeof(struct Font)`. */
        public const val SIZE: Int = 0x18

        internal fun put16(out: ByteArray, o: Int, v: Int) {
            out[o] = v.toByte()
            out[o + 1] = (v shr 8).toByte()
        }

        internal fun put32(out: ByteArray, o: Int, v: Int) {
            put16(out, o, v)
            put16(out, o + 2, v shr 16)
        }
    }
}

/**
 * `struct Text` (include/fontgrp.h): one text line's tile strip. Fields mirror the C layout.
 */
public class Text {
    /** `chr_position` (0x00, u16): first tile of the strip within the font's tile area. */
    public var chrPosition: Int = 0

    /** `x` (0x02, u8): pen position in pixels. */
    public var x: Int = 0

    /** `colorId` (0x03, u8). */
    public var colorId: Int = 0

    /** `tile_width` (0x04, u8). */
    public var tileWidth: Int = 0

    /** `db_enabled` (0x05, s8): double-buffering flag. */
    public var dbEnabled: Boolean = false

    /** `db_id` (0x06, u8). */
    public var dbId: Int = 0

    /** `is_printing` (0x07, u8). */
    public var isPrinting: Boolean = false

    /** Writes the 8 bytes the GBA keeps in RAM to [out] at [offset]. */
    public fun serialize(out: ByteArray, offset: Int = 0) {
        Font.put16(out, offset, chrPosition)
        out[offset + 2] = x.toByte()
        out[offset + 3] = colorId.toByte()
        out[offset + 4] = tileWidth.toByte()
        out[offset + 5] = if (dbEnabled) 1 else 0
        out[offset + 6] = dbId.toByte()
        out[offset + 7] = if (isPrinting) 1 else 0
    }

    /** Size constants. */
    public companion object {
        /** `sizeof(struct Text)`. */
        public const val SIZE: Int = 8
    }
}
