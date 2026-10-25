package gbafe.extract.fe8.mapsprites

import gbafe.data.Bgr555
import gbafe.data.Lz77
import gbafe.data.Tiles
import gbafe.extract.IndexedPng
import gbafe.extract.Json

/**
 * Extracts FE8 standing map sprites (`unit_icon_wait_table`, decomp `src/unit_icon_wait_data.c`,
 * `UnitIconWait {u16 pattern; u16 size; sheet}` in `include/unit_icon_data.h`, 8 bytes per entry).
 *
 * Each sheet is LZ77 4bpp holding 3 animation frames stacked vertically: `size` 0 = 16×16, 1 = 16×32
 * (2 tiles wide), 2 = 32×32 (4 tiles wide). PNGs use the blue (player) faction palette, the first of
 * `gPal_MapSprite`'s 4 palettes (blue, red, green, purple; applied by `src/bmudisp.c` at OBJ palette 0x1C);
 * the other factions only swap palettes, so the indices are what matters.
 */
public object MapSpritesExtractor {
    /** Size of `UnitIconWait`. */
    public const val ENTRY_SIZE: Int = 8

    /** Result: PNGs keyed by relative path and the metadata rows. */
    public class Output(
        /** `standing/NNN.png` → PNG bytes. */
        public val files: Map<String, ByteArray>,
        /** One row per standing-sprite id. */
        public val entries: List<Map<String, Any?>>,
    ) {
        /** The `gbafe.mapsprites` document. */
        public val json: String
            get() = Json.write(linkedMapOf("format" to "gbafe.mapsprites", "game" to "fe8", "version" to 1, "standing" to entries))
    }

    /** Extracts [count] standing sprites; [paletteOffset] is `gPal_MapSprite` (4 × 16 colors). */
    public fun extract(rom: ByteArray, tableOffset: Int, count: Int, paletteOffset: Int): Output {
        val palette = Bgr555.readPalette(rom, paletteOffset, 16)
        val files = LinkedHashMap<String, ByteArray>()
        val entries = List(count) { i ->
            val e = tableOffset + i * ENTRY_SIZE
            val pattern = u16(rom, e)
            val size = u16(rom, e + 2)
            val ptr = u16(rom, e + 4) or (u16(rom, e + 6) shl 16)
            val (w, h) = when (size) { 0 -> 16 to 16; 1 -> 16 to 32; 2 -> 32 to 32; else -> throw IllegalArgumentException("sprite $i: size $size") }
            val data = Lz77.decode(rom, ptr - 0x08000000).data
            val tiles = data.size / Tiles.BYTES_4BPP
            require(tiles == 3 * (w / 8) * (h / 8)) { "sprite $i: $tiles tiles for 3 frames of ${w}x$h" }
            val path = "standing/%03d.png".format(i)
            files[path] = IndexedPng.write(Tiles.decode4bpp(data, 0, tiles, w / 8), palette)
            linkedMapOf("id" to i, "image" to path, "width" to w, "height" to h, "frames" to 3, "pattern" to pattern)
        }
        return Output(files, entries)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
