package gbafe.extract.fe8.ui

import gba.data.Bgr555
import gba.data.Lz77
import gba.data.Tiles
import gba.extract.IndexedPng
import gba.extract.Json

/**
 * FE8 window frames, decomp `src/uiutils.c`: `sUiFrameImageLookup[4]` (LZ77 4bpp tile sets),
 * `sUiFramePaletteLookup[4]` (16 colors each) and `sUiFrameModelTilemapLookup[4]`. The image and
 * palette entries are indexed by `gPlaySt.config.windowColor`; in the decomp all four image entries
 * point to the same `gUiFrameImage`, so the four PNGs are expected to be identical and only the
 * palettes differ. The model tilemaps are indexed by the `style` argument of `DrawUiFrame`; each
 * has 16 raw BG tile entries (tile | flip | palette bits), all used by `DrawUiFrame` as the
 * frame's 2x2 corners, edge tiles and 2x2 centre/inner-edge tiles. Tile sheets are written 16
 * tiles wide in tile order; the JSON keeps the model tilemaps as raw u16. The `sLegacyUiFrame*`
 * tables are not extracted.
 */
public object WindowFramesExtractor {
    /** Result: PNG files by name and the `gbafe.windowframes` document. */
    public class Output(
        /** PNG bytes by file name. */
        public val files: Map<String, ByteArray>,
        /** The `gbafe.windowframes` document. */
        public val json: String,
    )

    /**
     * Extracts from the three lookup tables ([imageLookup], [paletteLookup], [modelLookup]):
     * ROM file offsets of the 4-pointer arrays named in the class KDoc.
     */
    public fun extract(
        rom: ByteArray, imageLookup: Int, paletteLookup: Int, modelLookup: Int,
    ): Output {
        val files = LinkedHashMap<String, ByteArray>()
        val colors = List(4) { color ->
            val image = Lz77.decode(rom, u32(rom, imageLookup + 4 * color) - ROM).data
            val palette = Bgr555.readPalette(rom, u32(rom, paletteLookup + 4 * color) - ROM, 16)
            val tiles = image.size / Tiles.BYTES_4BPP
            // Complete the final row so the 16-tile-wide PNG has a rectangular tile sheet.
            val padded = image.copyOf((tiles + 15) / 16 * 16 * Tiles.BYTES_4BPP)
            val name = "window_frame_$color.png"
            files[name] = IndexedPng.write(
                Tiles.decode4bpp(padded, 0, padded.size / Tiles.BYTES_4BPP, 16),
                palette,
            )
            linkedMapOf("windowColor" to color, "image" to name, "tiles" to tiles)
        }
        val models = List(4) { style ->
            val offset = u32(rom, modelLookup + 4 * style) - ROM
            List(16) { u16(rom, offset + 2 * it) }
        }
        val json = Json.write(
            linkedMapOf(
                "format" to "gbafe.windowframes", "game" to "fe8", "version" to 1,
                "colors" to colors, "modelTilemaps" to models,
            ),
        )
        return Output(files, json)
    }

    /** Base address of the GBA ROM; subtracted from pointers to get file offsets. */
    private const val ROM = 0x08000000

    /** Reads a little-endian 16-bit value from the ROM. */
    private fun u16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** Reads a little-endian lookup-table pointer from the ROM. */
    private fun u32(bytes: ByteArray, offset: Int) =
        u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
}
