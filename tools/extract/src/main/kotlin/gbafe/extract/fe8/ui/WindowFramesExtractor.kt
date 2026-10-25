package gbafe.extract.fe8.ui

import gbafe.data.Bgr555
import gbafe.data.Lz77
import gbafe.data.Tiles
import gbafe.extract.IndexedPng
import gbafe.extract.Json

/**
 * FE8 window frames (M2-20), decomp `src/uiutils.c`: `sUiFrameImageLookup[4]` (LZ77 4bpp tile sets, one per
 * `config.windowColor`), `sUiFramePaletteLookup[4]` (16 colors each) and `sUiFrameModelTilemapLookup[4]`
 * (16 raw BG tile entries per frame style used by `DrawUiFrame`). Tile sheets are written 16 tiles wide in
 * tile order; the JSON keeps the model tilemaps as raw u16 (tile | flip | palette bits).
 */
public object WindowFramesExtractor {
    /** Result: PNG files by name and the `gbafe.windowframes` document. */
    public class Output(public val files: Map<String, ByteArray>, public val json: String)

    /** Extracts from the three lookup tables (ROM offsets). */
    public fun extract(rom: ByteArray, imageLookup: Int, paletteLookup: Int, modelLookup: Int): Output {
        val files = LinkedHashMap<String, ByteArray>()
        val colors = List(4) { c ->
            val img = Lz77.decode(rom, u32(rom, imageLookup + 4 * c) - ROM).data
            val pal = Bgr555.readPalette(rom, u32(rom, paletteLookup + 4 * c) - ROM, 16)
            val tiles = img.size / Tiles.BYTES_4BPP
            val padded = img.copyOf((tiles + 15) / 16 * 16 * Tiles.BYTES_4BPP)
            val name = "window_frame_$c.png"
            files[name] = IndexedPng.write(Tiles.decode4bpp(padded, 0, padded.size / Tiles.BYTES_4BPP, 16), pal)
            linkedMapOf("windowColor" to c, "image" to name, "tiles" to tiles)
        }
        val models = List(4) { s -> val o = u32(rom, modelLookup + 4 * s) - ROM; List(16) { u16(rom, o + 2 * it) } }
        val json = Json.write(linkedMapOf("format" to "gbafe.windowframes", "game" to "fe8", "version" to 1, "colors" to colors, "modelTilemaps" to models))
        return Output(files, json)
    }

    private const val ROM = 0x08000000
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
