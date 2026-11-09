package gbafe.text

/**
 * `struct Font` (include/fontgrp.h): state of one text font in the GBA text renderer.
 *
 * Fields mirror the C layout; memory addresses are represented as GBA bus integers (`Int`),
 * allowing serialised byte-for-byte comparisons against mGBA RAM dumps via [serialize].
 */
public class Font {
    /** `vramDest` (0x00): VRAM address where text character tiles are drawn (`0x06000000..`). */
    public var vramDest: Int = 0

    /** `glyphs` (0x04): ROM address of the 256-entry table of `struct Glyph *` (ASCII or Shift-JIS). */
    public var glyphs: Int = 0

    /** `drawGlyph` (0x08): function pointer to the active glyph rendering routine. */
    public var drawGlyph: Int = 0

    /** `get_draw_dest` (0x0C): function pointer to the routine computing draw destination addresses. */
    public var getDrawDest: Int = 0

    /** `tileref` (0x10, u16): BG tile reference of the first tile, `TILEREF(chr, palid)`. */
    public var tileref: Int = 0

    /** `chr_counter` (0x12, u16): next free tile index within the font's tile allocation area. */
    public var chrCounter: Int = 0

    /** `palid` (0x14, u16): palette bank ID (0..15 for BG palettes). */
    public var palid: Int = 0

    /** `lang` (0x16, u8): language flag (0 = Shift-JIS Japanese, 1 = ASCII English/Western). */
    public var lang: Int = 0

    /**
     * Serializes this font as the 0x18 (24) bytes kept by the GBA in memory (little-endian,
     * padding byte 0x17 zeroed) into [out] at [offset].
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

    /** Font size constants and serialization helpers. */
    public companion object {
        /** Size in bytes of `struct Font` in C (`sizeof(struct Font)` = 0x18 = 24 bytes). */
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
 * `struct Text` (include/fontgrp.h): state tracking one line or strip of rendered text tiles.
 *
 * Fields mirror the C layout directly.
 */
public class Text {
    /** `chr_position` (0x00, u16): starting tile index of this strip within the parent font. */
    public var chrPosition: Int = 0

    /** `x` (0x02, u8): current drawing pen X coordinate in pixels. */
    public var x: Int = 0

    /** `colorId` (0x03, u8): active text color index (0 = white, 1 = dark grey, 2 = blue, etc.). */
    public var colorId: Int = 0

    /** `tile_width` (0x04, u8): width of the text strip in 8x8 tiles. */
    public var tileWidth: Int = 0

    /** `db_enabled` (0x05, s8): double-buffering enabled flag. */
    public var dbEnabled: Boolean = false

    /** `db_id` (0x06, u8): double-buffer page index (0 or 1). */
    public var dbId: Int = 0

    /** `is_printing` (0x07, u8): true while characters are progressively printing. */
    public var isPrinting: Boolean = false

    /** Serializes this text descriptor as 8 bytes in GBA RAM format into [out] at [offset]. */
    public fun serialize(out: ByteArray, offset: Int = 0) {
        Font.put16(out, offset, chrPosition)
        out[offset + 2] = x.toByte()
        out[offset + 3] = colorId.toByte()
        out[offset + 4] = tileWidth.toByte()
        out[offset + 5] = if (dbEnabled) 1 else 0
        out[offset + 6] = dbId.toByte()
        out[offset + 7] = if (isPrinting) 1 else 0
    }

    /** Text strip size constants. */
    public companion object {
        /** Size in bytes of `struct Text` in C (`sizeof(struct Text)` = 8 bytes). */
        public const val SIZE: Int = 8
    }
}
