package gbafe.extract.font

import gba.data.IndexedImage
import gba.extract.IndexedPng
import gba.extract.Json

/**
 * Extracts a text font (`struct Glyph *TextGlyphs_*[256]`, decomp `include/fontgrp.h`) into a
 * 16×16-cell PNG atlas and a JSON metrics document (format `gbafe.font`). Character code `c` is
 * stored at column `c % 16`, row `c / 16`.
 *
 * `struct Glyph` (72 bytes): `sjisNext` pointer, `sjisByte1`, `width` (advance in pixels),
 * 2 padding bytes, then `u32 bitmap[16]`: one 16-pixel row per word, 2 bits per pixel, pixel x in
 * bits `2x..2x+1` (as `DrawTextGlyphNoClear` in `src/fontgrp.c` shifts them). Pixel values 0..3 are
 * kept as PNG palette indices (0 = background; the colors come from the runtime lookup tables).
 * English fonts index the table by the message byte directly; `sjisNext` chains (Japanese) are not
 * followed. Same layout in all GBA Fire Emblem games.
 */
public object FontExtractor {
    /** Size of `struct Glyph`. */
    public const val GLYPH_SIZE: Int = 72

    /** Cell size in pixels (glyph bitmaps are 16×16). */
    public const val CELL: Int = 16

    /** Preview palette for the 4 pixel values (ARGB). */
    public val PALETTE: IntArray = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFC0C0C0.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt(),
    )

    /** One glyph of a font. */
    public class Glyph(
        /** Character code (message byte) indexing the glyph table. */
        public val code: Int,
        /** Advance width in pixels. */
        public val width: Int,
        /** 16×16 pixel values 0..3, row-major. */
        public val pixels: ByteArray,
    )

    /** Reads the non-null glyphs of the 256-pointer table at ROM offset [tableOffset]. */
    public fun read(rom: ByteArray, tableOffset: Int): List<Glyph> =
        (0 until 256).mapNotNull { code ->
            val glyphAddress = u32(rom, tableOffset + code * 4)
            if (glyphAddress == 0) return@mapNotNull null
            require(glyphAddress ushr 25 == 4) {
                "glyph $code pointer 0x${glyphAddress.toUInt().toString(16)} is not in ROM"
            }
            val glyphOffset = glyphAddress - 0x08000000
            val pixels = ByteArray(CELL * CELL)
            for (y in 0 until CELL) {
                val row = u32(rom, glyphOffset + 8 + y * 4)
                for (x in 0 until CELL) pixels[y * CELL + x] = (row ushr (2 * x) and 3).toByte()
            }
            Glyph(code, rom[glyphOffset + 5].toInt() and 0xFF, pixels)
        }

    /** The 256×256 atlas of [glyphs]; cells of missing codes stay 0. */
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

    /** The atlas as PNG bytes. */
    public fun png(glyphs: List<Glyph>): ByteArray = IndexedPng.write(atlas(glyphs), PALETTE)

    /** The `gbafe.font` metrics document; [image] is the atlas path relative to the JSON file. */
    public fun toJson(game: String, name: String, image: String, glyphs: List<Glyph>): String =
        Json.write(
            linkedMapOf(
                "format" to "gbafe.font", "game" to game, "version" to 1, "name" to name,
                "image" to image, "cell" to CELL,
                "glyphs" to glyphs.map { linkedMapOf("code" to it.code, "width" to it.width) },
            ),
        )

    /** Reads a little-endian word from the ROM. */
    private fun u32(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
