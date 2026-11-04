package gbafe.extract.fe8.ui

import gba.data.Bgr555
import gba.data.IndexedImage
import gba.data.Tiles
import gbafe.extract.IndexedPng
import gbafe.extract.Json

/**
 * FE8 item/UI icons: `item_icon_tiles` (decomp `data/data_item_icon.s`, uncompressed 4bpp, 0x80 bytes =
 * 2×2 tiles row-major per 16×16 icon; `src/icon.c` copies them to 4 consecutive tiles and `DrawIcon` places
 * them 2 wide) and `item_icon_palette[2][16]`. The sheet PNG has 16 icons per row in icon-index order and a
 * 32-color palette (palette 0, then 1); pixel indices are 0–15, the caller picks the palette at run time.
 */
public object IconsExtractor {
    /** Bytes per icon. */
    public const val ICON_BYTES: Int = 0x80

    /** Icons per sheet row. */
    public const val PER_ROW: Int = 16

    /** Result: sheet PNG bytes and the `gbafe.icons` document. */
    public class Output(
        /** The icon sheet PNG. */
        public val png: ByteArray,
        /** The `gbafe.icons` document. */
        public val json: String,
        /** Number of icons. */
        public val count: Int,
    )

    /** Extracts [count] icons at [tilesOffset] with the 2 palettes at [paletteOffset]. */
    public fun extract(rom: ByteArray, tilesOffset: Int, count: Int, paletteOffset: Int): Output {
        val rows = (count + PER_ROW - 1) / PER_ROW
        val sheet = IndexedImage(PER_ROW * 16, rows * 16)
        for (i in 0 until count) {
            val icon = Tiles.decode4bpp(rom, tilesOffset + i * ICON_BYTES, 4, 2)
            val ox = (i % PER_ROW) * 16
            val oy = (i / PER_ROW) * 16
            for (y in 0 until 16) for (x in 0 until 16) sheet[ox + x, oy + y] = icon[x, y]
        }
        val palette = Bgr555.readPalette(rom, paletteOffset, 32)
        val json = Json.write(
            linkedMapOf(
                "format" to "gbafe.icons", "game" to "fe8", "version" to 1, "image" to "icons.png",
                "iconWidth" to 16, "iconHeight" to 16, "perRow" to PER_ROW, "count" to count, "palettes" to 2,
            ),
        )
        return Output(IndexedPng.write(sheet, palette), json, count)
    }
}
