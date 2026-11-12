package gbafe.extract.font

import gba.data.IndexedImage
import gba.extract.IndexedPng
import gba.extract.Json

/**
 * Extracts a text font (`struct Glyph *TextGlyphs_*[256]`, decomp `include/fontgrp.h`) into a
 * 16×16-cell PNG atlas and a JSON metrics document (format `gbafe.font`). Character code `c` is
 * stored at column `c % 16`, row `c / 16`.
 *
 * `struct Glyph` (72 bytes): `sjisNext` pointer, `sjisByte1` (both only used by Shift-JIS fonts),
 * `width` (advance in pixels), 2 padding bytes, then `u32 bitmap[16]`: one 16-pixel row per word,
 * 2 bits per pixel, pixel x in bits `2x..2x+1` (the draw routines `DrawTextGlyph` and
 * `DrawSpriteTextGlyph` in `src/fontgrp.c` shift whole rows by `2 * (pen x and 7)` and look each
 * byte of four pixels up in a colour table). Pixel values 0..3 are kept as PNG palette indices
 * (0 = background; the colours come from the runtime lookup tables, `s2bppTo4bppLutTable`).
 * English fonts (`LANG_ENGLISH`) index the table by the message byte directly; `sjisNext` chains
 * (Japanese) are not followed. The layout is FE8U's, from its decomp; other GBA Fire Emblem games
 * are assumed to match but were not checked.
 */
public object FontExtractor {
    /** Size of `struct Glyph` in bytes: 4 + 1 + 1 + 2 padding + 16 rows of 4 bytes. */
    public const val GLYPH_SIZE: Int = 72

    /** Cell size in pixels: a glyph bitmap is 16 rows of 16 pixels. */
    public const val CELL: Int = 16

    /**
     * Preview palette of the PNG (ARGB): white, light grey, dark grey, black for pixel values 0..3.
     * Not the game's colours; those depend on the text colour chosen at draw time.
     */
    public val PALETTE: IntArray = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFC0C0C0.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt(),
    )

    /** One glyph of a font. */
    public class Glyph(
        /** Character code: the message byte that selects this glyph in the 256-entry table. */
        public val code: Int,
        /** Advance width in pixels. */
        public val width: Int,
        /** 16×16 pixel values 0..3, row-major. */
        public val pixels: ByteArray,
    )

    /**
     * Reads the non-null glyphs of the 256-pointer glyph table (`TextGlyphs_*`) at ROM offset
     * [tableOffset]; a NULL entry is a character the font does not have.
     */
    public fun read(rom: ByteArray, tableOffset: Int): List<Glyph> =
        (0 until 256).mapNotNull { code ->
            val glyphAddress = u32(rom, tableOffset + code * 4)
            if (glyphAddress == 0) return@mapNotNull null
            require(glyphAddress ushr 25 == 4) {
                "glyph $code pointer 0x${glyphAddress.toUInt().toString(16)} is not in ROM"
            }
            val glyphOffset = glyphAddress - 0x08000000
            // `bitmap` starts at +8 of struct Glyph, `width` is at +5.
            val pixels = ByteArray(CELL * CELL)
            for (y in 0 until CELL) {
                val row = u32(rom, glyphOffset + 8 + y * 4)
                for (x in 0 until CELL) pixels[y * CELL + x] = (row ushr (2 * x) and 3).toByte()
            }
            Glyph(code, rom[glyphOffset + 5].toInt() and 0xFF, pixels)
        }

    /**
     * The 256×256 atlas of [glyphs] (16×16 cells of 16×16 pixels); cells of missing codes stay 0.
     */
    public fun atlas(glyphs: List<Glyph>): IndexedImage {
        val image = IndexedImage(CELL * 16, CELL * 16)
        for (glyph in glyphs) {
            val originX = glyph.code % 16 * CELL
            val originY = glyph.code / 16 * CELL
            for (y in 0 until CELL) {
                for (x in 0 until CELL) {
                    image.pixels[(originY + y) * image.width + originX + x] =
                        glyph.pixels[y * CELL + x]
                }
            }
        }
        return image
    }

    /** The atlas of [glyphs] as indexed-PNG bytes using [PALETTE]. */
    public fun png(glyphs: List<Glyph>): ByteArray = IndexedPng.write(atlas(glyphs), PALETTE)

    /**
     * The `gbafe.font` metrics document (version 1) for font [name] of [game]: the cell size and
     * each glyph's code and advance width; [image] is the atlas path relative to the JSON file.
     */
    public fun toJson(game: String, name: String, image: String, glyphs: List<Glyph>): String =
        Json.write(
            linkedMapOf(
                "format" to "gbafe.font", "game" to game, "version" to 1, "name" to name,
                "image" to image, "cell" to CELL,
                "glyphs" to glyphs.map { linkedMapOf("code" to it.code, "width" to it.width) },
            ),
        )

    /** Reads the little-endian 32-bit word of [bytes] at [offset]. */
    private fun u32(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
