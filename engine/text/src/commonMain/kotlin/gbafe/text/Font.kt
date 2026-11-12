package gbafe.text

/**
 * `struct Font` (include/fontgrp.h): one text font of the GBA text renderer: which glyph table it
 * reads, where in VRAM its tiles go and which tile and palette numbers it hands out to [Text]s.
 *
 * Fields mirror the C layout; memory addresses are represented as GBA bus integers (`Int`),
 * allowing serialised byte-for-byte comparisons against mGBA RAM dumps via [serialize].
 */
public class Font {
    /**
     * `vramDest` (0x00): VRAM address of the font's first tile (`0x06000000..`): BG tile data for
     * a BG font, OBJ tile data for a sprite font.
     */
    public var vramDest: Int = 0

    /**
     * `glyphs` (0x04): ROM address of the 256-entry table of `struct Glyph *`. ASCII fonts index it
     * by character; Shift-JIS fonts index it by `byte2 - 0x40` and chain glyphs through `sjisNext`.
     */
    public var glyphs: Int = 0

    /**
     * `drawGlyph` (0x08): function pointer to the routine that draws one glyph (`DrawTextGlyph` or
     * `DrawSpriteTextGlyph`, or `DrawTextGlyphNoClear` after `SetTextDrawNoClear`).
     */
    public var drawGlyph: Int = 0

    /**
     * `get_draw_dest` (0x0C): function pointer to the routine that gives the VRAM address a [Text]
     * draws at (`GetTextDrawDest` or `GetSpriteTextDrawDest`).
     */
    public var getDrawDest: Int = 0

    /**
     * `tileref` (0x10, u16): tilemap entry of the font's first tile, which [TextSystem.putText]
     * counts up from. A BG font stores `TILEREF(chr, palid)` (tile number plus palette bank in the
     * high bits); a sprite font stores its OBJ tile number (`InitSpriteTextFont`).
     */
    public var tileref: Int = 0

    /**
     * `chr_counter` (0x12, u16): next free tile offset in the font's tile area; each `InitText`
     * claims `tileWidth` entries of it for one [Text].
     */
    public var chrCounter: Int = 0

    /**
     * `palid` (0x14, u16): palette bank the text is drawn with: 0..15 for a BG font; a sprite font
     * stores 16 plus its OBJ palette number (`InitSpriteTextFont`).
     */
    public var palid: Int = 0

    /**
     * `lang` (0x16, u8): language the font was set up for, copied from `GetLang`:
     * `LANG_JAPANESE` (0, two-byte Shift-JIS strings) or `LANG_ENGLISH` (1, one byte per
     * character). The draw and measure routines treat any non-zero value as ASCII.
     */
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
 * `struct Text` (include/fontgrp.h): one line of text drawn into a strip of tiles two rows tall
 * (a menu entry, a talk-window line); it holds the pen position and colour and where the strip
 * sits in its [Font]'s tile area. Fields mirror the C layout directly.
 */
public class Text {
    /** `chr_position` (0x00, u16): starting tile index of this strip within the parent font. */
    public var chrPosition: Int = 0

    /**
     * `x` (0x02, u8): pen position in pixels from the left of the strip. A `u8` in C;
     * [TextSystem.drawTextGlyph] wraps it at 256 to match.
     */
    public var x: Int = 0

    /**
     * `colorId` (0x03, u8): text colour, an index into the `s2bppTo4bppLutTable` colour tables. In
     * FE8U the system colours are `TEXT_COLOR_SYSTEM_WHITE` 0, `_GRAY` 1, `_BLUE` 2, `_GOLD` 3,
     * `_GREEN` 4 and `_BLACK` 5; the table has further entries (`TEXT_COLOR_MASK` 9 is the mask
     * `DrawTextGlyphNoClear` uses).
     */
    public var colorId: Int = 0

    /** `tile_width` (0x04, u8): width of the text strip in 8x8 tiles. */
    public var tileWidth: Int = 0

    /**
     * `db_enabled` (0x05, s8): set by `InitTextDb`; makes [TextSystem.putText] flip [dbId] so the
     * next draw goes to the other half of the doubled strip.
     */
    public var dbEnabled: Boolean = false

    /** `db_id` (0x06, u8): which half (0 or 1) of the doubled strip is currently drawn to. */
    public var dbId: Int = 0

    /**
     * `is_printing` (0x07, u8): set by `StartTextPrint`, cleared by `TextPrint_OnLoop` when the
     * print proc reaches the end of the string.
     */
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
